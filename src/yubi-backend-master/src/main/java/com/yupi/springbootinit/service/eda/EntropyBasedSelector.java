package com.yupi.springbootinit.service.eda;

import com.yupi.springbootinit.model.vo.autoeda.HypothesisVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 期望假设信息增益（EHIG）查询选择器。
 *
 * 用信息论统一探索与验证：选择能最大化减少假设不确定性的查询。
 * 使用 Beta 分布方差作为不确定性度量（避免 digamma/log-beta 等特殊函数依赖）。
 *
 * EHIG(q) = Σ_{h ∈ H_active} relevance(q, h) × ΔVar(q, h) × confidence(h)
 *
 * ΔVar = current_variance − expected_posterior_variance
 * expected_posterior_variance = P(support)×Var(α+k,β) + (1−P(support))×Var(α,β+k)
 */
@Component
@Slf4j
public class EntropyBasedSelector {

    /** 预期证据步数（每次查询对假设后验的影响强度） */
    @Value("${autoeda.ehig.evidence-steps:3}")
    private int evidenceSteps;

    /** 相关性阈值：lineage 路径重叠度低于此值时忽略 */
    @Value("${autoeda.ehig.relevance-threshold:0.15}")
    private double relevanceThreshold;

    /** 置信度加权最小阈值：后验过低的假设对 EHIG 贡献可忽略 */
    @Value("${autoeda.ehig.confidence-floor:0.25}")
    private double confidenceFloor;

    // ══════════════════════════════════════════════════════════
    // 核心方法：从候选池中按 EHIG 选择最佳查询
    // ══════════════════════════════════════════════════════════

    /**
     * 从候选池中按 EHIG 选出最佳查询。
     *
     * @param pool     候选查询池（已填充 dimensions / lineageNodeKey）
     * @param ctx      探索上下文
     * @return EHIG 最高的候选查询，若无法计算则返回 null
     */
    public QueryCandidate selectBestByEhig(List<QueryCandidate> pool,
                                           ExplorationContext ctx) {
        if (pool == null || pool.isEmpty()) {
            return null;
        }

        List<HypothesisVO> active = getActiveHypotheses(ctx);
        if (active.isEmpty()) {
            // 无活跃假设时退化为 argmax IG
            log.debug("EHIG: no active hypotheses, fallback to max IG");
            return pool.stream()
                    .max(Comparator.comparingDouble(QueryCandidate::getIgScore))
                    .orElse(null);
        }

        QueryCandidate best = null;
        double bestEhig = Double.NEGATIVE_INFINITY;

        for (QueryCandidate c : pool) {
            double ehig = computeEhig(c, active);
            if (ehig > bestEhig) {
                bestEhig = ehig;
                best = c;
            }
        }

        if (best != null) {
            // 将 EHIG 写入候选的 IG 字段，方便日志/对比
            best.setIgScore(bestEhig);
            Map<String, Double> bd = new LinkedHashMap<>();
            bd.put("ehig", bestEhig);
            bd.put("strategy", 2.0);  // 2 = EHIG
            best.setIgBreakdown(bd);
        }

        return best;
    }

    /**
     * 计算候选查询 q 的 EHIG 分数。
     */
    public double computeEhig(QueryCandidate candidate, List<HypothesisVO> active) {
        double total = 0.0;
        int contributed = 0;

        for (HypothesisVO h : active) {
            double relevance = computeRelevance(candidate, h);
            if (relevance < relevanceThreshold) {
                continue;
            }

            double currentVar = betaVariance(h);
            double expectedPostVar = expectedPosteriorVariance(h);
            double deltaVar = currentVar - expectedPostVar;

            double confidence = h.getConfidencePosterior() != null
                    ? h.getConfidencePosterior() : 0.3;
            if (confidence < confidenceFloor) {
                confidence = 0;
            }

            total += relevance * deltaVar * confidence;
            contributed++;
        }

        if (contributed == 0) {
            return 0.0;
        }
        return Math.max(0.0, total);
    }

    // ══════════════════════════════════════════════════════════
    // Beta 方差计算
    // ══════════════════════════════════════════════════════════

    /**
     * Beta(α, β) 方差 = αβ / ((α+β)² × (α+β+1))
     */
    static double betaVariance(double alpha, double beta) {
        double sum = alpha + beta;
        if (sum < 1e-9) return 0.25; // 最大方差（均匀先验）
        return (alpha * beta) / (sum * sum * (sum + 1.0));
    }

