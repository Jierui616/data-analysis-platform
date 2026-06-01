package com.yupi.springbootinit.service.eda;

import com.yupi.springbootinit.model.vo.autoeda.HypothesisVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每轮生成候选查询池（策略 a/b/c：下钻、邻域扩展、未覆盖抽样）。
 */
@Component
@Slf4j
public class CandidateQueryGenerator {

    private static final String[] TIME_VARIANTS = {"recent", "monthly", "weekly"};

    @Resource
    private QueryGenerator queryGenerator;

    @Resource
    private HypothesisVerifyExecutor hypothesisVerifyExecutor;

    @Value("${autoeda.hypothesis.use-llm-generation:true}")
    private boolean useLlmGeneration;

    public List<QueryCandidate> generate(ExplorationContext ctx) {
        int poolSize = Math.max(1, ctx.getCandidatePoolSize());
        List<QueryCandidate> pool = new ArrayList<>();
        List<String> metricCols = extractMetricColumns(ctx.getAvailableColumns());
        List<String> dimCols = extractDimColumns(ctx.getAvailableColumns());
        if (metricCols.isEmpty()) {
            metricCols = Collections.singletonList(ctx.getRootMetricKey());
        }
        if (dimCols.isEmpty()) {
            dimCols = Collections.singletonList("storeId");
        }

        int round = ctx.getState().getQueryCount();

        // 策略 a: 沿活跃假设指标下钻
        pool.addAll(drillDownCandidates(ctx, metricCols, dimCols, poolSize / 3 + 1));

        // 策略 b: 异常/高后验假设邻域扩展
        pool.addAll(neighborCandidates(ctx, metricCols, dimCols, poolSize / 3 + 1));

        // 策略 c: 未覆盖区域随机抽样
        pool.addAll(sampleUnexplored(ctx, metricCols, dimCols, poolSize));

        // 策略 d: Top-K 假设验证查询入池（设计文档 4.4）
        pool.addAll(hypothesisVerifyExecutor.buildVerificationCandidates(ctx));

        // 去重并截断
        List<QueryCandidate> deduped = deduplicate(pool);
        if (deduped.size() > poolSize) {
            Collections.shuffle(deduped, ctx.getRandom());
            deduped = new ArrayList<>(deduped.subList(0, poolSize));
        }

        for (int i = 0; i < deduped.size(); i++) {
            QueryCandidate c = deduped.get(i);
            if (StringUtils.isBlank(c.getSql())) {
                fillSql(c, ctx, round + i);
            }
            if (c.getLineageNodeKey() == null) {
                c.setLineageNodeKey(resolveMetricKey(c.getDimensions(), ctx.getRootMetricKey()));
            }
            // estimatedRows 由 ExplorationOrchestrator.preparePool 在 EXPLAIN 后写入
        }

        return deduped;
    }

    private List<QueryCandidate> drillDownCandidates(ExplorationContext ctx,
                                                     List<String> metricCols,
                                                     List<String> dimCols,
                                                     int limit) {
        List<QueryCandidate> list = new ArrayList<>();
        List<HypothesisVO> active = ctx.getState().getActiveHypotheses();
        if (active == null || active.isEmpty()) {
            active = ctx.getState().getPendingHypotheses();
        }
        int added = 0;
        for (HypothesisVO h : active) {
            if (added >= limit) break;
            String metric = guessMetricFromHypothesis(h, metricCols, ctx.getRootMetricKey());
            String dim = dimCols.get(added % dimCols.size());
            list.add(buildCandidate(metric, dim, TIME_VARIANTS[added % TIME_VARIANTS.length]));
            added++;
        }
        return list;
    }

    private List<QueryCandidate> neighborCandidates(ExplorationContext ctx,
                                                      List<String> metricCols,
                                                      List<String> dimCols,
                                                      int limit) {
        List<QueryCandidate> list = new ArrayList<>();
        List<HypothesisVO> verified = ctx.getState().getVerifiedHypotheses();
        if (verified == null || verified.isEmpty()) {
            return list;
        }
        int added = 0;
        for (HypothesisVO h : verified) {
            if (added >= limit) break;
            if (!"anomaly".equalsIgnoreCase(h.getType()) && !"segment_diff".equalsIgnoreCase(h.getType())) {
                continue;
            }
            String metric = guessMetricFromHypothesis(h, metricCols, ctx.getRootMetricKey());
            String dim = dimCols.get((added + 1) % dimCols.size());
            list.add(buildCandidate(metric, dim, "monthly"));
            added++;
        }
        return list;
    }

