package com.yupi.springbootinit.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.gson.Gson;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.mapper.AutoEdaHypothesisMapper;
import com.yupi.springbootinit.mapper.AutoEdaQueryLogMapper;
import com.yupi.springbootinit.mapper.AutoEdaSessionMapper;
import com.yupi.springbootinit.model.dto.autoeda.ContinueExplorationRequest;
import com.yupi.springbootinit.model.dto.autoeda.StartExplorationRequest;
import com.yupi.springbootinit.model.entity.AutoEdaHypothesis;
import com.yupi.springbootinit.model.entity.AutoEdaQueryLog;
import com.yupi.springbootinit.model.entity.AutoEdaSession;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.vo.autoeda.ExplorationStateVO;
import com.yupi.springbootinit.model.vo.autoeda.HypothesisVO;
import com.yupi.springbootinit.model.vo.autoeda.InsightReportVO;
import com.yupi.springbootinit.service.*;
import com.yupi.springbootinit.service.eda.*;
import com.yupi.springbootinit.service.storage.ChartDataResolverService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
public class AutoEdaServiceImpl implements AutoEdaService {

    @Resource
    private ChartService chartService;

    @Resource
    private LineageService lineageService;

    @Resource
    private ClickHouseQueryService clickHouseQueryService;

    @Resource
    private ChartDataResolverService chartDataResolverService;

    @Resource
    private ExplorationBlackboard blackboard;

    @Resource
    private InfoGainCalculator igCalculator;

    @Resource
    private QueryGenerator queryGenerator;

    @Resource
    private HypothesisManager hypothesisManager;

    @Resource
    private ReportComposer reportComposer;

    @Resource
    private AutoEdaSessionMapper sessionMapper;

    @Resource
    private AutoEdaHypothesisMapper hypothesisMapper;

    @Resource
    private AutoEdaQueryLogMapper queryLogMapper;

    private final Gson gson = new Gson();

    @Value("${autoeda.table-name:store_daily_metric}")
    private String defaultTableName;

