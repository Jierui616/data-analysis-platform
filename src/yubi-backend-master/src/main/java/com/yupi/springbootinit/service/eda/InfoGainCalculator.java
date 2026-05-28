package com.yupi.springbootinit.service.eda;

import com.yupi.springbootinit.service.LineageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 信息增益计算器。
 * IG(q) = w1 * CoverageNew + w2 * SurpriseScore + w3 * LineageDepth - w4 * Cost
 */
@Component
@Slf4j
public class InfoGainCalculator {

    @Resource
    private LineageService lineageService;

    @Value("${autoeda.ig.weight-coverage:0.30}")
    private double wCoverage;

    @Value("${autoeda.ig.weight-surprise:0.35}")
    private double wSurprise;

    @Value("${autoeda.ig.weight-lineage-depth:0.20}")
    private double wLineageDepth;

    @Value("${autoeda.ig.weight-cost:0.15}")
    private double wCost;

    @Value("${autoeda.ig.cost-max-rows:5000000}")
    private long costMaxRows;

    /**
     * 计算候选查询的信息增益分数
     *
     * @param queryDimensions 查询涉及的维度组合 {"time":["2025-06"], "city":["杭州"]}
     * @param exploredRegions 已探索区域
     * @param surpriseContext 附近的异常幅度（邻近已探索区域的max z-score）
     * @param lineageNodeKey  查询目标在血缘图中的节点key
     * @param estimatedRows   预估扫描行数
     * @param rootMetricKey   根指标节点key
     * @return IG 分数 (0~1 归一化)
     */
    public double calculate(Map<String, List<String>> queryDimensions,
                            Map<String, Set<String>> exploredRegions,
                            double surpriseContext,
                            String lineageNodeKey,
                            long estimatedRows,
                            String rootMetricKey) {
        double coverage = calcCoverageNew(queryDimensions, exploredRegions);
        double surprise = normalizeSurprise(surpriseContext);
        double lineageDepth = calcLineageDepth(lineageNodeKey, rootMetricKey);
        double cost = normalizeCost(estimatedRows);

        double ig = wCoverage * coverage
                  + wSurprise * surprise
                  + wLineageDepth * lineageDepth
                  - wCost * cost;

        return Math.max(0.0, Math.min(1.0, ig));
    }

    /**
     * CoverageNew: 未探索维度单元占查询涉及单元的比例
     */
    private double calcCoverageNew(Map<String, List<String>> queryDimensions,
                                   Map<String, Set<String>> exploredRegions) {
        if (queryDimensions == null || queryDimensions.isEmpty()) return 1.0;

        int total = 0;
        int unexplored = 0;
        for (Map.Entry<String, List<String>> entry : queryDimensions.entrySet()) {
            String dim = entry.getKey();
            Set<String> explored = exploredRegions.getOrDefault(dim, Collections.emptySet());
            for (String val : entry.getValue()) {
                total++;
                if (!explored.contains(val)) unexplored++;
            }
        }
        return total == 0 ? 0 : (double) unexplored / total;
    }

    /**
     * SurpriseScore: 将异常 Z-score 归一化到 [0,1]
     * 使用 tanh，使得 Z=2 → 0.96, Z=3 → 1.0
     */
    private double normalizeSurprise(double zScore) {
        return Math.tanh(Math.abs(zScore) / 2.0);
    }

    /**
     * LineageDepth: 从根指标到当前节点的血缘跳数 / maxDepth
     */
    private double calcLineageDepth(String nodeKey, String rootMetricKey) {
        if (nodeKey == null || rootMetricKey == null) return 0;
        if (nodeKey.equals(rootMetricKey)) return 0;
        List<String> path = lineageService.getLineagePath(nodeKey);
        if (path.isEmpty()) return 0;
        int idx = path.indexOf(rootMetricKey);
        int depth = idx >= 0 ? path.size() - idx - 1 : path.size();
        return Math.min(1.0, depth / 10.0);
    }

    /**
     * Cost: 预估扫描行数 / 最大行数，归一化到 [0,1]
     */
    private double normalizeCost(long estimatedRows) {
        return Math.min(1.0, (double) estimatedRows / costMaxRows);
    }

    /**
     * 解析 IG 各分项为可展示的 breakdown
     */
    public Map<String, Double> breakdown(Map<String, List<String>> queryDimensions,
                                         Map<String, Set<String>> exploredRegions,
                                         double surpriseContext,
                                         String lineageNodeKey,
                                         long estimatedRows,
                                         String rootMetricKey) {
        double coverage = calcCoverageNew(queryDimensions, exploredRegions);
        double surprise = normalizeSurprise(surpriseContext);
        double lineageDepth = calcLineageDepth(lineageNodeKey, rootMetricKey);
        double cost = normalizeCost(estimatedRows);

        Map<String, Double> bd = new LinkedHashMap<>();
        bd.put("coverageNew", coverage);
        bd.put("surpriseScore", surprise);
        bd.put("lineageDepth", lineageDepth);
        bd.put("cost", cost);
        bd.put("total", wCoverage * coverage + wSurprise * surprise + wLineageDepth * lineageDepth - wCost * cost);
        return bd;
    }
}
