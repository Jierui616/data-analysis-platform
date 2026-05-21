package com.yupi.springbootinit.config.bigdata;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 大数据栈开关与连接配置（ClickHouse / Kafka / 同步策略）。
 */
@Data
@ConfigurationProperties(prefix = "bigdata")
public class BigDataProperties {

    /** 总开关：开启后经营分析可优先读 ClickHouse，并在接口中返回 bigData 元数据 */
    private boolean enabled = false;

    private ClickHouse clickhouse = new ClickHouse();

    private Kafka kafka = new Kafka();

    private Sync sync = new Sync();

    @Data
    public static class ClickHouse {
        private boolean enabled = false;
        /** 为 true 且 CH 有数据时，总部概览 summary/trend 优先走 OLAP */
        private boolean preferRead = true;
        private String url = "jdbc:ch://127.0.0.1:8123/yubi_analytics";
        private String username = "default";
        private String password = "";
    }

    @Data
    public static class Kafka {
        private boolean enabled = false;
        private String bootstrapServers = "localhost:9092";
        private Topics topics = new Topics();
    }

    @Data
    public static class Topics {
        private String chartGenCompleted = "yubi.chart.gen.completed";
        private String importCompleted = "yubi.import.completed";
    }

    @Data
    public static class Sync {
        private boolean scheduledEnabled = false;
        /** 默认每天 02:00 全量同步门店日指标 */
        private String cron = "0 0 2 * * ?";
    }
}
