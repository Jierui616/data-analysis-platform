package com.yupi.springbootinit.utils;

import com.google.gson.Gson;
import com.yupi.springbootinit.constant.LineageConstant;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 血缘事件写入时的 datasetsIn/datasetsOut 与 eventMeta 约定，供同步流程与 MQ 消费端复用。
 */
public final class LineageRecordMetaHelper {

    private static final Gson GSON = new Gson();

    private LineageRecordMetaHelper() {
    }

    /**
     * 为图表分析链路补齐结构化数据集标识，提升图谱 event_meta 证据比例。
     */
    public static void enrichDatasetsForChart(List<String> datasetsIn, List<String> datasetsOut, Long chartId, String eventType) {
        ensureChartInputDataset(datasetsIn, chartId);
        ensureDerivedOutputDatasets(datasetsOut, chartId, eventType);
    }

    public static String buildEventMetaJson(String logicRef, List<String> datasetsIn, List<String> datasetsOut) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("logicRef", StringUtils.isBlank(logicRef) ? "default" : logicRef.trim());
        meta.put("datasetsIn", datasetsIn == null ? Collections.emptyList() : datasetsIn);
        meta.put("datasetsOut", datasetsOut == null ? Collections.emptyList() : datasetsOut);
        return GSON.toJson(meta);
    }

    private static void ensureChartInputDataset(List<String> datasetsIn, Long chartId) {
        if (datasetsIn == null || chartId == null || chartId <= 0) {
            return;
        }
        String datasetName = ("chart_input_" + chartId).toLowerCase();
        for (String d : datasetsIn) {
            if (d != null && datasetName.equalsIgnoreCase(d.trim())) {
                return;
            }
        }
        datasetsIn.add(datasetName);
    }

    private static void ensureDerivedOutputDatasets(List<String> datasetsOut, Long chartId, String eventType) {
        if (datasetsOut == null || chartId == null || chartId <= 0 || StringUtils.isBlank(eventType)) {
            return;
        }
        String type = eventType.trim();
        if (LineageConstant.EVENT_SOURCE_INGESTED.equals(type)) {
            addDatasetIfAbsent(datasetsOut, "chart_input_" + chartId);
            return;
        }
        if (LineageConstant.EVENT_DATA_CLEANED.equals(type)) {
            addDatasetIfAbsent(datasetsOut, "chart_clean_" + chartId);
            return;
        }
        if (LineageConstant.EVENT_METRIC_COMPUTED.equals(type)) {
            addDatasetIfAbsent(datasetsOut, "chart_metric_" + chartId);
            return;
        }
        if (LineageConstant.EVENT_CHART_RENDERED.equals(type)) {
            addDatasetIfAbsent(datasetsOut, "chart_option_" + chartId);
            return;
        }
        if (LineageConstant.EVENT_AI_INTERPRETED.equals(type)) {
            addDatasetIfAbsent(datasetsOut, "chart_ai_result_" + chartId);
        }
    }

    private static void addDatasetIfAbsent(List<String> datasets, String datasetName) {
        if (datasets == null || StringUtils.isBlank(datasetName)) {
            return;
        }
        String target = datasetName.trim().toLowerCase();
        for (String d : datasets) {
            if (d != null && target.equalsIgnoreCase(d.trim())) {
                return;
            }
        }
        datasets.add(target);
    }
}
