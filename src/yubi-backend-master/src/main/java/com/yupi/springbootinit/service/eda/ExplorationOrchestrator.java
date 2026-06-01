package com.yupi.springbootinit.service.eda;

import com.yupi.springbootinit.service.ClickHouseQueryService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 实验策略编排：血缘过滤、IG/AIG/EHIG 评分、候选选择（对应论文各 baseline / 消融）。
 *
 * 论文创新集成点：
 *   - AIG:  通过 AdaptiveInfoGainCalculator 实现三阶段自适应权重
 *   - EHIG: 通过 EntropyBasedSelector 实现信息论查询选择
 *   - 阶段信息在 preparePool 中通过黑板状态自动检测
 */
@Component
@Slf4j
public class ExplorationOrchestrator {

    @Resource
    private InfoGainCalculator igCalculator;

    @Resource
    private AdaptiveInfoGainCalculator aigCalculator;

    @Resource
    private EntropyBasedSelector ehigSelector;

    @Resource
    private QueryGenerator queryGenerator;

    @Resource
    private ClickHouseQueryService clickHouseQueryService;

    @Value("${autoeda.experiment.use-explain-cost:true}")
    private boolean useExplainCost;

    @Value("${autoeda.ig.cost-fallback-rows:100000}")
    private long costFallbackRows;

    // ══════════════════════════════════════════════════════════
    // preparePool
    // ══════════════════════════════════════════════════════════

    /**
     * 对原始候选池做血缘过滤并计算 IG/AIG 分数。
     * AIG 模式下根据黑板状态自动检测探索阶段并应用对应权重。
     */
    public List<QueryCandidate> preparePool(ExplorationContext ctx, List<QueryCandidate> rawPool) {
        if (rawPool == null || rawPool.isEmpty()) {
            return new ArrayList<>();
        }

        // 血缘过滤
        List<QueryCandidate> filtered = rawPool;
        if (ctx.getMode().useLineageFilter()) {
            filtered = rawPool.stream()
                    .filter(c -> passesLineage(c, ctx))
                    .collect(Collectors.toList());
            if (filtered.isEmpty()) {
                log.debug("Lineage filter removed all candidates for session {}, using raw pool",
                        ctx.getSessionId());
                filtered = rawPool;
            }
        }

        // 预估成本
        enrichEstimatedCost(filtered);

        // 异常上下文
        double priorSurprise = ctx.getState().getLastMaxIg() > 0
                ? ctx.getState().getLastMaxIg() * 2
                : 0;

        // 检测当前探索阶段（AIG 模式使用）
        AdaptiveInfoGainCalculator.Phase phase = null;
        if (ctx.getMode().useAig()) {
            phase = aigCalculator.detectPhase(
                    ctx.getState(), ctx.getMaxBudget(), ctx.getConvRounds());
            log.debug("AIG phase: {} for session {}", phase, ctx.getSessionId());
        }

        // 评分
        for (QueryCandidate c : filtered) {
            if (ctx.getMode().useAig()) {
                scoreCandidateAig(c, ctx, priorSurprise, phase);
            } else {
                scoreCandidateIg(c, ctx, priorSurprise);
            }
        }

        filtered.sort(Comparator.comparingDouble(QueryCandidate::getIgScore).reversed());
        return filtered;
    }

    // ══════════════════════════════════════════════════════════
    // selectBest
    // ══════════════════════════════════════════════════════════

    /**
     * 从候选池选出下一查询。
     * EHIG 模式下使用信息论选择；否则取 IG 最高或随机。
     */
    public QueryCandidate selectBest(List<QueryCandidate> pool, ExplorationContext ctx) {
        if (pool == null || pool.isEmpty()) {
            return null;
        }

        // EHIG 策略：信息论驱动选择
        if (ctx.getMode().useEhig()) {
            QueryCandidate ehigBest = ehigSelector.selectBestByEhig(pool, ctx);
            if (ehigBest != null) {
                log.debug("EHIG selected candidate with score={} for session {}",
                        ehigBest.getIgScore(), ctx.getSessionId());
                return ehigBest;
            }
            // EHIG 失败时 fallback
            log.debug("EHIG selection failed, fallback to top IG for session {}",
                    ctx.getSessionId());
        }

        // IG/AIG 策略：取排名最高
        if (ctx.getMode().useIgSelection()) {
            return pool.get(0);
        }

        // 随机基线
        int idx = ctx.getRandom().nextInt(pool.size());
        return pool.get(idx);
    }