    @Override
    public String startExploration(StartExplorationRequest request, Long userId) {
        Chart chart = chartService.getById(request.getChartId());
        if (chart == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图表不存在");
        if (!chart.getUserId().equals(userId)) throw new BusinessException(ErrorCode.NO_AUTH_ERROR);

        chartDataResolverService.hydrate(chart);
        String sessionId = "eda_" + UUID.randomUUID().toString().replace("-", "");

        // 初始化探索黑板
        ExplorationBlackboard.SessionState state = blackboard.getOrCreate(sessionId);
        state.setStatus("RUNNING");

        // 保存会话记录
        AutoEdaSession session = new AutoEdaSession();
        session.setSessionId(sessionId);
        session.setChartId(chart.getId());
        session.setUserId(userId);
        session.setStatus("RUNNING");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("maxQueryBudget", request.getMaxQueryBudget());
        config.put("targetMetrics", request.getTargetMetrics());
        config.put("convergenceEpsilon", request.getConvergenceEpsilon());
        config.put("convergenceRounds", request.getConvergenceRounds());
        session.setConfigJson(gson.toJson(config));
        sessionMapper.insert(session);

        // 异步执行探索循环
        CompletableFuture.runAsync(() -> explorationLoop(sessionId, chart, request));

        return sessionId;
    }

    private void explorationLoop(String sessionId, Chart chart, StartExplorationRequest config) {
        ExplorationBlackboard.SessionState state = blackboard.getOrCreate(sessionId);
        int maxBudget = config.getMaxQueryBudget() != null ? config.getMaxQueryBudget() : 30;
        double epsilon = config.getConvergenceEpsilon() != null ? config.getConvergenceEpsilon() : 0.05;
        int convRounds = config.getConvergenceRounds() != null ? config.getConvergenceRounds() : 3;

        // 获取血缘可及字段
        List<String> targetMetrics = config.getTargetMetrics();
        if (targetMetrics == null || targetMetrics.isEmpty()) {
            targetMetrics = Arrays.asList("revenue", "profit", "gross_margin");
        }
        String rootMetric = targetMetrics.get(0);
        List<String> downstreamNodes = lineageService.getDownstreamNodes(rootMetric);
        List<String> availableColumns = new ArrayList<>(downstreamNodes);
        availableColumns.add(0, rootMetric);

        try {
            while (state.getQueryCount() < maxBudget) {
                // Step 1: 生成候选查询池并选择 IG 最大的
                Map<String, List<String>> dimensions = generateCandidateDimensions(state, rootMetric);
                String sql = queryGenerator.generateSql(defaultTableName, availableColumns,
                        "探索维度: " + dimensions, dimensions, rootMetric);

                // Step 2: 执行查询
                String queryId = "q_" + UUID.randomUUID().toString().substring(0, 8);
                ClickHouseQueryService.QueryResult result = clickHouseQueryService.executeWithStats(sql);

                // Step 3: 计算 IG
                double ig = igCalculator.calculate(dimensions, state.getExploredRegions(),
                        0, rootMetric, result.getScannedRows(), rootMetric);
                state.setLastMaxIg(ig);

                // Step 4: 记录查询日志
                String resultSummary = summarizeResult(result.getRows(), 2000);
                saveQueryLog(sessionId, queryId, sql, dimensions, ig, resultSummary,
                        result.getRowCount(), (int) result.getExecutionMs(), (int) result.getScannedRows(), rootMetric);

                // Step 5: 更新已探索区域
                for (Map.Entry<String, List<String>> entry : dimensions.entrySet()) {
                    for (String val : entry.getValue()) {
                        state.markExplored(entry.getKey(), val);
                    }
                }
                state.addTrace(queryId);

                // Step 6: 生成 + 验证假设
                List<HypothesisVO> newHypotheses = hypothesisManager.generateHypotheses(
                        resultSummary, state.getPendingHypotheses(), availableColumns, rootMetric);
                for (HypothesisVO h : newHypotheses) {
                    state.addPending(h);
                    saveHypothesis(sessionId, h);
                }

                // Step 7: Bayesian 分类循环
                HypothesisManager.HypothesisCycleResult cycleResult = hypothesisManager.runCycle(
                        state.getPendingHypotheses(), state.getActiveHypotheses(), sessionId);
                for (HypothesisVO h : cycleResult.verified) {
                    updateHypothesisStatus(sessionId, h.getHypothesisId(), "VERIFIED", h.getConfidencePosterior());
                }
                for (HypothesisVO h : cycleResult.discarded) {
                    updateHypothesisStatus(sessionId, h.getHypothesisId(), "DISCARDED", h.getConfidencePosterior());
                }
                state.setActiveHypotheses(new ArrayList<>(cycleResult.topActive));

                // Step 8: 检查收敛
                if (state.checkConvergence(epsilon, convRounds)) {
                    log.info("Exploration converged for session {}", sessionId);
                    break;
                }
            }

            // 探索完成：生成报告
            composeAndSaveReport(sessionId, chart);
            state.setStatus("COMPLETED");
            updateSessionStatus(sessionId, "COMPLETED", state.getQueryCount());

        } catch (Exception e) {
            log.error("Exploration loop failed for session {}: {}", sessionId, e.getMessage());
            state.setStatus("FAILED");
            updateSessionStatus(sessionId, "FAILED", state.getQueryCount());
        }
    }

    @Override
    public ExplorationStateVO getState(String sessionId) {
        ExplorationBlackboard.SessionState state = blackboard.get(sessionId);
        if (state == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");

        ExplorationStateVO vo = new ExplorationStateVO();
        vo.setSessionId(sessionId);
        vo.setStatus(state.getStatus());
        vo.setPendingHypothesisCount(state.getPendingHypotheses().size());
        vo.setVerifiedHypothesisCount(state.getVerifiedHypotheses().size());
        vo.setQueriesExecuted(state.getQueryCount());
        vo.setLastMaxIg(state.getLastMaxIg());
        vo.setConverged("COMPLETED".equals(state.getStatus()));

        // 简化 exploredRegions 为 Map<String, List<String>>
        Map<String, List<String>> explored = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : state.getExploredRegions().entrySet()) {
            explored.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        vo.setExploredRegions(explored);
        vo.setActiveHypotheses(new ArrayList<>(state.getActiveHypotheses()));
        vo.setSuggestedNextDirections(suggestNextDirections(state));
        return vo;
    }

    @Override
    public InsightReportVO getReport(String sessionId) {
        AutoEdaSession session = sessionMapper.selectOne(
                new QueryWrapper<AutoEdaSession>().eq("session_id", sessionId));
        if (session == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");
        if (!"COMPLETED".equals(session.getStatus()))
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "探索尚未完成");

        if (StringUtils.isNotBlank(session.getResultJson())) {
            return gson.fromJson(session.getResultJson(), InsightReportVO.class);
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "报告生成中");
    }

    @Override
    public ExplorationStateVO continueExploration(ContinueExplorationRequest request) {
        // 重新激活会话，追加查询预算
        ExplorationBlackboard.SessionState state = blackboard.getOrCreate(request.getSessionId());
        state.setStatus("RUNNING");
        state.resetConvergenceCounter();

        // 将用户追问作为新的探索方向注入
        String queryId = "q_followup_" + UUID.randomUUID().toString().substring(0, 8);
        state.addTrace(queryId);

        // TODO: 基于用户追问生成专门查询并重启探索循环
        return getState(request.getSessionId());
    }

    @Override
    public HypothesisVO getHypothesisDetail(String sessionId, String hypothesisId) {
        AutoEdaHypothesis entity = hypothesisMapper.selectOne(
                new QueryWrapper<AutoEdaHypothesis>()
                        .eq("session_id", sessionId)
                        .eq("hypothesis_id", hypothesisId));
        if (entity == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "假设不存在");
        return entityToVO(entity);
    }

    @Override
    public boolean stopExploration(String sessionId) {
        ExplorationBlackboard.SessionState state = blackboard.get(sessionId);
        if (state != null) {
            state.setStatus("STOPPED");
            updateSessionStatus(sessionId, "STOPPED", state.getQueryCount());
            return true;
        }
        return false;
    }

    // ---------- Private Helpers ----------

    private Map<String, List<String>> generateCandidateDimensions(ExplorationBlackboard.SessionState state, String rootMetric) {
        Map<String, List<String>> dims = new LinkedHashMap<>();
        // 简化：从血缘下游节点中采样维度
        List<String> downstream = lineageService.getDownstreamNodes(rootMetric);
        if (!downstream.isEmpty()) {
            String dim = downstream.get(state.getQueryCount() % downstream.size());
            dims.put("category", Collections.singletonList(dim));
        }
        dims.put("time", Collections.singletonList("recent"));
        return dims;
    }

    private String summarizeResult(List<Map<String, Object>> rows, int maxLen) {
        if (rows == null || rows.isEmpty()) return "查询返回空结果";
        String json = gson.toJson(rows.size() <= 5 ? rows : rows.subList(0, 5));
        return json.length() <= maxLen ? json : json.substring(0, maxLen) + "...";
    }

    private void saveQueryLog(String sessionId, String queryId, String sql,
                               Map<String, List<String>> dimensions, double ig,
                               String resultSummary, int rowCount, int execMs,
                               int scannedRows, String lineagePath) {
        AutoEdaQueryLog log = new AutoEdaQueryLog();
        log.setSessionId(sessionId);
        log.setQueryId(queryId);
        log.setQuerySql(sql);
        log.setQueryDimensions(gson.toJson(dimensions));
        log.setIgScore(ig);
        log.setResultSummary(resultSummary);
        log.setResultRowCount(rowCount);
        log.setExecutionMs(execMs);
        log.setCostEstimated(scannedRows);
        log.setLineagePath(lineagePath);
        queryLogMapper.insert(log);
    }

    private void saveHypothesis(String sessionId, HypothesisVO vo) {
        AutoEdaHypothesis entity = new AutoEdaHypothesis();
        entity.setSessionId(sessionId);
        entity.setHypothesisId(vo.getHypothesisId());
        entity.setType(vo.getType());
        entity.setStatement(vo.getStatement());
        entity.setConfidencePrior(vo.getConfidencePrior() != null ? vo.getConfidencePrior() : 0.3);
        entity.setConfidencePosterior(vo.getConfidencePosterior() != null ? vo.getConfidencePosterior() : 0.3);
        entity.setStatus(vo.getStatus());
        entity.setLineagePath(vo.getLineagePath());
        entity.setParentHypothesisId(vo.getParentHypothesisId() != null ? vo.getParentHypothesisId() : null);
        entity.setSourceQueryIds(vo.getSourceQueryIds() != null ? String.join(",", vo.getSourceQueryIds()) : null);
        hypothesisMapper.insert(entity);
    }

    private void updateHypothesisStatus(String sessionId, String hypothesisId, String status, Double posterior) {
        AutoEdaHypothesis entity = new AutoEdaHypothesis();
        entity.setStatus(status);
        if (posterior != null) entity.setConfidencePosterior(posterior);
        hypothesisMapper.update(entity,
                new QueryWrapper<AutoEdaHypothesis>()
                        .eq("session_id", sessionId)
                        .eq("hypothesis_id", hypothesisId));
    }

    private void updateSessionStatus(String sessionId, String status, int totalQueries) {
        AutoEdaSession session = new AutoEdaSession();
        session.setStatus(status);
        session.setTotalQueries(totalQueries);
        session.setEndTime(new Date());
        sessionMapper.update(session, new QueryWrapper<AutoEdaSession>().eq("session_id", sessionId));
    }

    private void composeAndSaveReport(String sessionId, Chart chart) {
        ExplorationBlackboard.SessionState state = blackboard.get(sessionId);
        List<AutoEdaQueryLog> queryLogs = queryLogMapper.selectList(
                new QueryWrapper<AutoEdaQueryLog>().eq("session_id", sessionId).orderByAsc("create_time"));

        InsightReportVO report = reportComposer.compose(
                state.getVerifiedHypotheses(),
                state.getPendingHypotheses(),
                queryLogs,
                chart.getName(),
                chart.getId(),
                sessionId);

        AutoEdaSession session = new AutoEdaSession();
        session.setResultJson(gson.toJson(report));
        sessionMapper.update(session, new QueryWrapper<AutoEdaSession>().eq("session_id", sessionId));
    }

    private List<String> suggestNextDirections(ExplorationBlackboard.SessionState state) {
        List<String> suggestions = new ArrayList<>();
        if (state.getPendingHypotheses().size() > 3) {
            suggestions.add("还有 " + state.getPendingHypotheses().size() + " 个假设待验证，继续探索以确认或排除");
        }
        if (state.getActiveHypotheses() != null && !state.getActiveHypotheses().isEmpty()) {
            HypothesisVO top = state.getActiveHypotheses().get(0);
            suggestions.add("当前最优先假设: " + top.getStatement());
        }
        return suggestions;
    }

    private HypothesisVO entityToVO(AutoEdaHypothesis entity) {
        HypothesisVO vo = new HypothesisVO();
        vo.setHypothesisId(entity.getHypothesisId());
        vo.setType(entity.getType());
        vo.setStatement(entity.getStatement());
        vo.setConfidencePrior(entity.getConfidencePrior());
        vo.setConfidencePosterior(entity.getConfidencePosterior());
        vo.setStatus(entity.getStatus());
        vo.setLineagePath(entity.getLineagePath());
        return vo;
    }
}
