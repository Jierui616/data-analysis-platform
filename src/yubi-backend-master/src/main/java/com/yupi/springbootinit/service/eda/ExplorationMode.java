package com.yupi.springbootinit.service.eda;

import org.apache.commons.lang3.StringUtils;

/**
 * AutoEDA 实验模式（论文消融与基线对比）。
 *
 * 论文创新对应:
 *   AIG  — 自适应阶段感知 InfoGain（升级基础 IG）
 *   EHIG — 期望假设信息增益（信息论统一框架）
 *   PSC  — 后验停滞收敛（useAdaptiveConvergence）
 *   BHC  — 贝叶斯假设周期（useBayesian）
 */
public enum ExplorationMode {

    // ══════════════════════════════════════════════════════════
    // 论文核心方法
    // ══════════════════════════════════════════════════════════

    /** 完整方法：血缘 + 基础 IG + Bayesian + PSC 收敛 */
    FULL(true, true, true, false, true, true, false, false),

    /** AIG 策略：用自适应 InfoGain 替代固定权重 IG */
    AIG(true, true, true, false, true, true, true, false),

    /** EHIG 策略：用期望假设信息增益选择查询 */
    EHIG(true, true, true, false, true, true, false, true),

    /** AIG + EHIG 联合：AIG 评分 + EHIG 终选 */
    AIG_EHIG(true, true, true, false, true, true, true, true),

    // ══════════════════════════════════════════════════════════
    // 消融实验（论文 Table 3）
    // ══════════════════════════════════════════════════════════

    /** 消融：无 PSC——固定跑满预算，不提前收敛 */
    NO_PSC(true, true, true, false, true, false, false, false),

    /** 消融：无 BHC——不做贝叶斯假设周期，所有假设直接标记为 VERIFIED */
    NO_BHC(true, true, true, false, false, true, false, false),

    /** 消融：无血缘剪枝，IG 不含 LineageDepth 项 */
    NO_LINEAGE(false, true, true, false, true, true, false, false),

    /** 消融：无 IG，候选随机选 */
    NO_IG(true, false, true, false, true, true, false, false),

    // ══════════════════════════════════════════════════════════
    // 基线
    // ══════════════════════════════════════════════════════════

    /** 随机策略：无血缘、无 IG、无 Bayesian */
    RANDOM(false, false, false, false, true, true, false, false),

    /** 纯 LLM 生成，无 IG 排序、无血缘 */
    LLM_ONLY(false, false, true, false, true, true, false, false),

    /** 固定步数：同 FULL 但跑满预算 */
    FIXED_STEP(true, true, true, false, true, false, false, false),

    /** 保留血缘 + Bayesian，无 IG（同 NO_IG 但强调 Bayesian 保留） */
    NO_BAYES(true, true, true, false, false, true, false, false);

    // ══════════════════════════════════════════════════════════
    // 字段
    // ══════════════════════════════════════════════════════════

    private final boolean useLineageFilter;
    private final boolean useIgSelection;
    private final boolean useTemplateSqlFirst;
    private final boolean forceLlmSql;
    private final boolean useBayesian;
    private final boolean useAdaptiveConvergence;
    /** 使用 AIG 自适应 InfoGain 替代基础 IG */
    private final boolean useAig;
    /** 使用 EHIG 熵驱动选择器替代 IG 排序 */
    private final boolean useEhig;

    // ══════════════════════════════════════════════════════════
    // 构造函数
    // ══════════════════════════════════════════════════════════

    /**
     * 完整参数构造函数（论文新模式）。
     */
    ExplorationMode(boolean useLineageFilter,
                    boolean useIgSelection,
                    boolean useTemplateSqlFirst,
                    boolean forceLlmSql,
                    boolean useBayesian,
                    boolean useAdaptiveConvergence,
                    boolean useAig,
                    boolean useEhig) {
        this.useLineageFilter = useLineageFilter;
        this.useIgSelection = useIgSelection;
        this.useTemplateSqlFirst = useTemplateSqlFirst;
        this.forceLlmSql = forceLlmSql;
        this.useBayesian = useBayesian;
        this.useAdaptiveConvergence = useAdaptiveConvergence;
        this.useAig = useAig;
        this.useEhig = useEhig;
    }

    // ══════════════════════════════════════════════════════════
    // 工厂方法
    // ══════════════════════════════════════════════════════════

    public static ExplorationMode from(String raw) {
        if (StringUtils.isBlank(raw)) {
            return FULL;
        }
        try {
            return ExplorationMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return FULL;
        }
    }

    // ══════════════════════════════════════════════════════════
    // Getters
    // ══════════════════════════════════════════════════════════

    public boolean useLineageFilter() {
        return useLineageFilter;
    }

    /** IG 公式是否包含血缘深度项 */
    public boolean useLineageInIg() {
        return useLineageFilter;
    }

    public boolean useIgSelection() {
        return useIgSelection;
    }

    public boolean useTemplateSqlFirst() {
        return useTemplateSqlFirst;
    }

    public boolean forceLlmSql() {
        return forceLlmSql;
    }

    public boolean useBayesian() {
        return useBayesian;
    }

    public boolean useAdaptiveConvergence() {
        return useAdaptiveConvergence;
    }

    /** 是否使用 AIG 自适应 InfoGain */
    public boolean useAig() {
        return useAig;
    }

    /** 是否使用 EHIG 熵驱动选择器 */
    public boolean useEhig() {
        return useEhig;
    }
}