    private List<QueryCandidate> sampleUnexplored(ExplorationContext ctx,
                                                List<String> metricCols,
                                                List<String> dimCols,
                                                int poolSize) {
        List<QueryCandidate> list = new ArrayList<>();
        int round = ctx.getState().getQueryCount();
        for (int i = 0; i < poolSize; i++) {
            int idx = (round + i) % Math.max(metricCols.size(), 1);
            int dimIdx = (round + i * 2) % Math.max(dimCols.size(), 1);
            String metric = metricCols.get(idx);
            String dim = dimCols.get(dimIdx);
            String time = TIME_VARIANTS[(round + i) % TIME_VARIANTS.length];
            if (!ctx.getState().isExplored("metric", metric)
                    || !ctx.getState().isExplored("dimension", dim)
                    || !ctx.getState().isExplored("time", time)) {
                list.add(buildCandidate(metric, dim, time));
            }
        }
        if (list.isEmpty()) {
            list.add(buildCandidate(
                    metricCols.get(round % metricCols.size()),
                    dimCols.get(round % dimCols.size()),
                    TIME_VARIANTS[round % TIME_VARIANTS.length]));
        }
        return list;
    }

    private QueryCandidate buildCandidate(String metric, String dim, String time) {
        QueryCandidate c = new QueryCandidate();
        Map<String, List<String>> dims = new LinkedHashMap<>();
        dims.put("metric", Collections.singletonList(metric));
        dims.put("dimension", Collections.singletonList(dim));
        dims.put("time", Collections.singletonList(time));
        c.setDimensions(dims);
        c.setLineageNodeKey(metric);
        return c;
    }

    private void fillSql(QueryCandidate c, ExplorationContext ctx, int salt) {
        ExplorationMode mode = ctx.getMode();
        String lineageDesc = ctx.getLineageReachable() != null
                ? String.join("→", ctx.getLineageReachable())
                : "";

        if (mode.forceLlmSql()) {
            c.setSql(queryGenerator.generateSql(
                    ctx.getTableName(),
                    ctx.getAvailableColumns(),
                    ctx.getExplorationIntent(),
                    c.getDimensions(),
                    lineageDesc));
            return;
        }

        c.setSql(queryGenerator.generateTemplateSql(
                ctx.getTableName(), c.getDimensions(), ctx.getAvailableColumns()));

        if (StringUtils.isBlank(c.getSql()) && useLlmGeneration) {
            c.setSql(queryGenerator.generateSql(
                    ctx.getTableName(),
                    ctx.getAvailableColumns(),
                    ctx.getExplorationIntent(),
                    c.getDimensions(),
                    lineageDesc));
        }
    }

    private List<QueryCandidate> deduplicate(List<QueryCandidate> pool) {
        List<QueryCandidate> out = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (QueryCandidate c : pool) {
            String key = c.getDimensions().toString() + "|" + StringUtils.defaultString(c.getSql());
            if (!keys.contains(key)) {
                keys.add(key);
                out.add(c);
            }
        }
        return out;
    }

    private String resolveMetricKey(Map<String, List<String>> dimensions, String fallback) {
        List<String> metrics = dimensions.get("metric");
        if (metrics != null && !metrics.isEmpty()) {
            return metrics.get(0);
        }
        return fallback;
    }

    private String guessMetricFromHypothesis(HypothesisVO h, List<String> metricCols, String root) {
        if (h.getLineagePath() != null) {
            for (String col : metricCols) {
                if (h.getLineagePath().toLowerCase().contains(col.toLowerCase())) {
                    return col;
                }
            }
        }
        if (h.getStatement() != null) {
            for (String col : metricCols) {
                if (h.getStatement().toLowerCase().contains(col.toLowerCase())) {
                    return col;
                }
            }
        }
        return root;
    }

    private List<String> extractMetricColumns(List<String> columns) {
        List<String> metrics = new ArrayList<>();
        for (String col : columns) {
            String lower = col.toLowerCase();
            if (lower.contains("revenue") || lower.contains("rate") || lower.contains("avg")
                    || lower.contains("ticket") || lower.contains("turnover")
                    || lower.contains("ratio") || lower.contains("occupancy")
                    || lower.contains("cost") || lower.contains("cancel")
                    || lower.contains("review")) {
                metrics.add(col);
            }
        }
        return metrics;
    }

    private List<String> extractDimColumns(List<String> columns) {
        List<String> dims = new ArrayList<>();
        for (String col : columns) {
            if ("storeId".equalsIgnoreCase(col)) {
                dims.add(col);
            }
        }
        if (dims.isEmpty()) {
            for (String col : columns) {
                String lower = col.toLowerCase();
                if (lower.contains("store") && !lower.contains("mysql")) {
                    dims.add(col);
                }
            }
        }
        return dims.isEmpty() ? Collections.singletonList("storeId") : dims;
    }
}
