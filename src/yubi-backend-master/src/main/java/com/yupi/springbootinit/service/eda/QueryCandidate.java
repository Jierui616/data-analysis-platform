package com.yupi.springbootinit.service.eda;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 候选探索查询（维度组合 + SQL + IG 评分）。
 */
@Data
public class QueryCandidate {

    private Map<String, List<String>> dimensions = new LinkedHashMap<>();
    private String sql;
    private String lineageNodeKey;
    private long estimatedRows = 100_000L;
    private double igScore;
    private Map<String, Double> igBreakdown = new LinkedHashMap<>();
    /** 执行后回填，用于下一轮 Surprise 估计 */
    private double surpriseContext;
}
