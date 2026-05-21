package com.yupi.springbootinit.service.storage;

import com.yupi.springbootinit.model.entity.Chart;

/**
 * 图表原始 CSV 写入对象存储（MinIO），并回填 chartDataRef。
 */
public interface ChartDataStorageService {

    /**
     * 若已开启 storage.chartData.enabled，则上传 CSV 并更新 chart 的 chartDataRef（可选清空内联 chartData）。
     *
     * @return true 表示已外置存储
     */
    boolean tryExternalizeChartData(Chart chart, String csvContent);
}
