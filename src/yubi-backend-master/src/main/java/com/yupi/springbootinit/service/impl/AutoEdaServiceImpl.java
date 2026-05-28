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
import com.yupi.springbootinit.model.vo.autoeda.AutoEdaChartOptionVO;
import com.yupi.springbootinit.model.vo.autoeda.AutoEdaSessionSummaryVO;
import com.yupi.springbootinit.model.vo.autoeda.ExplorationStateVO;
import com.yupi.springbootinit.model.vo.autoeda.HypothesisVO;
import com.yupi.springbootinit.model.vo.autoeda.InsightReportVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

    @Value("${autoeda.table-name:yubi_analytics.store_daily_metric_ch}")
    private String defaultTableName;

    @Override
    public List<AutoEdaChartOptionVO> listExplorableCharts(Long userId) {
        QueryWrapper<Chart> wrapper = new QueryWrapper<>();
        wrapper.eq("userId", userId);
        wrapper.eq("isDelete", 0);
        wrapper.orderByDesc("createTime");
        Page<Chart> page = chartService.page(new Page<>(1, 50), wrapper);
        List<AutoEdaChartOptionVO> options = new ArrayList<>();
        for (Chart chart : page.getRecords()) {
            AutoEdaChartOptionVO vo = new AutoEdaChartOptionVO();
            vo.setId(chart.getId());
            vo.setName(chart.getName());
            vo.setGoal(chart.getGoal());
            vo.setChartType(chart.getChartType());
            vo.setStatus(chart.getStatus());
            options.add(vo);
        }
        return options;
    }

    @Override
    public List<AutoEdaSessionSummaryVO> listMySessions(Long userId, int limit) {
        QueryWrapper<AutoEdaSession> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        wrapper.eq("is_delete", 0);
        wrapper.orderByDesc("create_time");
        Page<AutoEdaSession> page = sessionMapper.selectPage(new Page<>(1, limit), wrapper);
        List<AutoEdaSessionSummaryVO> list = new ArrayList<>();
        for (AutoEdaSession s : page.getRecords()) {
            AutoEdaSessionSummaryVO vo = new AutoEdaSessionSummaryVO();
            vo.setSessionId(s.getSessionId());
            vo.setChartId(s.getChartId());
            vo.setStatus(s.getStatus());
            vo.setTotalQueries(s.getTotalQueries());
            vo.setStartTime(s.getStartTime());
            vo.setEndTime(s.getEndTime());
            vo.setCreateTime(s.getCreateTime());
            if (s.getChartId() != null) {
                Chart chart = chartService.getById(s.getChartId());
                if (chart != null) {
                    vo.setChartName(chart.getName());
                }
            }
            list.add(vo);
        }
        return list;
    }

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

        // 获取真实 ClickHouse 列名（不可用时使用已知 schema 降级）
        List<String> availableColumns = clickHouseQueryService.discoverColumns(defaultTableName);
        if (availableColumns.isEmpty()) {
            availableColumns = getDefaultColumns();
            log.warn("ClickHouse column discovery failed, using default columns for {}", defaultTableName);
        }
        log.info("Discovered {} columns from table {}: {}", availableColumns.size(), defaultTableName, availableColumns);

        // 使用 chart goal 作为探索意图上下文
        String explorationIntent = StringUtils.isNotBlank(chart.getGoal()) ? chart.getGoal() : "通用数据探索";

        int consecutiveErrors = 0;
        try {
            while (state.getQueryCount() < maxBudget) {
                try {
                    // Step 1: 生成 SQL（优先模板，避免 LLM 生成非法 SQL）
                    Map<String, List<String>> dimensions = generateCandidateDimensions(state, availableColumns);
                    String sql = queryGenerator.generateTemplateSql(defaultTableName, dimensions, availableColumns);
                    if (StringUtils.isBlank(sql)) {
                        sql = queryGenerator.generateSql(defaultTableName, availableColumns,
                                explorationIntent, dimensions, "探索维度: " + dimensions);
                    }

                    // Step 2: 执行查询
                    String queryId = "q_" + UUID.randomUUID().toString().substring(0, 8);
                    ClickHouseQueryService.QueryResult result = clickHouseQueryService.executeWithStats(sql);

                    // Step 3: 计算 IG
                    String igMetric = pickMetricColumn(availableColumns);
                    double surprise = estimateSurprise(result.getRows(), igMetric);
                    double ig = igCalculator.calculate(dimensions, state.getExploredRegions(),
                            surprise, igMetric, result.getScannedRows(), igMetric);
                    state.setLastMaxIg(ig);

                    // Step 4: 记录查询日志
                    String resultSummary = summarizeResult(result.getRows(), 2000);
                    if (result.getRowCount() == 0) {
                        resultSummary = "查询返回空结果（请确认 ClickHouse 已启动并已同步指标数据）";
                    }
                    saveQueryLog(sessionId, queryId, sql, dimensions, ig, resultSummary,
                            result.getRowCount(), (int) result.getExecutionMs(), (int) result.getScannedRows(),
                            defaultTableName);

                    // Step 5: 更新已探索区域
                    for (Map.Entry<String, List<String>> entry : dimensions.entrySet()) {
                        for (String val : entry.getValue()) {
                            state.markExplored(entry.getKey(), val);
                        }
                    }
                    state.addTrace(queryId);

                    // Step 6: 生成假设
                    List<HypothesisVO> newHypotheses = hypothesisManager.generateHypotheses(
                            resultSummary, state.getPendingHypotheses(), availableColumns, defaultTableName);
                    if (newHypotheses.isEmpty()) {
                        newHypotheses = hypothesisManager.generateRuleBasedHypotheses(
                                resultSummary, availableColumns, queryId);
                    }
                    for (HypothesisVO h : newHypotheses) {
                        if (h.getSourceQueryIds() == null || h.getSourceQueryIds().isEmpty()) {
                            h.setSourceQueryIds(Collections.singletonList(queryId));
                        }
                        state.addPending(h);
                        saveHypothesis(sessionId, h);
                    }

                    // Step 7: Bayesian 分类
                    HypothesisManager.HypothesisCycleResult cycleResult = hypothesisManager.runCycle(
                            state.getPendingHypotheses(), state.getActiveHypotheses(), sessionId);
                    for (HypothesisVO h : cycleResult.verified) {
                        updateHypothesisStatus(sessionId, h.getHypothesisId(), "VERIFIED", h.getConfidencePosterior());
                    }
                    for (HypothesisVO h : cycleResult.discarded) {
                        updateHypothesisStatus(sessionId, h.getHypothesisId(), "DISCARDED", h.getConfidencePosterior());
                    }
                    state.getPendingHypotheses().clear();
                    state.getPendingHypotheses().addAll(cycleResult.keepPending);
                    state.setActiveHypotheses(new ArrayList<>(cycleResult.topActive));

                    consecutiveErrors = 0;

                    if (state.checkConvergence(epsilon, convRounds)) {
                        log.info("Exploration converged for session {}", sessionId);
                        break;
                    }
                } catch (Exception roundEx) {
                    consecutiveErrors++;
                    log.warn("Exploration round failed for session {} (round {}): {}",
                            sessionId, state.getQueryCount() + 1, roundEx.getMessage(), roundEx);
                    state.setLastError(roundEx.getMessage());
                    if (consecutiveErrors >= 3) {
                        throw roundEx;
                    }
                }
            }

            composeAndSaveReport(sessionId, chart);
            state.setStatus("COMPLETED");
            state.setLastError(null);
            updateSessionStatus(sessionId, "COMPLETED", state.getQueryCount());

        } catch (Exception e) {
            log.error("Exploration loop failed for session {}: {}", sessionId, e.getMessage(), e);
            state.setStatus("FAILED");
            state.setLastError(e.getMessage());
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

        AutoEdaSession session = sessionMapper.selectOne(
                new QueryWrapper<AutoEdaSession>().eq("session_id", sessionId));
        if (session != null && StringUtils.isNotBlank(session.getConfigJson())) {
            Map<?, ?> cfg = gson.fromJson(session.getConfigJson(), Map.class);
            Object budget = cfg.get("maxQueryBudget");
            if (budget instanceof Number) {
                vo.setQueryBudget(((Number) budget).intValue());
            }
        }
        if (vo.getQueryBudget() == null) vo.setQueryBudget(30);
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
        vo.setErrorMessage(state.getLastError());
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

    @Override
    public boolean deleteSession(String sessionId, Long userId) {
        if (StringUtils.isBlank(sessionId)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "会话编号无效");
        }
        AutoEdaSession session = sessionMapper.selectOne(
                new QueryWrapper<AutoEdaSession>()
                        .eq("session_id", sessionId)
                        .eq("user_id", userId)
                        .eq("is_delete", 0));
        if (session == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "探索记录不存在");
        }

        if ("RUNNING".equals(session.getStatus())) {
            stopExploration(sessionId);
        }

        hypothesisMapper.delete(new QueryWrapper<AutoEdaHypothesis>().eq("session_id", sessionId));
        queryLogMapper.delete(new QueryWrapper<AutoEdaQueryLog>().eq("session_id", sessionId));
        sessionMapper.delete(new QueryWrapper<AutoEdaSession>().eq("session_id", sessionId));

        blackboard.remove(sessionId);
        return true;
    }

    // ---------- Private Helpers ----------

    private List<String> getDefaultColumns() {
        return Arrays.asList(
                "statDate", "storeId", "revenue", "grossMarginRate", "avgTicket",
                "tableTurnover", "seatOccupancy", "takeawayRatio", "dineInRatio",
                "prepTimeAvgMin", "cancelRate", "badReviewRate"
        );
    }

    private String pickMetricColumn(List<String> availableColumns) {
        for (String col : availableColumns) {
            String lower = col.toLowerCase();
            if (lower.contains("revenue") || lower.equals("grossmarginrate")) return col;
        }
        return availableColumns.isEmpty() ? "revenue" : availableColumns.get(0);
    }

    private double estimateSurprise(List<Map<String, Object>> rows, String metricCol) {
        if (rows == null || rows.isEmpty()) return 0;
        List<Double> values = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (e.getKey() != null && e.getKey().toLowerCase().contains(metricCol.toLowerCase().replace("_", ""))) {
                    if (e.getValue() instanceof Number) values.add(((Number) e.getValue()).doubleValue());
                }
            }
            Object direct = row.get(metricCol);
            if (direct instanceof Number) values.add(((Number) direct).doubleValue());
            Object totalRev = row.get("total_revenue");
            if (totalRev instanceof Number) values.add(((Number) totalRev).doubleValue());
        }
        if (values.size() < 2) return 0;
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0);
        double std = Math.sqrt(variance);
        if (std < 1e-9) return 0;
        double maxDev = values.stream().mapToDouble(v -> Math.abs(v - mean) / std).max().orElse(0);
        return maxDev;
    }

    private Map<String, List<String>> generateCandidateDimensions(ExplorationBlackboard.SessionState state, List<String> availableColumns) {
        Map<String, List<String>> dims = new LinkedHashMap<>();
        // 使用真实 ClickHouse 列名作为维度来源
        List<String> metricColumns = new ArrayList<>();
        List<String> dimColumns = new ArrayList<>();
        for (String col : availableColumns) {
            String lower = col.toLowerCase();
            // 数值列作为指标
            if (lower.contains("revenue") || lower.contains("rate") || lower.contains("avg")
                    || lower.contains("count") || lower.contains("sum") || lower.contains("margin")
                    || lower.contains("ticket") || lower.contains("turnover") || lower.contains("ratio")
                    || lower.contains("occupancy") || lower.contains("cost") || lower.contains("price")) {
                metricColumns.add(col);
            }
            // 维度类列
            if (lower.contains("date") || lower.contains("store") || lower.contains("id")
                    || lower.contains("name") || lower.contains("type") || lower.contains("category")) {
                dimColumns.add(col);
            }
        }

        // 至少选一个指标列
        if (!metricColumns.isEmpty()) {
            String metric = metricColumns.get(state.getQueryCount() % metricColumns.size());
            dims.put("metric", Collections.singletonList(metric));
        }
        // 至少选一个维度列
        if (!dimColumns.isEmpty()) {
            String dim = dimColumns.get(state.getQueryCount() % dimColumns.size());
            dims.put("dimension", Collections.singletonList(dim));
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
        String stmt = vo.getStatement();
        if (stmt != null && stmt.length() > 1000) {
            stmt = stmt.substring(0, 1000);
        }
        entity.setStatement(stmt);
        if (StringUtils.isBlank(entity.getType())) {
            entity.setType("trend");
        }
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
            suggestions.add("还有 " + state.getPendingHypotheses().size() + " 条结论待核实，可继续分析以确认或排除");
        }
        if (state.getActiveHypotheses() != null && !state.getActiveHypotheses().isEmpty()) {
            HypothesisVO top = state.getActiveHypotheses().get(0);
            suggestions.add("当前重点关注: " + top.getStatement());
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
