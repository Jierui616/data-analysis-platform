package com.yupi.springbootinit.model.vo.bicenter;

import lombok.Data;

import java.util.List;

@Data
public class ManagerOverviewVO {

    private String period;

    private Number revenue;
    private Number profit;
    private Number cashflow;
    private Number inventoryTurnover;
    private Number userGrowthRate;
    private Number marketingRoi;

    /**
     * 0-100，后端可返回；前端也可自行计算
     */
    private Integer healthScore;

    private KpiTrendVO kpiTrend;

    private List<AlertVO> alertList;

    private OverviewSectionVO overview;

    private List<AnomalyVO> anomalies;

    private List<ActionVO> actions;

    private AiBlockVO ai;

    @Data
    public static class OverviewSectionVO {
        private Number healthScore;
        private Number revenue;
        private Number profit;
        private Number cashflow;
        private Number revenueYoY;
        private Number revenueMoM;
        private Number profitYoY;
        private Number cashflowYoY;
        private Number targetCompletionRate;
        private String updatedAt;
        private String dataRange;
        private Integer sampleSize;
    }

    @Data
    public static class KpiTrendVO {
        private List<String> x;
        private List<Number> revenue;
        private List<Number> profit;
        private List<Number> cashflow;
    }

    @Data
    public static class AlertVO {
        private String id;
        private String level; // P0 / P1 / P2
        private String kpi;
        private Number current;
        private Number baseline;
        private Number deltaRate;
        private String occurredAt;
    }

    @Data
    public static class AnomalyVO {
        private String id;
        private String metric;
        private String level;
        private Number currentValue;
        private Number baselineValue;
        private Number deltaRate;
        private Number impactAmount;
        private String conclusion;
        private String reason;
        private String action;
    }

    @Data
    public static class ActionVO {
        private String priority;
        private String title;
        private String owner;
        private String expectedBenefit;
        private String eta;
    }
}

