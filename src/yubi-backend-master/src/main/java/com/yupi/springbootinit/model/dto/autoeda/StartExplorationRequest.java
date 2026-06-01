package com.yupi.springbootinit.model.dto.autoeda;

import lombok.Data;

import java.util.List;

@Data
public class StartExplorationRequest {

    private Long chartId;

    /** 最大查询预算，默认 30 */
    private Integer maxQueryBudget = 30;

    /** 目标指标列表（空 = 自动从血缘图识别） */
    private List<String> targetMetrics;

    /** 探索收敛阈值 ε，默认 0.05 */
    private Double convergenceEpsilon = 0.05;

    /** 收敛连续轮次 k，默认 3 */
    private Integer convergenceRounds = 3;

    /** 实验模式：FULL / RANDOM / LLM_ONLY / FIXED_STEP / NO_LINEAGE / NO_IG / NO_BAYES */
    private String experimentMode;

    /** 随机种子（可复现实验） */
    private Integer randomSeed;

    /** 数据集标签，如 gt_v1 */
    private String datasetTag;

    /** 每轮候选查询池大小 */
    private Integer candidatePoolSize;
}
