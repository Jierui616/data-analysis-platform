package com.yupi.springbootinit.service;

import com.yupi.springbootinit.model.dto.lineage.ExplainRecordRequest;
import com.yupi.springbootinit.model.dto.lineage.LineageRecordRequest;
import com.yupi.springbootinit.model.vo.lineage.LineageFixTaskVO;
import com.yupi.springbootinit.model.vo.lineage.LineageGraphVO;
import com.yupi.springbootinit.model.vo.lineage.LineageImpactVO;
import com.yupi.springbootinit.model.vo.lineage.LineageTraceVO;

import java.util.List;

public interface LineageService {

    String newTraceId();

    void recordEvent(LineageRecordRequest request);

    void recordExplain(ExplainRecordRequest request);

    LineageTraceVO getTrace(String traceId);

    LineageTraceVO getLatestTraceByChartId(Long chartId);

    LineageGraphVO getAssetGraphByChartId(Long chartId, Integer depth);

    LineageImpactVO getImpactByChartId(Long chartId);

    List<LineageFixTaskVO> getFixTasksByChartId(Long chartId);

    boolean updateFixTaskStatus(Long taskId, String status);
}
