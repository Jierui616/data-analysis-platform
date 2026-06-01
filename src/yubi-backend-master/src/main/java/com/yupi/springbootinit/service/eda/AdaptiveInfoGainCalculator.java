package com.yupi.springbootinit.service.eda;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自适应阶段感知信息增益（AIG）。
 *
 * 对基础 InfoGain 的升级：权重不是固定的，而是根据探索阶段动态调整。
 * 由 PSC（后验停滞收敛）自动驱动阶段切换，实现元认知能力。
 *
 * 三阶段：
 *   BROAD_SWEEP  — 广度扫描：重覆盖、轻血缘
 *   DEEP_DRILL   — 深度下钻：重血缘、重异常
 *   VERIFICATION — 验证收敛：重血缘、重假设关联
 *
 * AIG(q, phase) = α(phase)*Coverage + β(phase)*Surprise + γ(phase)*Lineage - ε(phase)*Cost
 */
@Component
@Slf4j
public class AdaptiveInfoGainCalculator {

    @Resource
    private InfoGainCalculator baseIg;

    // ── 三阶段权重（可通过 application.yml 覆盖） ──

    /** BROAD_SWEEP: 广度扫描阶段 */
    @Value("${autoeda.aig.sweep.coverage:0.50}")
    private double sweepCoverage;

    @Value("${autoeda.aig.sweep.surprise:0.15}")
    private double sweepSurprise;

    @Value("${autoeda.aig.sweep.lineage:0.15}")
    private double sweepLineage;

    @Value("${autoeda.aig.sweep.cost:0.20}")
    private double sweepCost;

    /** DEEP_DRILL: 深度下钻阶段 */
    @Value("${autoeda.aig.drill.coverage:0.10}")
    private double drillCoverage;

    @Value("${autoeda.aig.drill.surprise:0.35}")
    private double drillSurprise;

    @Value("${autoeda.aig.drill.lineage:0.40}")
    private double drillLineage;

    @Value("${autoeda.aig.drill.cost:0.15}")
    private double drillCost;

    /** VERIFICATION: 验证收敛阶段 */
    @Value("${autoeda.aig.verify.coverage:0.05}")
    private double verifyCoverage;

    @Value("${autoeda.aig.verify.surprise:0.25}")
    private double verifySurprise;

    @Value("${autoeda.aig.verify.lineage:0.50}")
    private double verifyLineage;

    @Value("${autoeda.aig.verify.cost:0.20}")
    private double verifyCost;

    // ── 阶段切换阈值 ──

    @Value("${autoeda.aig.phase-sweep-min-verified:3}")
    private int phaseSweepMinVerified;

    @Value("${autoeda.aig.phase-sweep-budget-ratio:0.33}")
    private double phaseSweepBudgetRatio;

    @Value("${autoeda.aig.phase-verify-budget-ratio:0.80}")
    private double phaseVerifyBudgetRatio;

    // ══════════════════════════════════════════════════════════
    // 探索阶段枚举
    // ══════════════════════════════════════════════════════════

    public enum Phase {
        /** 广度扫描：快速覆盖数据空间，发现候选假设 */
        BROAD_SWEEP,
        /** 深度下钻：沿血缘路径深挖已验证假设的根因 */
        DEEP_DRILL,
        /** 验证收敛：对低置信度假设做最后一轮验证 */
        VERIFICATION
    }

    // ══════════════════════════════════════════════════════════
    // 阶段检测
    // ══════════════════════════════════════════════════════════

    /**
     * 根据黑板状态自动检测当前探索阶段。
     *
     * @param state      探索黑板状态
     * @param maxBudget  最大查询预算
     * @param convRounds 收敛所需连续轮次
     * @return 当前阶段
     */
    public Phase detectPhase(ExplorationBlackboard.SessionState state,
                             int maxBudget, int convRounds) {
        int verified = state.getVerifiedHypotheses().size();
        int queried = state.getQueryCount();

        // 阶段 1 → 2 条件：已验证假设达到阈值 或 查询量超过预算 1/3
        boolean enoughVerified = verified >= phaseSweepMinVerified;
        boolean pastEarlyBudget = queried >= maxBudget * phaseSweepBudgetRatio;

        // 阶段 2 → 3 条件：接近收敛 或 查询量超过预算 80%
        boolean nearConvergence = state.getConvergenceCounter() >= Math.max(1, convRounds - 1);
        boolean pastLateBudget = queried >= maxBudget * phaseVerifyBudgetRatio;

        if (nearConvergence || pastLateBudget) {
            return Phase.VERIFICATION;
        }
        if (enoughVerified || pastEarlyBudget) {
            return Phase.DEEP_DRILL;
        }
        return Phase.BROAD_SWEEP;
    }

