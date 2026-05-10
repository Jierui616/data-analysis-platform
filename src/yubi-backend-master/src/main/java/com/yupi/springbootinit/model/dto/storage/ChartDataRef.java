package com.yupi.springbootinit.model.dto.storage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * chart.chartData 外置后的元数据（JSON 存于 chart.chartDataRef）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChartDataRef {
    public static final String PROVIDER_MINIO = "minio";
    public static final String PROVIDER_COS = "cos";

    private String provider;
    private String bucket;
    private String objectKey;
    private String etag;
}
