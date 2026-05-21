package com.yupi.springbootinit.service.bigdata;

import com.yupi.springbootinit.config.bigdata.BigDataProperties;
import com.yupi.springbootinit.config.storage.StorageProperties;
import io.minio.MinioClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class BigDataStackProbeService {

    @Resource
    private BigDataProperties bigDataProperties;

    @Resource
    private StorageProperties storageProperties;

    @Resource
    private MetricSyncService metricSyncService;

    @Autowired(required = false)
    private ClickHouseMetricRepository clickHouseMetricRepository;

    @Autowired(required = false)
    @Qualifier("bigDataKafkaTemplate")
    private KafkaTemplate<String, String> kafkaTemplate;

    private final AtomicReference<MinioClient> minioClientRef = new AtomicReference<>();

    public Map<String, Object> probe() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("bigDataEnabled", bigDataProperties.isEnabled());

        Map<String, Object> clickhouse = new LinkedHashMap<>();
        clickhouse.put("enabled", bigDataProperties.getClickhouse().isEnabled());
        clickhouse.put("preferRead", bigDataProperties.getClickhouse().isPreferRead());
        clickhouse.put("reachable", false);
        clickhouse.put("rowCount", 0L);
        if (clickHouseMetricRepository != null) {
            try {
                long rows = clickHouseMetricRepository.countStoreDailyRows();
                clickhouse.put("reachable", true);
                clickhouse.put("rowCount", rows);
            } catch (Exception e) {
                clickhouse.put("error", e.getMessage());
            }
        }
        if (metricSyncService.getLastSyncAt() != null) {
            clickhouse.put("lastSyncAt", metricSyncService.getLastSyncAt().toString());
        }
        root.put("clickhouse", clickhouse);

        Map<String, Object> kafka = new LinkedHashMap<>();
        kafka.put("enabled", bigDataProperties.getKafka().isEnabled());
        kafka.put("bootstrapServers", bigDataProperties.getKafka().getBootstrapServers());
        kafka.put("producerReady", kafkaTemplate != null);
        root.put("kafka", kafka);

        Map<String, Object> minio = new LinkedHashMap<>();
        minio.put("chartDataExternalEnabled", storageProperties.getChartData().isEnabled());
        minio.put("endpoint", storageProperties.getMinio().getEndpoint());
        minio.put("reachable", probeMinio());
        root.put("minio", minio);

        root.put("analyticsEngineHint",
                resolveAnalyticsEngine(clickhouse, bigDataProperties.getClickhouse().isPreferRead()));
        return root;
    }

    private String resolveAnalyticsEngine(Map<String, Object> clickhouse, boolean preferRead) {
        if (!bigDataProperties.isEnabled()) {
            return "mysql";
        }
        boolean reachable = Boolean.TRUE.equals(clickhouse.get("reachable"));
        long rowCount = clickhouse.get("rowCount") instanceof Number
                ? ((Number) clickhouse.get("rowCount")).longValue() : 0L;
        if (preferRead && reachable && rowCount > 0) {
            return "clickhouse";
        }
        return "mysql";
    }

    private boolean probeMinio() {
        StorageProperties.Minio m = storageProperties.getMinio();
        if (StringUtils.isBlank(m.getEndpoint())) {
            return false;
        }
        try {
            MinioClient client = getOrCreateMinioClient();
            client.listBuckets();
            return true;
        } catch (Exception e) {
            log.debug("MinIO probe failed: {}", e.getMessage());
            return false;
        }
    }

    private MinioClient getOrCreateMinioClient() {
        MinioClient existing = minioClientRef.get();
        if (existing != null) {
            return existing;
        }
        StorageProperties.Minio m = storageProperties.getMinio();
        MinioClient created = MinioClient.builder()
                .endpoint(m.getEndpoint())
                .credentials(m.getAccessKey(), m.getSecretKey())
                .build();
        minioClientRef.compareAndSet(null, created);
        return minioClientRef.get();
    }
}
