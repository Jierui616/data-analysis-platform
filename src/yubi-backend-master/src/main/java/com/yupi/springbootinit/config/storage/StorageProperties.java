package com.yupi.springbootinit.config.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 图表 CSV 外置存储（MinIO / COS）配置，与 application.yml 中 storage.* 对齐。
 */
@Data
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    private ChartData chartData = new ChartData();

    private Minio minio = new Minio();

    @Data
    public static class ChartData {
        /** 是否启用外置写入（读取侧只要 chartDataRef 有值即尝试拉取） */
        private boolean enabled = false;
        private String provider = "minio";
        private String keyPrefix = "chart-data";
    }

    @Data
    public static class Minio {
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String bucket;
    }
}
