package com.yupi.springbootinit.constant;

/**
 * 数据血缘与可解释分析常量
 */
public interface LineageConstant {

    String EVENT_SOURCE_INGESTED = "SOURCE_INGESTED";
    String EVENT_DATA_CLEANED = "DATA_CLEANED";
    String EVENT_METRIC_COMPUTED = "METRIC_COMPUTED";
    String EVENT_CHART_RENDERED = "CHART_RENDERED";
    String EVENT_AI_INTERPRETED = "AI_INTERPRETED";

    String EXPLAIN_TYPE_METRIC = "metric";
    String EXPLAIN_TYPE_CHART = "chart";
    String EXPLAIN_TYPE_AI = "ai";
}