    /**
     * 从假设的后验概率反推 Beta 参数。
     * 假设 Bayesian 更新中使用 prior*10+1 作为伪计数基数。
     */
    private double[] getBetaParams(HypothesisVO h) {
        double prior = h.getConfidencePrior() != null ? h.getConfidencePrior() : 0.3;
        double posterior = h.getConfidencePosterior() != null ? h.getConfidencePosterior() : prior;

        // 从 HypothesisManager.bayesianUpdate() 推导:
        // α_prior = prior*10 + 1, β_prior = (1-prior)*10 + 1
        // 后验 = α_post / (α_post + β_post)
        // 我们近似还原 α_post, β_post
        double totalPseudo = 10.0 + 2.0 + evidenceSteps; // prior pseudocounts + evidence steps
        double alpha = posterior * totalPseudo + 1.0;
        double beta = (1.0 - posterior) * totalPseudo + 1.0;

        return new double[]{Math.max(1.01, alpha), Math.max(1.01, beta)};
    }

    private double betaVariance(HypothesisVO h) {
        double[] ab = getBetaParams(h);
        return betaVariance(ab[0], ab[1]);
    }

    // ══════════════════════════════════════════════════════════
    // 期望后验方差
    // ══════════════════════════════════════════════════════════

    /**
     * E[Var | q] = P(support) × Var(α+k, β) + P(contradict) × Var(α, β+k)
     * P(support) ≈ current posterior ≈ α/(α+β)
     */
    private double expectedPosteriorVariance(HypothesisVO h) {
        double[] ab = getBetaParams(h);
        double alpha = ab[0], beta_ = ab[1];
        double pSupport = alpha / (alpha + beta_);

        double varSupport = betaVariance(alpha + evidenceSteps, beta_);
        double varContradict = betaVariance(alpha, beta_ + evidenceSteps);

        return pSupport * varSupport + (1.0 - pSupport) * varContradict;
    }

    // ══════════════════════════════════════════════════════════
    // 查询-假设相关性
    // ══════════════════════════════════════════════════════════

    /**
     * 计算查询 q 与假设 h 的相关性。
     * 基于维度重叠 + 血缘路径重叠 + 指标名匹配。
     *
     * @return [0, 1]，越高越相关
     */
    double computeRelevance(QueryCandidate q, HypothesisVO h) {
        double score = 0.0;
        int factors = 0;

        // 因子 1: 血缘路径重叠
        String qLineage = q.getLineageNodeKey();
        String hLineage = h.getLineagePath();
        if (StringUtils.isNotBlank(qLineage) && StringUtils.isNotBlank(hLineage)) {
            double overlap = lineageOverlap(qLineage, hLineage);
            score += overlap;
            factors++;
        }

        // 因子 2: 查询维度中的 metric 是否出现在假设陈述中
        if (q.getDimensions() != null && StringUtils.isNotBlank(h.getStatement())) {
            List<String> metrics = q.getDimensions().get("metric");
            if (metrics != null) {
                String stmt = h.getStatement().toLowerCase();
                for (String m : metrics) {
                    if (m != null && stmt.contains(m.toLowerCase())) {
                        score += 0.5;
                        break;
                    }
                }
                factors++;
            }
        }

        // 因子 3: 假设类型与查询意图的天然关联
        // anomaly 假设对 surprise 高的查询更相关
        if ("anomaly".equalsIgnoreCase(h.getType())) {
            // 已在 lineage/dim 覆盖，不做额外加权
        }

        return factors == 0 ? 0.1 : Math.min(1.0, score / factors);
    }

    /**
     * 计算两个血缘路径的重叠度。
     * 路径格式: "A→B→C" 或 "A->B->C"
     */
    private double lineageOverlap(String path1, String path2) {
        Set<String> nodes1 = splitPath(path1);
        Set<String> nodes2 = splitPath(path2);
        if (nodes1.isEmpty() || nodes2.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(nodes1);
        intersection.retainAll(nodes2);

        return (double) intersection.size() / Math.max(nodes1.size(), nodes2.size());
    }

    private Set<String> splitPath(String path) {
        Set<String> nodes = new HashSet<>();
        for (String node : path.split("→|->")) {
            String trimmed = node.trim().toLowerCase();
            if (trimmed.length() > 1) {
                nodes.add(trimmed);
            }
        }
        return nodes;
    }

    // ══════════════════════════════════════════════════════════
    // 工具方法
    // ══════════════════════════════════════════════════════════

    private List<HypothesisVO> getActiveHypotheses(ExplorationContext ctx) {
        List<HypothesisVO> active = ctx.getState().getActiveHypotheses();
        if (active != null && !active.isEmpty()) {
            return active;
        }
        List<HypothesisVO> pending = ctx.getState().getPendingHypotheses();
        if (pending != null && !pending.isEmpty()) {
            return pending;
        }
        List<HypothesisVO> verified = ctx.getState().getVerifiedHypotheses();
        return verified != null ? verified : Collections.emptyList();
    }
}
