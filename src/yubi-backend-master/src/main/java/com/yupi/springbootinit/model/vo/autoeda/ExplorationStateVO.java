package com.yupi.springbootinit.model.vo.autoeda;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ExplorationStateVO {

    private String sessionId;

    private String status;

    /** 已探索维度区域摘要 */
    private Map<String, List<String>> exploredRegions;

    /** 待验证假设数 */
    private Integer pendingHypothesisCount;

    /** 已验证假设数 */
    private Integer verifiedHypothesisCount;

    /** 当前活跃假设摘要 */
    private List<HypothesisVO> activeHypotheses;

    /** 已执行查询数 / 总预算 */
    private Integer queriesExecuted;

    private Integer queryBudget;

    /** 最近一次 IG 最大值 */
    private Double lastMaxIg;

    /** 收敛状态 */
    private Boolean converged;

    /** 实验模式：FULL / RANDOM / NO_IG 等 */
    private String experimentMode;

    /** 下一步建议查询方向 */
    private List<String> suggestedNextDirections;

    private Long startTime;

    private Long elapsedSeconds;

    /** 失败时的错误摘要 */
    private String errorMessage;
}
