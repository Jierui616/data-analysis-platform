package com.yupi.springbootinit.service.storage;

import com.yupi.springbootinit.model.entity.Chart;

/**
 * 解析图表原始 CSV：支持库内 {@link Chart#getChartData()} 或外置 {@link Chart#getChartDataRef()}。
 */
public interface ChartDataResolverService {

    /**
     * 是否存在可用的原始 CSV（内联非空或外置引用有效）。
     */
    boolean hasCsv(Chart chart);

    /**
     * 若仅有外置引用，则拉取对象并写入 {@link Chart#setChartData(String)}，便于后续逻辑只读 chartData。
     */
    void hydrate(Chart chart);

    /**
     * 返回 CSV 文本：优先内联；否则按 chartDataRef 从对象存储读取（不强制写入实体，便于只读场景）。
     */
    String resolveCsv(Chart chart);
}
