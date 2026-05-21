package com.yupi.springbootinit.service.impl.storage;

import com.google.gson.Gson;
import com.yupi.springbootinit.config.storage.StorageProperties;
import com.yupi.springbootinit.model.dto.storage.ChartDataRef;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.service.storage.ChartDataStorageService;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ChartDataStorageServiceImpl implements ChartDataStorageService {

    private static final Gson GSON = new Gson();

    @Resource
    private StorageProperties storageProperties;

    private final AtomicReference<MinioClient> minioClientRef = new AtomicReference<>();

    @Override
    public boolean tryExternalizeChartData(Chart chart, String csvContent) {
        if (chart == null || chart.getId() == null || StringUtils.isBlank(csvContent)) {
            return false;
        }
        if (!storageProperties.getChartData().isEnabled()) {
            return false;
        }
        StorageProperties.Minio minio = storageProperties.getMinio();
        if (StringUtils.isBlank(minio.getEndpoint()) || StringUtils.isBlank(minio.getBucket())) {
            log.warn("MinIO 未配置 endpoint/bucket，跳过 chartData 外置");
            return false;
        }
        String prefix = StringUtils.defaultIfBlank(storageProperties.getChartData().getKeyPrefix(), "chart-data");
        String objectKey = prefix + "/" + chart.getUserId() + "/" + chart.getId() + ".csv";
        byte[] bytes = csvContent.getBytes(StandardCharsets.UTF_8);
        try {
            MinioClient client = getOrCreateMinioClient();
            ensureBucket(client, minio.getBucket());
            client.putObject(PutObjectArgs.builder()
                    .bucket(minio.getBucket())
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType("text/csv")
                    .build());
            ChartDataRef ref = new ChartDataRef();
            ref.setProvider(ChartDataRef.PROVIDER_MINIO);
            ref.setBucket(minio.getBucket());
            ref.setObjectKey(objectKey);
            chart.setChartDataRef(GSON.toJson(ref));
            chart.setChartData(null);
            log.info("chartData 已外置 MinIO: chartId={} key={}", chart.getId(), objectKey);
            return true;
        } catch (Exception e) {
            log.warn("chartData 外置 MinIO 失败，保留 MySQL 内联: chartId={} {}", chart.getId(), e.getMessage());
            return false;
        }
    }

    private void ensureBucket(MinioClient client, String bucket) throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
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
