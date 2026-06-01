package com.yupi.springbootinit.service.eda;

import com.yupi.springbootinit.model.vo.autoeda.EvidenceChainVO;
import com.yupi.springbootinit.model.vo.autoeda.HypothesisVO;
import com.yupi.springbootinit.service.ClickHouseQueryService;
import lombok.Data;
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
 * 沿假设生成验证 SQL，在 ClickHouse 上执行并计算证据一致性（设计文档 4.4）。
 */
@Component
@Slf4j
public class HypothesisVerifyExecutor {

    @Resource
    private ClickHouseQueryService clickHouseQueryService;

    @Value("${autoeda.hypothesis.verify-top-k:3}")
    private int verifyTopK;

    @Value("${autoeda.hypothesis.verify-enabled:true}")
    private boolean verifyEnabled;

    @Data
    public static class VerificationResult {
        private double consistency;
        private String sql;
        private String resultSummary;
        private int rowCount;
    }

    /**
     * 为 Top-K 活跃假设生成验证候选查询（并入下一轮候选池）。
     */
    public List<QueryCandidate> buildVerificationCandidates(ExplorationContext ctx) {
        List<QueryCandidate> out = new ArrayList<>();
        if (!verifyEnabled || ctx == null || ctx.getState() == null) {
            return out;
        }
        List<HypothesisVO> active = ctx.getState().getActiveHypotheses();
        if (active == null || active.isEmpty()) {
            active = ctx.getState().getPendingHypotheses();
        }
        int limit = Math.min(verifyTopK, active != null ? active.size() : 0);
        for (int i = 0; i < limit; i++) {
            HypothesisVO h = active.get(i);
            String sql = buildVerificationSql(h, ctx);
            if (StringUtils.isBlank(sql)) {
                continue;
            }
            QueryCandidate c = new QueryCandidate();
            c.setSql(sql);
            c.setLineageNodeKey(resolveMetricFromHypothesis(h, ctx.getRootMetricKey(), ctx.getAvailableColumns()));
            Map<String, List<String>> dims = new LinkedHashMap<>();
            dims.put("metric", Collections.singletonList(c.getLineageNodeKey()));
            dims.put("purpose", Collections.singletonList("verify_" + h.getHypothesisId()));
            c.setDimensions(dims);
            out.add(c);
        }
        return out;
    }

    public VerificationResult verify(HypothesisVO hypothesis, ExplorationContext ctx) {
        VerificationResult result = new VerificationResult();
        result.setConsistency(0.45);
        if (!verifyEnabled || hypothesis == null || ctx == null) {
            return result;
        }
        String sql = buildVerificationSql(hypothesis, ctx);
        result.setSql(sql);
        if (StringUtils.isBlank(sql)) {
            return result;
        }
        try {
            ClickHouseQueryService.QueryResult qr = clickHouseQueryService.executeWithStats(sql);
            result.setRowCount(qr.getRowCount());
            result.setResultSummary(summarizeRows(qr.getRows(), 1500));
            result.setConsistency(scoreConsistency(hypothesis, qr.getRows()));
        } catch (Exception e) {
            log.debug("Hypothesis verify query failed: {}", e.getMessage());
            result.setConsistency(0.35);
        }
        return result;
    }

    String buildVerificationSql(HypothesisVO h, ExplorationContext ctx) {
        String table = ctx.getTableName();
        String metric = resolveMetricFromHypothesis(h, ctx.getRootMetricKey(), ctx.getAvailableColumns());
        if (!isSafeIdentifier(metric)) {
            metric = "revenue";
        }
        String type = StringUtils.defaultString(h.getType(), "trend").toLowerCase();
        if ("segment_diff".equals(type) || "anomaly".equals(type)) {
            return "SELECT storeId, avg(" + metric + ") AS avg_metric, sum(" + metric + ") AS sum_metric "
                    + "FROM " + table + " GROUP BY storeId ORDER BY avg_metric DESC LIMIT 30";
        }
        if ("correlation".equals(type)) {
            String secondary = pickSecondaryMetric(ctx.getAvailableColumns(), metric);
            return "SELECT avg(" + metric + ") AS avg_a, avg(" + secondary + ") AS avg_b "
                    + "FROM " + table;
        }
        return "SELECT formatDateTime(statDate, '%Y-%m') AS month, avg(" + metric + ") AS avg_metric "
                + "FROM " + table + " GROUP BY month ORDER BY month LIMIT 24";
    }

