package com.yupi.springbootinit.model.vo.bicenter;

import lombok.Data;

import java.util.List;

@Data
public class OperatorOverviewVO {

    private String period;

    private CampaignVO campaign;
    private RfmVO rfm;
    private List<PersonaVO> persona;
    private FunnelDiagnosisVO funnelDiagnosis;
    private AttributionVO attribution;
    private List<OptimizationActionVO> optimizationActions;
    private AiBlockVO ai;

    @Data
    public static class CampaignVO {
        private String name;
        private Number predictedRoi;
        private Number realtimeRoi;
        private Number finalRoi;
        private FunnelVO funnel;
        private RealtimeVO realtime;
    }

    @Data
    public static class FunnelVO {
        private List<String> stages;
        private List<Number> values;
    }

    @Data
    public static class RealtimeVO {
        private List<String> x;
        private List<Number> spend;
        private List<Number> conversions;
    }

    @Data
    public static class RfmVO {
        private List<String> segments;
        private List<Number> counts;
    }

    @Data
    public static class PersonaVO {
        private String name;
        private Number share;
        private List<String> tags;
    }

    @Data
    public static class FunnelDiagnosisVO {
        private List<String> stages;
        private List<Number> values;
        private List<Number> conversionRates;
        private List<Number> dropOffRates;
        private List<ChannelCompareVO> channelCompare;
        private String bottleneckStage;
    }

    @Data
    public static class ChannelCompareVO {
        private String channel;
        private Number conversionRate;
        private Number dropOffRate;
        private Number roi;
    }

    @Data
    public static class AttributionVO {
        private List<ContributionVO> crowds;
        private List<ContributionVO> channels;
        private List<ContributionVO> creatives;
        private List<HeatPointVO> heatmap;
    }

    @Data
    public static class ContributionVO {
        private String name;
        private Number share;
        private Number roi;
        private Number risk;
    }

    @Data
    public static class HeatPointVO {
        private String timeSlot;
        private String channel;
        private Number score;
    }

    @Data
    public static class OptimizationActionVO {
        private String priority;
        private String title;
        private String detail;
        private String expectedLift;
    }
}

