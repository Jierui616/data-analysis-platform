package com.yupi.springbootinit.model.vo.bicenter;

import lombok.Data;

import java.util.List;

@Data
public class InvestorOverviewVO {

    private String company;
    private String period;

    private FinancialCompareVO financialCompare;
    private List<RiskAlertVO> riskAlerts;
    private IndustryVO industry;
    private PeerCompareSectionVO peerCompare;
    private ScenarioForecastVO scenarioForecast;
    private List<RiskRegisterVO> riskRegister;
    private AiBlockVO ai;

    @Data
    public static class FinancialCompareVO {
        private List<String> metrics;
        private List<Number> company;
        private List<Number> peers;
    }

    @Data
    public static class RiskAlertVO {
        private String id;
        private String level; // P0 / P1 / P2
        private String title;
        private String detail;
    }

    @Data
    public static class IndustryVO {
        private List<String> x;
        private List<Number> actual;
        private List<Number> forecast;
    }

    @Data
    public static class PeerCompareSectionVO {
        private List<String> metrics;
        private List<Number> company;
        private List<Number> peers;
        private String rankingSummary;
    }

    @Data
    public static class ScenarioForecastVO {
        private List<String> x;
        private List<Number> base;
        private List<Number> optimistic;
        private List<Number> cautious;
    }

    @Data
    public static class RiskRegisterVO {
        private String id;
        private String level;
        private String title;
        private String triggerCondition;
        private String monitoringSignal;
        private String impact;
    }
}

