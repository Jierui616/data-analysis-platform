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

    /**
     * 获取指定节点的所有下游节点（血缘链路的子节点，按深度排序）
     * @param nodeKey 节点标识
     * @return 下游节点 key 列表（最近的在前面）
     */
    List<String> getDownstreamNodes(String nodeKey);

    /**
     * 获取指定节点的所有上游节点（血缘链路的父节点，按深度排序）
     * @param nodeKey 节点标识
     * @return 上游节点 key 列表（最近的根在前面）
     */
    List<String> getUpstreamNodes(String nodeKey);

    /**
     * 判断从 fromNode 到 toNode 是否存在血缘可达路径
     * @return true 如果可达
     */
    boolean isReachable(String fromNode, String toNode);

    /**
     * 获取从根指标到指定叶节点的完整血缘路径
     * @param leafNodeKey 叶节点标识
     * @return 路径上的节点 key 列表（从根到叶）
     */
    List<String> getLineagePath(String leafNodeKey);
}
