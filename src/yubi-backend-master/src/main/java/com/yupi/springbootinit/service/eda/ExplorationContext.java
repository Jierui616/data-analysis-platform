package com.yupi.springbootinit.service.eda;

import com.yupi.springbootinit.model.entity.Chart;
import lombok.Data;

import java.util.List;
import java.util.Random;

/**
 * 单轮探索的只读上下文（候选生成与策略选择共享）。
 */
@Data
public class ExplorationContext {

    private String sessionId;
    private Chart chart;
    private ExplorationBlackboard.SessionState state;
    private List<String> availableColumns;
    private String tableName;
    private String rootMetricKey;
    private List<String> lineageReachable;
    private String explorationIntent;
    private int maxBudget;
    private double epsilon;
    private int convRounds;
    private Random random;
    private ExplorationMode mode;
    private int candidatePoolSize;
}