    // ══════════════════════════════════════════════════════════
    // 核心计算
    // ══════════════════════════════════════════════════════════

    /**
     * 计算自适应信息增益分数。
     *
     * @param queryDimensions  查询涉及的维度组合
     * @param exploredRegions  已探索区域
     * @param surpriseContext  附近的异常幅度
     * @param lineageNodeKey   血缘节点 key
     * @param estimatedRows    预估扫描行数
     * @param rootMetricKey    根指标 key
     * @param phase            当前探索阶段
     * @return AIG 分数 (0~1)
     */
    public double calculate(Map<String, List<String>> queryDimensions,
                            Map<String, Set<String>> exploredRegions,
                            double surpriseContext,
                            String lineageNodeKey,
                            long estimatedRows,
                            String rootMetricKey,
                            Phase phase) {
        // 复用基础 IG 的子分计算（不重复造轮）
        double coverage = baseIg.calcCoverageNew(queryDimensions, exploredRegions);
        double surprise = baseIg.normalizeSurprise(surpriseContext);
        double lineage = baseIg.calcLineageDepth(lineageNodeKey, rootMetricKey);
        double cost = baseIg.normalizeCost(estimatedRows);

        double[] w = getWeights(phase);
        double aig = w[0] * coverage + w[1] * surprise + w[2] * lineage - w[3] * cost;

        return Math.max(0.0, Math.min(1.0, aig));
    }

    /**
     * 为兼容现有 Orchestrator 流程，计算后直接写回 QueryCandidate。
     */
    public void score(QueryCandidate candidate,
                      Map<String, Set<String>> exploredRegions,
                      double surpriseContext,
                      String rootMetricKey,
                      Phase phase) {
        long cost = candidate.getEstimatedRows() > 0 ? candidate.getEstimatedRows() : 100000L;
        String nodeKey = candidate.getLineageNodeKey() != null
                ? candidate.getLineageNodeKey() : rootMetricKey;

        double aig = calculate(candidate.getDimensions(), exploredRegions,
                surpriseContext, nodeKey, cost, rootMetricKey, phase);

        candidate.setIgScore(aig);
        candidate.setIgBreakdown(breakdown(candidate.getDimensions(), exploredRegions,
                surpriseContext, nodeKey, cost, rootMetricKey, phase));
    }

    /**
     * 解析 AIG 各分项及当前阶段权重为可展示的 breakdown。
     */
    public Map<String, Double> breakdown(Map<String, List<String>> queryDimensions,
                                         Map<String, Set<String>> exploredRegions,
                                         double surpriseContext,
                                         String lineageNodeKey,
                                         long estimatedRows,
                                         String rootMetricKey,
                                         Phase phase) {
        double coverage = baseIg.calcCoverageNew(queryDimensions, exploredRegions);
        double surprise = baseIg.normalizeSurprise(surpriseContext);
        double lineage = baseIg.calcLineageDepth(lineageNodeKey, rootMetricKey);
        double cost = baseIg.normalizeCost(estimatedRows);

        double[] w = getWeights(phase);

        Map<String, Double> bd = new LinkedHashMap<>();
        bd.put("phase", (double) phase.ordinal());
        bd.put("w_coverage", w[0]);
        bd.put("w_surprise", w[1]);
        bd.put("w_lineage", w[2]);
        bd.put("w_cost", w[3]);
        bd.put("coverageNew", coverage);
        bd.put("surpriseScore", surprise);
        bd.put("lineageDepth", lineage);
        bd.put("cost", cost);
        bd.put("total", w[0] * coverage + w[1] * surprise + w[2] * lineage - w[3] * cost);
        return bd;
    }

    // ══════════════════════════════════════════════════════════
    // 内部工具
    // ══════════════════════════════════════════════════════════

    /**
     * 返回 [α(Coverage), β(Surprise), γ(Lineage), ε(Cost)]
     */
    private double[] getWeights(Phase phase) {
        switch (phase) {
            case BROAD_SWEEP:
                return new double[]{sweepCoverage, sweepSurprise, sweepLineage, sweepCost};
            case DEEP_DRILL:
                return new double[]{drillCoverage, drillSurprise, drillLineage, drillCost};
            case VERIFICATION:
                return new double[]{verifyCoverage, verifySurprise, verifyLineage, verifyCost};
            default:
                return new double[]{sweepCoverage, sweepSurprise, sweepLineage, sweepCost};
        }
    }
}