    // ══════════════════════════════════════════════════════════
    // 评分内部方法
    // ══════════════════════════════════════════════════════════

    /**
     * 基础 IG 评分（保持向后兼容）。
     */
    private void scoreCandidateIg(QueryCandidate c, ExplorationContext ctx,
                                   double surpriseContext) {
        Map<String, java.util.Set<String>> explored = ctx.getState().getExploredRegions();
        long cost = c.getEstimatedRows() > 0 ? c.getEstimatedRows() : costFallbackRows;
        String nodeKey = StringUtils.defaultIfBlank(c.getLineageNodeKey(), ctx.getRootMetricKey());

        double ig;
        Map<String, Double> breakdown;
        if (ctx.getMode().useLineageInIg()) {
            ig = igCalculator.calculate(
                    c.getDimensions(), explored, surpriseContext, nodeKey, cost,
                    ctx.getRootMetricKey());
            breakdown = igCalculator.breakdown(
                    c.getDimensions(), explored, surpriseContext, nodeKey, cost,
                    ctx.getRootMetricKey());
        } else {
            ig = igCalculator.calculateWithoutLineage(
                    c.getDimensions(), explored, surpriseContext, cost);
            breakdown = igCalculator.breakdown(
                    c.getDimensions(), explored, surpriseContext, nodeKey, cost,
                    ctx.getRootMetricKey());
            breakdown.put("lineageDepth", 0.0);
            breakdown.put("total", ig);
        }
        c.setIgScore(ig);
        if (breakdown != null) {
            breakdown.put("estimatedRows", (double) cost);
        }
        c.setIgBreakdown(breakdown);
    }

    /**
     * AIG 评分：使用自适应阶段权重。
     */
    private void scoreCandidateAig(QueryCandidate c, ExplorationContext ctx,
                                    double surpriseContext,
                                    AdaptiveInfoGainCalculator.Phase phase) {
        Map<String, java.util.Set<String>> explored = ctx.getState().getExploredRegions();
        long cost = c.getEstimatedRows() > 0 ? c.getEstimatedRows() : costFallbackRows;
        String nodeKey = StringUtils.defaultIfBlank(c.getLineageNodeKey(), ctx.getRootMetricKey());

        aigCalculator.score(c, explored, surpriseContext, ctx.getRootMetricKey(), phase);

        // 补充 cost 到 breakdown
        Map<String, Double> bd = c.getIgBreakdown();
        if (bd != null) {
            bd.put("estimatedRows", (double) cost);
        }
    }

    public double maxIgInPool(List<QueryCandidate> pool) {
        if (pool == null || pool.isEmpty()) {
            return 0;
        }
        return pool.stream()
                .mapToDouble(QueryCandidate::getIgScore)
                .max().orElse(0);
    }

    // ══════════════════════════════════════════════════════════
    // 血缘过滤 & 成本估算（不变）
    // ══════════════════════════════════════════════════════════

    private boolean passesLineage(QueryCandidate c, ExplorationContext ctx) {
        List<String> reachable = ctx.getLineageReachable();
        if (reachable == null || reachable.isEmpty()) {
            return true;
        }
        String sql = StringUtils.defaultString(c.getSql());
        if (StringUtils.isBlank(sql)) {
            return true;
        }
        return queryGenerator.validateByLineage(sql, ctx.getRootMetricKey(), reachable);
    }

    private void enrichEstimatedCost(List<QueryCandidate> candidates) {
        for (QueryCandidate c : candidates) {
            if (StringUtils.isBlank(c.getSql())) {
                c.setEstimatedRows(costFallbackRows);
                continue;
            }
            if (!useExplainCost) {
                c.setEstimatedRows(costFallbackRows);
                continue;
            }
            try {
                long rows = clickHouseQueryService.estimateRowCount(c.getSql());
                c.setEstimatedRows(rows > 0 ? rows : costFallbackRows);
            } catch (Exception e) {
                log.debug("EXPLAIN cost estimate failed for candidate: {}", e.getMessage());
                c.setEstimatedRows(costFallbackRows);
            }
        }
    }
}
