package com.yupi.springbootinit.model.vo.autoeda;

import lombok.Data;

import java.util.List;

@Data
public class InsightReportVO {

    private String sessionId;

    private String chartName;

    private Long chartId;

    /** 1. Executive Summary */
    private List<InsightItem> executiveSummary;

    /** 2. Anomalies & Alerts */
    private List<AnomalyItem> anomalies;

    /** 3. Deep Insights */
    private List<InsightItem> deepInsights;

    /** 4. Open Leads */
    private List<OpenLeadItem> openLeads;

    /** 5. Exploration Audit */
    private List<AuditStep> auditTrail;

    private Integer totalQueriesExecuted;

    private Long generatedAt;

    @Data
    public static class InsightItem {
        private String title;
        private String description;
        private Double confidence;
        private String actionableAdvice;
        private List<EvidenceChainVO> evidenceChain;
    }

    @Data
    public static class AnomalyItem {
        private String metricName;
        private String anomalyDescription;
        private Double deviationSigma;
        private String rootCause;
        private String businessImpact;
        private List<EvidenceChainVO> evidenceChain;
    }

    @Data
    public static class OpenLeadItem {
        private String hypothesis;
        private String reasonIncomplete;
        private String suggestedDataToCollect;
    }

    @Data
    public static class AuditStep {
        private Integer stepNumber;
        private String querySql;
        private String decisionReason;
        private Double igScore;
        private String resultSummary;
        private Integer executionMs;
    }
}
