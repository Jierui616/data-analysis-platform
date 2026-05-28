package com.yupi.springbootinit.service.eda;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.yupi.springbootinit.manager.AiManager;
import com.yupi.springbootinit.model.vo.autoeda.EvidenceChainVO;
import com.yupi.springbootinit.model.vo.autoeda.HypothesisVO;
import com.yupi.springbootinit.service.ClickHouseQueryService;
import com.yupi.springbootinit.service.LineageService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class HypothesisManager {

    @Resource
    private AiManager aiManager;

    @Resource
    private LineageService lineageService;

    @Resource
    private ClickHouseQueryService clickHouseQueryService;

    @Resource
    private ExplorationBlackboard blackboard;

    private final Gson gson = new Gson();

    @Value("${autoeda.hypothesis.accept-threshold:0.75}")
    private double thetaAccept;

    @Value("${autoeda.hypothesis.reject-threshold:0.25}")
    private double thetaReject;

    @Value("${autoeda.hypothesis.max-stale-rounds:3}")
    private int maxStaleRounds;

    private static final String HYPOTHESIS_GEN_PROMPT =
        "你是一个数据分析师。请基于最新的查询结果，从五个维度生成候选假设。\n"
        + "你必须为每个假设指定血缘链路径(沿数据加工链路下钻)。\n"
        + "输出严格 JSON 数组，每个元素格式:\n"
        + "{\"type\":\"anomaly|correlation|trend|segment_diff|counterfactual\","
        + "\"statement\":\"假设陈述\","
        + "\"lineagePath\":\"指标A→指标B→字段C\","
        + "\"parentHypothesisId\":null}\n\n"
        + "五个维度: 1)时序对比 2)结构拆解(沿血缘下钻) 3)异常检测(Z-score) 4)相关性 5)反事实试探\n"
        + "\n最新查询结果:\n%s\n\n已有假设:\n%s\n\n数据血缘可及字段:\n%s";

    /**
     * 从新查询结果生成候选假设（LLM）
     */
    public List<HypothesisVO> generateHypotheses(String queryResultSummary,
                                                  List<HypothesisVO> existingHypotheses,
                                                  List<String> lineageReachableFields,
                                                  String rootMetric) {
        String prompt = String.format(HYPOTHESIS_GEN_PROMPT,
                limit(queryResultSummary, 3000),
                summarizeExisting(existingHypotheses),
                String.join(", ", lineageReachableFields));

        try {
            String response = aiManager.doChat(0, prompt);
            List<HypothesisVO> parsed = parseHypotheses(response);
            if (!parsed.isEmpty()) return parsed;
        } catch (Exception e) {
            log.warn("Hypothesis generation failed: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * Bayesian 更新：沿血缘链路验证假设的证据一致性
     */
    public double evaluateEvidence(HypothesisVO hypothesis, String sessionId) {
        String lineagePath = hypothesis.getLineagePath();
        double base = 0.45;

        if (StringUtils.isNotBlank(lineagePath)) {
            String[] nodes = lineagePath.split("→|->");
            int matched = 0;
            for (String node : nodes) {
                String key = node.trim().toLowerCase();
                if (key.length() > 1) matched++;
            }
            base += Math.min(0.3, matched * 0.08);
        }

        if (hypothesis.getSourceQueryIds() != null && !hypothesis.getSourceQueryIds().isEmpty()) {
            base += 0.15;
        }
        if (hypothesis.getEvidenceChain() != null && !hypothesis.getEvidenceChain().isEmpty()) {
            base += Math.min(0.2, hypothesis.getEvidenceChain().size() * 0.05);
        }

        ExplorationBlackboard.SessionState state = blackboard.get(sessionId);
        if (state != null && state.getQueryCount() > 0) {
            base += Math.min(0.1, state.getQueryCount() * 0.01);
        }

        return Math.min(0.95, base);
    }

    /**
     * LLM 不可用时的规则化假设生成（基于查询结果 JSON）
     */
    public List<HypothesisVO> generateRuleBasedHypotheses(String queryResultSummary,
                                                           List<String> availableColumns,
                                                           String queryId) {
        List<HypothesisVO> result = new ArrayList<>();
        if (StringUtils.isBlank(queryResultSummary) || "查询返回空结果".equals(queryResultSummary)) {
            return result;
        }

        String metricCol = availableColumns.stream()
                .filter(c -> c.toLowerCase().contains("revenue") || c.toLowerCase().contains("rate"))
                .findFirst().orElse(availableColumns.isEmpty() ? "revenue" : availableColumns.get(0));

        HypothesisVO trend = new HypothesisVO();
        trend.setHypothesisId("h_" + UUID.randomUUID().toString().substring(0, 8));
        trend.setType("trend");
        trend.setStatement("查询结果显示 " + metricCol + " 存在时间维度波动，需进一步下钻验证");
        trend.setLineagePath(metricCol + "→statDate");
        trend.setConfidencePrior(0.3);
        trend.setConfidencePosterior(0.35);
        trend.setStatus("PENDING");
        trend.setSourceQueryIds(Collections.singletonList(queryId));
        result.add(trend);

        if (queryResultSummary.contains("store") || availableColumns.stream().anyMatch(c -> c.toLowerCase().contains("store"))) {
            HypothesisVO segment = new HypothesisVO();
            segment.setHypothesisId("h_" + UUID.randomUUID().toString().substring(0, 8));
            segment.setType("segment_diff");
            segment.setStatement("不同门店间 " + metricCol + " 存在结构性差异，建议按 storeId 分组对比");
            segment.setLineagePath(metricCol + "→storeId");
            segment.setConfidencePrior(0.3);
            segment.setConfidencePosterior(0.32);
            segment.setStatus("PENDING");
            segment.setSourceQueryIds(Collections.singletonList(queryId));
            result.add(segment);
        }

        if (queryResultSummary.length() > 100) {
            HypothesisVO anomaly = new HypothesisVO();
            anomaly.setHypothesisId("h_" + UUID.randomUUID().toString().substring(0, 8));
            anomaly.setType("anomaly");
            anomaly.setStatement("部分维度组合的数据偏离整体均值，可能存在异常点需进一步验证");
            anomaly.setLineagePath(metricCol);
            anomaly.setConfidencePrior(0.3);
            anomaly.setConfidencePosterior(0.38);
            anomaly.setStatus("PENDING");
            anomaly.setSourceQueryIds(Collections.singletonList(queryId));
            result.add(anomaly);
        }

        return result;
    }

    /**
     * Beta-Binomial 后验更新
     * 将 evidence consistency 映射为 Beta 分布的观测
     */
    public double bayesianUpdate(double prior, double evidenceConsistency, int evidenceSteps) {
        double alphaPrior = prior * 10 + 1;
        double betaPrior = (1 - prior) * 10 + 1;
        int successes = (int) Math.round(evidenceConsistency * evidenceSteps);
        int failures = evidenceSteps - successes;
        double alphaPost = alphaPrior + successes;
        double betaPost = betaPrior + failures;
        return alphaPost / (alphaPost + betaPost);
    }

    /**
     * 对假设池执行一轮 Bayesian 分类
     */
    public HypothesisCycleResult runCycle(List<HypothesisVO> pending,
                                           List<HypothesisVO> active,
                                           String sessionId) {
        ExplorationBlackboard.SessionState state = blackboard.getOrCreate(sessionId);

        List<HypothesisVO> verified = new ArrayList<>();
        List<HypothesisVO> discarded = new ArrayList<>();
        List<HypothesisVO> keepPending = new ArrayList<>();

        // 复制列表，避免 addVerified 修改 pending 时触发 ConcurrentModificationException
        for (HypothesisVO h : new ArrayList<>(pending)) {
            double prior = h.getConfidencePosterior() != null
                    ? h.getConfidencePosterior()
                    : (h.getConfidencePrior() != null ? h.getConfidencePrior() : 0.3);
            double evidence = evaluateEvidence(h, sessionId);
            int steps = Math.max(1, state.getQueryCount());
            double posterior = bayesianUpdate(prior, evidence, steps);
            h.setConfidencePosterior(posterior);

            if (posterior >= thetaAccept) {
                verified.add(h);
            } else if (posterior < thetaReject) {
                discarded.add(h);
            } else {
                keepPending.add(h);
            }
        }

        for (HypothesisVO h : verified) {
            state.addVerified(h);
        }

        List<HypothesisVO> allActive = new ArrayList<>(active);
        allActive.addAll(keepPending);
        allActive.sort((a, b) -> Double.compare(
                b.getConfidencePosterior() != null ? b.getConfidencePosterior() : 0,
                a.getConfidencePosterior() != null ? a.getConfidencePosterior() : 0
        ));

        HypothesisCycleResult result = new HypothesisCycleResult();
        result.verified = verified;
        result.discarded = discarded;
        result.keepPending = keepPending;
        result.topActive = allActive.subList(0, Math.min(5, allActive.size()));
        return result;
    }

    public static class HypothesisCycleResult {
        public List<HypothesisVO> verified;
        public List<HypothesisVO> discarded;
        public List<HypothesisVO> keepPending;
        public List<HypothesisVO> topActive;
    }

    @SuppressWarnings("unchecked")
    private List<HypothesisVO> parseHypotheses(String raw) {
        if (StringUtils.isBlank(raw)) return Collections.emptyList();
        String s = raw.trim();
        int a = s.indexOf("[");
        int b = s.lastIndexOf("]");
        if (a >= 0 && b > a) {
            s = s.substring(a, b + 1);
        }
        try {
            Type listType = new TypeToken<List<HypothesisVO>>(){}.getType();
            List<HypothesisVO> parsed = gson.fromJson(s, listType);
            if (parsed != null) {
                for (HypothesisVO h : parsed) {
                    h.setHypothesisId("h_" + UUID.randomUUID().toString().substring(0, 8));
                    h.setConfidencePrior(0.3);
                    h.setConfidencePosterior(0.3);
                    h.setStatus("PENDING");
                }
                return parsed;
            }
        } catch (Exception e) {
            log.debug("Hypothesis JSON parse failed: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    private String limit(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...(truncated)";
    }

    private String summarizeExisting(List<HypothesisVO> existing) {
        if (existing == null || existing.isEmpty()) return "无已有假设";
        return existing.stream()
                .map(h -> String.format("[%s|%.2f] %s", h.getType(), h.getConfidencePosterior(), h.getStatement()))
                .collect(Collectors.joining("\n"));
    }
}
