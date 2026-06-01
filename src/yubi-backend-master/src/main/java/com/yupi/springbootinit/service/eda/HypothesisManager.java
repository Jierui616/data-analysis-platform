package com.yupi.springbootinit.service.eda;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.yupi.springbootinit.manager.AiManager;
import com.yupi.springbootinit.model.vo.autoeda.HypothesisVO;
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
    private HypothesisVerifyExecutor hypothesisVerifyExecutor;

    @Resource
    private ExplorationBlackboard blackboard;

    private final Gson gson = new Gson();

    @Value("${autoeda.hypothesis.accept-threshold:0.75}")
    private double thetaAccept;

    @Value("${autoeda.hypothesis.reject-threshold:0.25}")
    private double thetaReject;

    @Value("${autoeda.hypothesis.max-stale-rounds:3}")
    private int maxStaleRounds;

    @Value("${autoeda.hypothesis.verify-enabled:true}")
    private boolean verifyEnabled;

    @Value("${autoeda.hypothesis.use-llm-generation:true}")
    private boolean useLlmGeneration;

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

    public List<HypothesisVO> generateHypotheses(String queryResultSummary,
                                                  List<HypothesisVO> existingHypotheses,
                                                  List<String> lineageReachableFields,
                                                  String rootMetric) {
        if (!useLlmGeneration) {
            return Collections.emptyList();
        }
        String prompt = String.format(HYPOTHESIS_GEN_PROMPT,
                limit(queryResultSummary, 3000),
                summarizeExisting(existingHypotheses),
                String.join(", ", lineageReachableFields));

        try {
            String response = aiManager.doChat(0, prompt);
            List<HypothesisVO> parsed = parseHypotheses(response);
            if (!parsed.isEmpty()) {
                return filterByLineageReachable(parsed, lineageReachableFields);
            }
        } catch (Exception e) {
            log.warn("Hypothesis generation failed: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * Bayesian 更新：优先使用 ClickHouse 验证查询的一致性，辅以血缘路径与证据链。
     */
    public double evaluateEvidence(HypothesisVO hypothesis, ExplorationContext ctx) {
        HypothesisVerifyExecutor.VerificationResult vr =
                hypothesisVerifyExecutor.verify(hypothesis, ctx);
        return evaluateEvidence(hypothesis, ctx, vr);
    }

    public double evaluateEvidence(HypothesisVO hypothesis, ExplorationContext ctx,
                                   HypothesisVerifyExecutor.VerificationResult vr) {
        double base = 0.25 + 0.7 * (vr != null ? vr.getConsistency() : 0.45);

        String lineagePath = hypothesis.getLineagePath();
        if (StringUtils.isNotBlank(lineagePath)) {
            String[] nodes = lineagePath.split("→|->");
            int matched = 0;
            for (String node : nodes) {
                if (node.trim().length() > 1) {
                    matched++;
                }
            }
            base += Math.min(0.15, matched * 0.04);
        }

        if (hypothesis.getEvidenceChain() != null && !hypothesis.getEvidenceChain().isEmpty()) {
            base += Math.min(0.1, hypothesis.getEvidenceChain().size() * 0.03);
        }

        return Math.min(0.98, base);
    }

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
        trend.setConfidencePosterior(0.3);
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
            segment.setConfidencePosterior(0.3);
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
            anomaly.setConfidencePosterior(0.3);
            anomaly.setStatus("PENDING");
            anomaly.setSourceQueryIds(Collections.singletonList(queryId));
            result.add(anomaly);
        }

        return result;
    }

    public double bayesianUpdate(double prior, double evidenceConsistency, int evidenceSteps) {
        double alphaPrior = prior * 10 + 1;
        double betaPrior = (1 - prior) * 10 + 1;
        int successes = (int) Math.round(evidenceConsistency * evidenceSteps);
        int failures = Math.max(0, evidenceSteps - successes);
        double alphaPost = alphaPrior + successes;
        double betaPost = betaPrior + failures;
        return alphaPost / (alphaPost + betaPost);
    }

    public HypothesisCycleResult runCycle(List<HypothesisVO> pending,
                                           List<HypothesisVO> active,
                                           ExplorationContext ctx) {
        ExplorationBlackboard.SessionState state = blackboard.getOrCreate(ctx.getSessionId());

        List<HypothesisVO> verified = new ArrayList<>();
        List<HypothesisVO> discarded = new ArrayList<>();
        List<HypothesisVO> keepPending = new ArrayList<>();

        for (HypothesisVO h : new ArrayList<>(pending)) {
            double prior = h.getConfidencePosterior() != null
                    ? h.getConfidencePosterior()
                    : (h.getConfidencePrior() != null ? h.getConfidencePrior() : 0.3);

            HypothesisVerifyExecutor.VerificationResult vr;
            if (verifyEnabled) {
                vr = hypothesisVerifyExecutor.verify(h, ctx);
                hypothesisVerifyExecutor.appendEvidenceStep(h, "verify_" + h.getHypothesisId(), vr);
            } else {
                vr = new HypothesisVerifyExecutor.VerificationResult();
                vr.setConsistency(0.55);
            }

            double evidence = evaluateEvidence(h, ctx, vr);
            int steps = Math.max(1, state.getQueryCount());
            double posterior = bayesianUpdate(prior, evidence, steps);
            h.setConfidencePosterior(posterior);
            state.recordPosteriorProgress(h.getHypothesisId(), posterior);

            int stale = state.getStaleRounds(h.getHypothesisId());
            if (posterior >= thetaAccept) {
                verified.add(h);
            } else if (posterior < thetaReject && stale >= maxStaleRounds) {
                discarded.add(h);
            } else if (posterior < thetaReject && stale < maxStaleRounds) {
                keepPending.add(h);
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
        result.topActive = allActive.isEmpty()
                ? Collections.emptyList()
                : new ArrayList<>(allActive.subList(0, Math.min(5, allActive.size())));
        return result;
    }

    private List<HypothesisVO> filterByLineageReachable(List<HypothesisVO> parsed, List<String> reachable) {
        if (reachable == null || reachable.isEmpty()) {
            return parsed;
        }
        List<HypothesisVO> out = new ArrayList<>();
        for (HypothesisVO h : parsed) {
            if (StringUtils.isBlank(h.getLineagePath())) {
                out.add(h);
                continue;
            }
            String path = h.getLineagePath().toLowerCase();
            boolean ok = reachable.stream().anyMatch(r -> path.contains(r.toLowerCase()));
            if (ok) {
                out.add(h);
            }
        }
        return out.isEmpty() ? parsed : out;
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