    private double scoreConsistency(HypothesisVO h, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0.25;
        }
        String type = StringUtils.defaultString(h.getType(), "").toLowerCase();
        List<Double> values = extractNumericValues(rows);
        if (values.size() < 2) {
            return 0.5;
        }
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0);
        double std = Math.sqrt(variance);
        if (std < 1e-9) {
            return 0.4;
        }
        double maxZ = values.stream().mapToDouble(v -> Math.abs(v - mean) / std).max().orElse(0);
        if ("anomaly".equals(type) || "segment_diff".equals(type)) {
            return Math.min(0.95, 0.45 + maxZ * 0.15);
        }
        if ("trend".equals(type)) {
            double first = values.get(0);
            double last = values.get(values.size() - 1);
            double relChange = Math.abs(last - first) / (Math.abs(first) + 1e-6);
            return Math.min(0.92, 0.5 + relChange * 0.3);
        }
        return Math.min(0.88, 0.45 + maxZ * 0.1);
    }

    private List<Double> extractNumericValues(List<Map<String, Object>> rows) {
        List<Double> values = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            for (Object v : row.values()) {
                if (v instanceof Number) {
                    values.add(((Number) v).doubleValue());
                    break;
                }
            }
        }
        return values;
    }

    private String resolveMetricFromHypothesis(HypothesisVO h, String root, List<String> columns) {
        if (h.getStatement() != null) {
            for (String col : columns) {
                if (!isMetricColumn(col)) {
                    continue;
                }
                if (h.getStatement().toLowerCase().contains(col.toLowerCase())) {
                    return col;
                }
            }
        }
        if (h.getLineagePath() != null) {
            String[] nodes = h.getLineagePath().split("→|->");
            for (String node : nodes) {
                String trimmed = node.trim();
                for (String col : columns) {
                    if (col.equalsIgnoreCase(trimmed) && isMetricColumn(col)) {
                        return col;
                    }
                }
            }
            for (String col : columns) {
                if (!isMetricColumn(col)) {
                    continue;
                }
                if (h.getLineagePath().toLowerCase().contains(col.toLowerCase())) {
                    return col;
                }
            }
        }
        if (isMetricColumn(root)) {
            return root;
        }
        for (String col : columns) {
            if (isMetricColumn(col)) {
                return col;
            }
        }
        return "revenue";
    }

    private boolean isMetricColumn(String col) {
        if (col == null || col.isEmpty()) {
            return false;
        }
        String lower = col.toLowerCase();
        if (lower.contains("date") || lower.equals("mysqlid") || lower.equals("storeid")) {
            return false;
        }
        return lower.contains("revenue") || lower.contains("rate") || lower.contains("avg")
                || lower.contains("ticket") || lower.contains("turnover") || lower.contains("ratio")
                || lower.contains("occupancy") || lower.contains("cost") || lower.contains("cancel")
                || lower.contains("review") || lower.contains("prep") || lower.contains("margin");
    }

    private String pickSecondaryMetric(List<String> columns, String primary) {
        for (String col : columns) {
            if (!col.equalsIgnoreCase(primary) && col.toLowerCase().matches(".*(rate|ticket|revenue|count).*")) {
                return col;
            }
        }
        return "avgTicket";
    }

    private boolean isSafeIdentifier(String name) {
        return name != null && name.matches("[a-zA-Z_][a-zA-Z0-9_]*");
    }

    private String summarizeRows(List<Map<String, Object>> rows, int maxLen) {
        if (rows == null || rows.isEmpty()) {
            return "验证查询无结果";
        }
        int n = Math.min(3, rows.size());
        String s = rows.subList(0, n).toString();
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    public void appendEvidenceStep(HypothesisVO h, String queryId, VerificationResult vr) {
        if (h == null || vr == null) {
            return;
        }
        EvidenceChainVO step = new EvidenceChainVO();
        step.setStepOrder(h.getEvidenceChain() != null ? h.getEvidenceChain().size() + 1 : 1);
        step.setQueryId(queryId);
        step.setResultSummary(StringUtils.abbreviate(vr.getResultSummary(), 200));
        step.setLineagePath(h.getLineagePath());
        if (h.getEvidenceChain() == null) {
            h.setEvidenceChain(new ArrayList<>());
        }
        h.getEvidenceChain().add(step);
        if (h.getSourceQueryIds() == null) {
            h.setSourceQueryIds(new ArrayList<>());
        }
        if (queryId != null && !h.getSourceQueryIds().contains(queryId)) {
            h.getSourceQueryIds().add(queryId);
        }
    }
}
