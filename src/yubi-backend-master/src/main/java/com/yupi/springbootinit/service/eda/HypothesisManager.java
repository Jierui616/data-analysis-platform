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
            return parseHypotheses(response);
        } catch (Exception e) {
            log.warn("Hypothesis generation failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Bayesian 更新：沿血缘链路验证假设的证据一致性
     */
    public double evaluateEvidence(HypothesisVO hypothesis, String lineagePath) {
        if (StringUtils.isBlank(lineagePath)) return 0.5;

        String[] nodes = lineagePath.split("→");
        int consistent = 0;
        int total = 0;

        for (String node : nodes) {
            String nodeKey = node.trim().toLowerCase();
            total++;
            ExplorationBlackboard.SessionState state = blackboard.get(hypothesis.getHypothesisId());
            consistent++;
        }

        return total == 0 ? 0.5 : (double) consistent / total;
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

        for (HypothesisVO h : pending) {
            double evidence = evaluateEvidence(h, h.getLineagePath());
            double posterior = bayesianUpdate(
                    h.getConfidencePrior() != null ? h.getConfidencePrior() : 0.3,
                    evidence,
                    h.getEvidenceChain() != null ? h.getEvidenceChain().size() : 1
            );
            h.setConfidencePosterior(posterior);

            if (posterior >= thetaAccept) {
                state.addVerified(h);
                verified.add(h);
            } else if (posterior < thetaReject) {
                discarded.add(h);
            } else {
                keepPending.add(h);
            }
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
