package com.yupi.springbootinit.service.impl.storage;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.yupi.springbootinit.config.CosClientConfig;
import com.yupi.springbootinit.config.storage.StorageProperties;
import com.yupi.springbootinit.manager.CosManager;
import com.yupi.springbootinit.model.dto.storage.ChartDataRef;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.service.storage.ChartDataResolverService;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import javax.annotation.Resource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * chartData 内联与 chartDataRef（MinIO/COS）统一解析。
 */
@Service
public class ChartDataResolverServiceImpl implements ChartDataResolverService {

    private static final Gson GSON = new Gson();

    @Resource
    private StorageProperties storageProperties;

    @Resource
    private CosManager cosManager;

    @Resource
    private CosClientConfig cosClientConfig;

    private final AtomicReference<MinioClient> minioClientRef = new AtomicReference<>();

    @Override
    public boolean hasCsv(Chart chart) {
        if (chart == null) {
            return false;
        }
        if (StringUtils.isNotBlank(chart.getChartData())) {
            return true;
        }
        ChartDataRef ref = parseRef(chart.getChartDataRef());
        return ref != null && StringUtils.isNotBlank(ref.getObjectKey());
    }

    @Override
    public void hydrate(Chart chart) {
        if (chart == null) {
            return;
        }
        if (StringUtils.isNotBlank(chart.getChartData())) {
            return;
        }
        String csv = loadFromRef(chart.getChartDataRef());
        if (StringUtils.isNotBlank(csv)) {
            chart.setChartData(csv);
        }
    }

    @Override
    public String resolveCsv(Chart chart) {
        if (chart == null) {
            return "";
        }
        if (StringUtils.isNotBlank(chart.getChartData())) {
            return chart.getChartData();
        }
        return StringUtils.defaultString(loadFromRef(chart.getChartDataRef()));
    }

    private String loadFromRef(String chartDataRefJson) {
        ChartDataRef ref = parseRef(chartDataRefJson);
        if (ref == null || StringUtils.isBlank(ref.getObjectKey())) {
            return "";
        }
        String provider = StringUtils.defaultIfBlank(ref.getProvider(), ChartDataRef.PROVIDER_MINIO).toLowerCase();
        String bucket = StringUtils.isNotBlank(ref.getBucket())
                ? ref.getBucket()
                : defaultBucketForProvider(provider);
        if (StringUtils.isBlank(bucket)) {
            throw new IllegalStateException("chartDataRef 缺少 bucket，且未配置默认桶: provider=" + provider);
        }
        if (ChartDataRef.PROVIDER_COS.equals(provider)) {
            return cosManager.getObjectAsString(bucket, ref.getObjectKey());
        }
        return getFromMinio(bucket, ref.getObjectKey());
    }

    private String defaultBucketForProvider(String provider) {
        if (ChartDataRef.PROVIDER_COS.equals(provider)) {
            return cosClientConfig == null ? null : cosClientConfig.getBucket();
        }
        StorageProperties.Minio m = storageProperties.getMinio();
        return m == null ? null : m.getBucket();
    }

    private String getFromMinio(String bucket, String objectKey) {
        MinioClient client = getOrCreateMinioClient();
        try (InputStream in = client.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("MinIO 读取图表 CSV 失败: " + bucket + "/" + objectKey, e);
        }
    }

    private MinioClient getOrCreateMinioClient() {
        MinioClient existing = minioClientRef.get();
        if (existing != null) {
            return existing;
        }
        StorageProperties.Minio m = storageProperties.getMinio();
        if (m == null || StringUtils.isBlank(m.getEndpoint())) {
            throw new IllegalStateException("未配置 storage.minio.endpoint，无法读取外置 chartDataRef");
        }
        String access = StringUtils.defaultString(m.getAccessKey());
        String secret = StringUtils.defaultString(m.getSecretKey());
        MinioClient created = MinioClient.builder()
                .endpoint(m.getEndpoint())
                .credentials(access, secret)
                .build();
        minioClientRef.compareAndSet(null, created);
        return minioClientRef.get();
    }

    private ChartDataRef parseRef(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            ChartDataRef ref = GSON.fromJson(json.trim(), ChartDataRef.class);
            if (ref == null || StringUtils.isBlank(ref.getObjectKey())) {
                return null;
            }
            return ref;
        } catch (JsonSyntaxException e) {
            return null;
        }
    }
}
