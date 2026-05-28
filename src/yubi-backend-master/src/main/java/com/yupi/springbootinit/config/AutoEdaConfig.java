package com.yupi.springbootinit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "autoeda")
@Data
public class AutoEdaConfig {

    private IgConfig ig = new IgConfig();
    private HypothesisConfig hypothesis = new HypothesisConfig();
    private String tableName = "store_daily_metric";

    @Data
    public static class IgConfig {
        private double weightCoverage = 0.30;
        private double weightSurprise = 0.35;
        private double weightLineageDepth = 0.20;
        private double weightCost = 0.15;
        private long costMaxRows = 5000000;
    }

    @Data
    public static class HypothesisConfig {
        private double acceptThreshold = 0.75;
        private double rejectThreshold = 0.25;
        private int maxStaleRounds = 3;
    }
}
