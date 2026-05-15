package com.yupi.springbootinit.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yupi.springbootinit.mapper.ChartMapper;
import com.yupi.springbootinit.mapper.ExplainSnapshotMapper;
import com.yupi.springbootinit.mapper.LineageFixTaskMapper;
import com.yupi.springbootinit.mapper.LineageEdgeMapper;
import com.yupi.springbootinit.mapper.LineageEventMapper;
import com.yupi.springbootinit.mapper.LineageNodeMapper;
import com.yupi.springbootinit.mapper.LineageRunMapper;
import com.yupi.springbootinit.model.dto.lineage.ExplainRecordRequest;
import com.yupi.springbootinit.model.dto.lineage.LineageRecordRequest;
import com.yupi.springbootinit.model.entity.ExplainSnapshot;
import com.yupi.springbootinit.model.entity.LineageEvent;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.entity.LineageEdge;
import com.yupi.springbootinit.model.entity.LineageFixTask;
import com.yupi.springbootinit.model.entity.LineageNode;
import com.yupi.springbootinit.model.entity.LineageRun;
import com.yupi.springbootinit.model.vo.lineage.LineageFixTaskVO;
import com.yupi.springbootinit.model.vo.lineage.LineageGraphEdgeVO;
import com.yupi.springbootinit.model.vo.lineage.LineageGraphNodeVO;
import com.yupi.springbootinit.model.vo.lineage.LineageGraphVO;
import com.yupi.springbootinit.model.vo.lineage.LineageImpactItemVO;
import com.yupi.springbootinit.model.vo.lineage.LineageImpactVO;
import com.yupi.springbootinit.model.vo.lineage.LineageTraceVO;
import com.yupi.springbootinit.service.LineageService;
import com.yupi.springbootinit.utils.CsvTableUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LineageServiceImpl implements LineageService {

    private static final Logger log = LoggerFactory.getLogger(LineageServiceImpl.class);

    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 物化入库时使用较大深度，便于查询侧拿到完整链路（查询仍可按 depth 走推导降级）。 */
    private static final int MATERIALIZE_GRAPH_DEPTH = 5;

    @Resource
    private LineageEventMapper lineageEventMapper;

    @Resource
    private ExplainSnapshotMapper explainSnapshotMapper;

    @Resource
    private ChartMapper chartMapper;

    @Resource
    private LineageFixTaskMapper lineageFixTaskMapper;

    @Resource
    private LineageNodeMapper lineageNodeMapper;

    @Resource
    private LineageEdgeMapper lineageEdgeMapper;

    @Resource
    private LineageRunMapper lineageRunMapper;

    @Override
    public String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordEvent(LineageRecordRequest request) {
        if (request == null || StringUtils.isBlank(request.getTraceId()) || StringUtils.isBlank(request.getEventType())) {
            return;
        }
        LineageEvent event = new LineageEvent();
        event.setTraceId(request.getTraceId());
        event.setChartId(request.getChartId());
        event.setEventType(request.getEventType());
        event.setInputDigest(digest(request.getInputText()));
        event.setOutputDigest(digest(request.getOutputText()));
        event.setRuleVersion(defaultIfBlank(request.getRuleVersion(), "v1"));
        event.setLogicRef(defaultIfBlank(request.getLogicRef(), "default"));
        event.setEventMeta(mergeEventMeta(request.getEventMetaJson(), request.getDatasetsIn(), request.getDatasetsOut()));
        event.setStatus(defaultIfBlank(request.getStatus(), "succeed"));
        event.setOperatorId(request.getOperatorId());
        lineageEventMapper.insert(event);
        try {
            syncMaterializedLineageForTrace(request.getTraceId());
        } catch (Exception e) {
            // 物化 node/edge 失败不应回滚 lineage_event，否则智能分析成功但血缘全空、前端报系统异常
            log.error("syncMaterializedLineageForTrace failed, traceId={}", request.getTraceId(), e);
        }
    }

    @Override
    public void recordExplain(ExplainRecordRequest request) {
        if (request == null || StringUtils.isBlank(request.getTraceId()) || StringUtils.isBlank(request.getExplainType())) {
            return;
        }
        ExplainSnapshot snapshot = new ExplainSnapshot();
        snapshot.setTraceId(request.getTraceId());
        snapshot.setChartId(request.getChartId());
        snapshot.setExplainType(request.getExplainType());
        snapshot.setExplainData(defaultIfBlank(request.getExplainData(), "{}"));
        snapshot.setPromptVersion(request.getPromptVersion());
        snapshot.setModelName(request.getModelName());
        explainSnapshotMapper.insert(snapshot);
    }

    @Override
    public LineageTraceVO getTrace(String traceId) {
        if (StringUtils.isBlank(traceId)) {
            return null;
        }
        QueryWrapper<LineageEvent> eventQw = new QueryWrapper<>();
        eventQw.eq("traceId", traceId);
        eventQw.eq("isDelete", 0);
        eventQw.orderByAsc("createTime");
        List<LineageEvent> events = lineageEventMapper.selectList(eventQw);
        if (events == null || events.isEmpty()) {
            return null;
        }
        QueryWrapper<ExplainSnapshot> explainQw = new QueryWrapper<>();
        explainQw.eq("traceId", traceId);
        explainQw.eq("isDelete", 0);
        explainQw.orderByAsc("createTime");
        List<ExplainSnapshot> explanations = explainSnapshotMapper.selectList(explainQw);
        LineageTraceVO vo = new LineageTraceVO();
        vo.setTraceId(traceId);
        vo.setChartId(events.get(0).getChartId());
        vo.setEvents(events);
        vo.setExplanations(explanations == null ? Collections.emptyList() : explanations);
        fillBasisInfo(vo, events);
        return vo;
    }

    @Override
    public LineageTraceVO getLatestTraceByChartId(Long chartId) {
        if (chartId == null || chartId <= 0) {
            return null;
        }
        QueryWrapper<LineageEvent> latestQw = new QueryWrapper<>();
        latestQw.eq("chartId", chartId);
        latestQw.eq("isDelete", 0);
        latestQw.orderByDesc("createTime");
        latestQw.orderByDesc("id");
        latestQw.last("limit 1");
        LineageEvent latest = lineageEventMapper.selectOne(latestQw);
        if (latest == null || StringUtils.isBlank(latest.getTraceId())) {
            return null;
        }
        LineageTraceVO current = getTrace(latest.getTraceId());
        if (current == null) {
            return null;
        }
        LineageEvent previousLatest = getPreviousLatestTraceEvent(chartId, latest.getTraceId());
        if (previousLatest == null || StringUtils.isBlank(previousLatest.getTraceId())) {
            current.setDataChanged(null);
            current.setDifferenceReason("暂无法判断（需接入上一次快照）");
            return current;
        }
        LineageTraceVO previous = getTrace(previousLatest.getTraceId());
        String currentInputDigest = current.getDataFingerprint();
        String currentOutputDigest = current.getResultFingerprint();
        String previousInputDigest = previous == null ? null : previous.getDataFingerprint();
        String previousOutputDigest = previous == null ? null : previous.getResultFingerprint();
        if (StringUtils.isBlank(currentInputDigest) || StringUtils.isBlank(previousInputDigest)) {
            current.setDataChanged(null);
            current.setDifferenceReason("暂无法判断（缺少指纹数据）");
            return current;
        }
        boolean inputChanged = !Objects.equals(currentInputDigest, previousInputDigest);
        boolean outputChanged = !Objects.equals(defaultIfBlank(currentOutputDigest, ""), defaultIfBlank(previousOutputDigest, ""));
        current.setDataChanged(inputChanged);
        if (inputChanged) {
            current.setDifferenceReason("数据已变化（输入指纹与上一次不同）");
        } else if (outputChanged) {
            current.setDifferenceReason("数据未变化，但结果有变化（可能由提示词/模型变化导致）");
        } else {
            current.setDifferenceReason("与上一次一致（数据和结果指纹均相同）");
        }
        return current;
    }

    @Override
    public LineageGraphVO getAssetGraphByChartId(Long chartId, Integer depth) {
        if (chartId == null || chartId <= 0) {
            return null;
        }
        LineageTraceVO trace = getLatestTraceByChartId(chartId);
        if (trace == null || StringUtils.isBlank(trace.getTraceId())) {
            return null;
        }
        int graphDepth = depth == null || depth <= 0 ? 2 : Math.min(depth, 5);
        List<LineageEvent> events = trace.getEvents() == null ? Collections.emptyList() : trace.getEvents();
        if (events.isEmpty()) {
            return null;
        }
        LineageGraphVO materialized = loadMaterializedGraphVO(trace.getTraceId(), chartId);
        if (materialized != null && materialized.getEdges() != null && !materialized.getEdges().isEmpty()) {
            return materialized;
        }
        return buildDerivedAssetGraphVO(chartId, trace.getTraceId(), events, graphDepth);
    }

    /**
     * 与 {@link #getAssetGraphByChartId} 中「无物化数据时」的推导逻辑一致，供物化入库复用。
     */
    private LineageGraphVO buildDerivedAssetGraphVO(Long chartId, String traceId, List<LineageEvent> events, int graphDepth) {
        if (chartId == null || chartId <= 0 || StringUtils.isBlank(traceId) || events == null || events.isEmpty()) {
            return null;
        }
        Map<String, LineageGraphNodeVO> nodeMap = new LinkedHashMap<>();
        List<LineageGraphEdgeVO> edges = new ArrayList<>();

        String chartNodeKey = "chart:" + chartId;
        nodeMap.put(chartNodeKey, newNode(chartNodeKey, "chart", "图表 " + chartId, latestStatus(events)));

        String metricNodeKey = "metric:chart_goal:" + chartId;
        nodeMap.put(metricNodeKey, newNode(metricNodeKey, "metric", "指标口径（图表目标）", "active"));
        edges.add(newEdge(metricNodeKey, chartNodeKey, "use", "system", "high", "系统关系：指标被图表消费"));

        String previousTaskNodeKey = null;
        int taskCount = 0;
        for (LineageEvent event : events) {
            if (event == null || StringUtils.isBlank(event.getEventType())) {
                continue;
            }
            String taskNodeKey = "task:" + defaultIfBlank(event.getLogicRef(), event.getEventType()) + ":" + event.getEventType();
            if (!nodeMap.containsKey(taskNodeKey)) {
                if (taskCount >= graphDepth + 1) {
                    continue;
                }
                nodeMap.put(taskNodeKey, newNode(taskNodeKey, "task", eventLabel(event), defaultIfBlank(event.getStatus(), "unknown")));
                taskCount++;
            }
            if (StringUtils.isNotBlank(previousTaskNodeKey) && !Objects.equals(previousTaskNodeKey, taskNodeKey)) {
                edges.add(newEdge(previousTaskNodeKey, taskNodeKey, "derive", "system", "high", "系统关系：任务步骤顺序推导"));
            }
            previousTaskNodeKey = taskNodeKey;
        }
        if (StringUtils.isNotBlank(previousTaskNodeKey)) {
            edges.add(newEdge(previousTaskNodeKey, metricNodeKey, "derive", "system", "high", "系统关系：任务产出指标"));
        }

        Map<String, String> datasetsInEvidence = extractDatasetEvidence(events, true);
        Map<String, String> datasetsOutEvidence = extractDatasetEvidence(events, false);
        Set<String> datasetsIn = extractDatasetsIn(events);
        Set<String> datasetsOut = extractDatasetsOut(events);
        List<String> inputDatasetNames = datasetsIn.isEmpty()
                ? Collections.singletonList("chart_input_" + chartId)
                : new ArrayList<>(datasetsIn);
        for (String datasetName : inputDatasetNames) {
            String datasetNodeKey = "dataset:" + datasetName;
            nodeMap.put(datasetNodeKey, newNode(datasetNodeKey, "dataset", datasetName, "active"));
            String firstTaskNodeKey = "task:" + defaultIfBlank(events.get(0).getLogicRef(), events.get(0).getEventType()) + ":" + events.get(0).getEventType();
            String evidence = defaultIfBlank(datasetsInEvidence.get(datasetName), "text_parse");
            String confidence = "event_meta".equals(evidence) ? "high" : "medium";
            if (nodeMap.containsKey(firstTaskNodeKey)) {
                edges.add(newEdge(datasetNodeKey, firstTaskNodeKey, "read", evidence, confidence, buildEvidenceDetail(evidence, datasetName, true)));
            } else {
                edges.add(newEdge(datasetNodeKey, metricNodeKey, "derive", evidence, confidence, buildEvidenceDetail(evidence, datasetName, true)));
            }
        }
        if (!datasetsOut.isEmpty()) {
            String lastNodeKey = StringUtils.isNotBlank(previousTaskNodeKey) ? previousTaskNodeKey : metricNodeKey;
            for (String datasetName : datasetsOut) {
                String datasetNodeKey = "dataset:" + datasetName;
                nodeMap.put(datasetNodeKey, newNode(datasetNodeKey, "dataset", datasetName, "active"));
                String evidence = defaultIfBlank(datasetsOutEvidence.get(datasetName), "text_parse");
                String confidence = "event_meta".equals(evidence) ? "high" : "medium";
                edges.add(newEdge(lastNodeKey, datasetNodeKey, "write", evidence, confidence, buildEvidenceDetail(evidence, datasetName, false)));
            }
        }

        LineageGraphVO graphVO = new LineageGraphVO();
        graphVO.setTraceId(traceId);
        graphVO.setChartId(chartId);
        graphVO.setNodes(new ArrayList<>(nodeMap.values()));
        graphVO.setEdges(edges);
        return graphVO;
    }

    /**
     * 每次写入 lineage_event 后，按当前 trace 全量重算并落库 node/edge/run（runId 与 traceId 对齐）。
     */
    private void syncMaterializedLineageForTrace(String traceId) {
        if (StringUtils.isBlank(traceId)) {
            return;
        }
        QueryWrapper<LineageEvent> eventQw = new QueryWrapper<>();
        eventQw.eq("traceId", traceId);
        eventQw.eq("isDelete", 0);
        eventQw.orderByAsc("createTime");
        eventQw.orderByAsc("id");
        List<LineageEvent> events = lineageEventMapper.selectList(eventQw);
        if (events == null || events.isEmpty()) {
            return;
        }
        Long anchorChartId = resolveAnchorChartId(events);
        if (anchorChartId == null || anchorChartId <= 0) {
            return;
        }
        LineageGraphVO graph = buildDerivedAssetGraphVO(anchorChartId, traceId, events, MATERIALIZE_GRAPH_DEPTH);
        if (graph == null || graph.getEdges() == null || graph.getEdges().isEmpty()) {
            return;
        }
        persistMaterializedGraph(graph, traceId, anchorChartId, events);
    }

    private Long resolveAnchorChartId(List<LineageEvent> events) {
        Long last = null;
        for (LineageEvent event : events) {
            if (event != null && event.getChartId() != null && event.getChartId() > 0) {
                last = event.getChartId();
            }
        }
        return last;
    }

    private void persistMaterializedGraph(LineageGraphVO graph, String traceId, Long chartId, List<LineageEvent> events) {
        upsertLineageRun(traceId, chartId, events);
        for (LineageGraphNodeVO node : graph.getNodes()) {
            if (node == null || StringUtils.isBlank(node.getNodeKey())) {
                continue;
            }
            upsertLineageNode(node, chartId);
        }
        softDeleteEdgesForRun(traceId);
        Date now = new Date();
        for (LineageGraphEdgeVO edge : graph.getEdges()) {
            if (edge == null || StringUtils.isBlank(edge.getFromNodeKey()) || StringUtils.isBlank(edge.getToNodeKey())) {
                continue;
            }
            LineageEdge row = new LineageEdge();
            row.setFromNodeKey(edge.getFromNodeKey());
            row.setToNodeKey(edge.getToNodeKey());
            row.setEdgeType(defaultIfBlank(edge.getEdgeType(), "derive"));
            row.setRunId(traceId);
            row.setMetaJson(edgeToMetaJson(edge));
            row.setCreateTime(now);
            row.setUpdateTime(now);
            lineageEdgeMapper.insert(row);
        }
    }

    private void upsertLineageRun(String traceId, Long chartId, List<LineageEvent> events) {
        LineageEvent first = events.get(0);
        LineageEvent last = events.get(events.size() - 1);
        QueryWrapper<LineageRun> qw = new QueryWrapper<>();
        qw.eq("runId", traceId);
        qw.eq("isDelete", 0);
        LineageRun existing = lineageRunMapper.selectOne(qw);
        LineageRun run = new LineageRun();
        run.setRunId(traceId);
        run.setTraceId(traceId);
        run.setChartId(chartId);
        run.setStatus(defaultIfBlank(last.getStatus(), "succeed"));
        run.setStartTime(first.getCreateTime());
        run.setEndTime(last.getCreateTime());
        JsonObject meta = new JsonObject();
        meta.addProperty("eventCount", events.size());
        meta.addProperty("source", "lineage_event_sync");
        run.setMetaJson(GSON.toJson(meta));
        if (existing != null) {
            run.setId(existing.getId());
            lineageRunMapper.updateById(run);
        } else {
            lineageRunMapper.insert(run);
        }
    }

    private void upsertLineageNode(LineageGraphNodeVO vo, Long runChartId) {
        QueryWrapper<LineageNode> qw = new QueryWrapper<>();
        qw.eq("nodeKey", vo.getNodeKey());
        qw.eq("isDelete", 0);
        LineageNode existing = lineageNodeMapper.selectOne(qw);
        LineageNode n = new LineageNode();
        n.setNodeKey(vo.getNodeKey());
        n.setNodeType(defaultIfBlank(vo.getNodeType(), "unknown"));
        n.setName(defaultIfBlank(vo.getName(), vo.getNodeKey()));
        n.setChartId(resolveNodeChartIdForPersist(vo.getNodeKey(), runChartId));
        JsonObject meta = new JsonObject();
        meta.addProperty("status", defaultIfBlank(vo.getStatus(), "active"));
        n.setMetaJson(GSON.toJson(meta));
        if (existing != null) {
            n.setId(existing.getId());
            lineageNodeMapper.updateById(n);
        } else {
            lineageNodeMapper.insert(n);
        }
    }

    private Long resolveNodeChartIdForPersist(String nodeKey, Long runChartId) {
        if (StringUtils.isBlank(nodeKey)) {
            return runChartId;
        }
        if (nodeKey.startsWith("chart:")) {
            try {
                return Long.parseLong(nodeKey.substring("chart:".length()));
            } catch (NumberFormatException ignored) {
                return runChartId;
            }
        }
        if (nodeKey.startsWith("metric:chart_goal:")) {
            try {
                return Long.parseLong(nodeKey.substring("metric:chart_goal:".length()));
            } catch (NumberFormatException ignored) {
                return runChartId;
            }
        }
        return runChartId;
    }

    private void softDeleteEdgesForRun(String traceId) {
        LineageEdge patch = new LineageEdge();
        patch.setIsDelete(1);
        UpdateWrapper<LineageEdge> uw = new UpdateWrapper<>();
        uw.eq("runId", traceId);
        uw.eq("isDelete", 0);
        lineageEdgeMapper.update(patch, uw);
    }

    private String edgeToMetaJson(LineageGraphEdgeVO edge) {
        JsonObject o = new JsonObject();
        if (StringUtils.isNotBlank(edge.getEvidenceSource())) {
            o.addProperty("evidenceSource", edge.getEvidenceSource());
        }
        if (StringUtils.isNotBlank(edge.getConfidence())) {
            o.addProperty("confidence", edge.getConfidence());
        }
        if (StringUtils.isNotBlank(edge.getEvidenceDetail())) {
            o.addProperty("evidenceDetail", edge.getEvidenceDetail());
        }
        if (StringUtils.isNotBlank(edge.getFixSuggestion())) {
            o.addProperty("fixSuggestion", edge.getFixSuggestion());
        }
        if (StringUtils.isNotBlank(edge.getEventMetaTemplate())) {
            o.addProperty("eventMetaTemplate", edge.getEventMetaTemplate());
        }
        return o.size() > 0 ? GSON.toJson(o) : null;
    }

    private LineageGraphVO loadMaterializedGraphVO(String traceId, Long chartId) {
        if (StringUtils.isBlank(traceId)) {
            return null;
        }
        QueryWrapper<LineageEdge> edgeQw = new QueryWrapper<>();
        edgeQw.eq("runId", traceId);
        edgeQw.eq("isDelete", 0);
        List<LineageEdge> edgeRows = lineageEdgeMapper.selectList(edgeQw);
        if (edgeRows == null || edgeRows.isEmpty()) {
            return null;
        }
        Set<String> nodeKeys = new LinkedHashSet<>();
        for (LineageEdge e : edgeRows) {
            if (e == null) {
                continue;
            }
            if (StringUtils.isNotBlank(e.getFromNodeKey())) {
                nodeKeys.add(e.getFromNodeKey());
            }
            if (StringUtils.isNotBlank(e.getToNodeKey())) {
                nodeKeys.add(e.getToNodeKey());
            }
        }
        Map<String, LineageGraphNodeVO> nodeMap = new LinkedHashMap<>();
        if (!nodeKeys.isEmpty()) {
            QueryWrapper<LineageNode> nodeQw = new QueryWrapper<>();
            nodeQw.in("nodeKey", nodeKeys);
            nodeQw.eq("isDelete", 0);
            List<LineageNode> nodeRows = lineageNodeMapper.selectList(nodeQw);
            if (nodeRows != null) {
                for (LineageNode row : nodeRows) {
                    if (row == null || StringUtils.isBlank(row.getNodeKey())) {
                        continue;
                    }
                    nodeMap.put(row.getNodeKey(), lineageNodeToVo(row));
                }
            }
        }
        for (String key : nodeKeys) {
            if (!nodeMap.containsKey(key)) {
                nodeMap.put(key, placeholderNodeVo(key));
            }
        }
        List<LineageGraphEdgeVO> edges = new ArrayList<>();
        for (LineageEdge row : edgeRows) {
            if (row == null) {
                continue;
            }
            edges.add(lineageEdgeRowToVo(row));
        }
        LineageGraphVO vo = new LineageGraphVO();
        vo.setTraceId(traceId);
        vo.setChartId(chartId);
        vo.setNodes(new ArrayList<>(nodeMap.values()));
        vo.setEdges(edges);
        return vo;
    }

    private LineageGraphNodeVO lineageNodeToVo(LineageNode row) {
        LineageGraphNodeVO vo = new LineageGraphNodeVO();
        vo.setNodeKey(row.getNodeKey());
        vo.setNodeType(defaultIfBlank(row.getNodeType(), "unknown"));
        vo.setName(defaultIfBlank(row.getName(), row.getNodeKey()));
        vo.setStatus(parseNodeStatusFromMeta(row.getMetaJson()));
        return vo;
    }

    private String parseNodeStatusFromMeta(String metaJson) {
        if (StringUtils.isBlank(metaJson)) {
            return "active";
        }
        try {
            JsonElement el = JsonParser.parseString(metaJson);
            if (el != null && el.isJsonObject()) {
                JsonElement st = el.getAsJsonObject().get("status");
                if (st != null && st.isJsonPrimitive()) {
                    return st.getAsString();
                }
            }
        } catch (Exception ignored) {
        }
        return "active";
    }

    private LineageGraphNodeVO placeholderNodeVo(String nodeKey) {
        LineageGraphNodeVO vo = new LineageGraphNodeVO();
        vo.setNodeKey(nodeKey);
        if (nodeKey.startsWith("dataset:")) {
            vo.setNodeType("dataset");
            vo.setName(nodeKey.substring("dataset:".length()));
        } else if (nodeKey.startsWith("task:")) {
            vo.setNodeType("task");
            vo.setName("任务节点");
        } else if (nodeKey.startsWith("chart:")) {
            vo.setNodeType("chart");
            vo.setName(nodeKey);
        } else if (nodeKey.startsWith("metric:")) {
            vo.setNodeType("metric");
            vo.setName("指标");
        } else {
            vo.setNodeType("unknown");
            vo.setName(nodeKey);
        }
        vo.setStatus("active");
        return vo;
    }

    private LineageGraphEdgeVO lineageEdgeRowToVo(LineageEdge row) {
        LineageGraphEdgeVO vo = new LineageGraphEdgeVO();
        vo.setFromNodeKey(row.getFromNodeKey());
        vo.setToNodeKey(row.getToNodeKey());
        vo.setEdgeType(row.getEdgeType());
        if (StringUtils.isBlank(row.getMetaJson())) {
            vo.setEvidenceSource("system");
            vo.setConfidence("high");
            return vo;
        }
        try {
            JsonElement el = JsonParser.parseString(row.getMetaJson());
            if (el != null && el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                if (o.has("evidenceSource") && o.get("evidenceSource").isJsonPrimitive()) {
                    vo.setEvidenceSource(o.get("evidenceSource").getAsString());
                }
                if (o.has("confidence") && o.get("confidence").isJsonPrimitive()) {
                    vo.setConfidence(o.get("confidence").getAsString());
                }
                if (o.has("evidenceDetail") && o.get("evidenceDetail").isJsonPrimitive()) {
                    vo.setEvidenceDetail(o.get("evidenceDetail").getAsString());
                }
                if (o.has("fixSuggestion") && o.get("fixSuggestion").isJsonPrimitive()) {
                    vo.setFixSuggestion(o.get("fixSuggestion").getAsString());
                }
                if (o.has("eventMetaTemplate") && o.get("eventMetaTemplate").isJsonPrimitive()) {
                    vo.setEventMetaTemplate(o.get("eventMetaTemplate").getAsString());
                }
            }
        } catch (Exception ignored) {
            vo.setEvidenceSource("system");
            vo.setConfidence("medium");
        }
        if (StringUtils.isBlank(vo.getEvidenceSource())) {
            vo.setEvidenceSource("system");
        }
        if (StringUtils.isBlank(vo.getConfidence())) {
            vo.setConfidence("high");
        }
        if (StringUtils.isBlank(vo.getFixSuggestion())) {
            vo.setFixSuggestion(buildFixSuggestion(vo.getEvidenceSource(), vo.getFromNodeKey(), vo.getToNodeKey(), vo.getEdgeType()));
        }
        if (StringUtils.isBlank(vo.getEventMetaTemplate())) {
            vo.setEventMetaTemplate(buildEventMetaTemplate(vo.getEvidenceSource(), vo.getFromNodeKey(), vo.getToNodeKey(), vo.getEdgeType()));
        }
        return vo;
    }

    @Override
    public LineageImpactVO getImpactByChartId(Long chartId) {
        LineageImpactVO impactVO = new LineageImpactVO();
        impactVO.setSourceChartId(chartId);
        if (chartId == null || chartId <= 0) {
            impactVO.setImpactedCount(0);
            impactVO.setImpactedCharts(Collections.emptyList());
            return impactVO;
        }
        LineageTraceVO sourceTrace = getLatestTraceByChartId(chartId);
        Set<String> sourceDatasets = sourceTrace == null ? Collections.emptySet() : extractDatasetsIn(sourceTrace.getEvents());
        String sourceInputFingerprint = sourceTrace == null ? null : sourceTrace.getDataFingerprint();
        Chart sourceChart = chartMapper.selectById(chartId);
        ChartBusinessScope sourceScope = parseChartBusinessScope(sourceChart);
        if (sourceDatasets.isEmpty()) {
            sourceDatasets = Collections.emptySet();
        }

        QueryWrapper<LineageEvent> chartQw = new QueryWrapper<>();
        chartQw.select("chartId");
        chartQw.isNotNull("chartId");
        chartQw.eq("isDelete", 0);
        chartQw.groupBy("chartId");
        List<LineageEvent> chartEvents = lineageEventMapper.selectList(chartQw);
        if (chartEvents == null || chartEvents.isEmpty()) {
            impactVO.setImpactedCount(0);
            impactVO.setImpactedCharts(Collections.emptyList());
            return impactVO;
        }
        List<LineageImpactItemVO> impacted = new ArrayList<>();
        for (LineageEvent chartEvent : chartEvents) {
            if (chartEvent == null || chartEvent.getChartId() == null || Objects.equals(chartEvent.getChartId(), chartId)) {
                continue;
            }
            LineageTraceVO trace = getLatestTraceByChartId(chartEvent.getChartId());
            Set<String> candidateDatasets = trace == null ? Collections.emptySet() : extractDatasetsIn(trace.getEvents());
            Set<String> intersection = new LinkedHashSet<>();
            if (!candidateDatasets.isEmpty() && !sourceDatasets.isEmpty()) {
                intersection.addAll(candidateDatasets);
                intersection.retainAll(sourceDatasets);
            }
            String candidateInputFingerprint = trace == null ? null : trace.getDataFingerprint();
            boolean sameInputFingerprint = StringUtils.isNotBlank(sourceInputFingerprint)
                    && StringUtils.equals(sourceInputFingerprint, candidateInputFingerprint);
            Chart candidateChart = chartMapper.selectById(chartEvent.getChartId());
            ChartBusinessScope candidateScope = parseChartBusinessScope(candidateChart);
            boolean businessScopeOverlap = isBusinessScopeOverlap(sourceScope, candidateScope);
            if (intersection.isEmpty() && !sameInputFingerprint && !businessScopeOverlap) {
                continue;
            }
            LineageImpactItemVO itemVO = new LineageImpactItemVO();
            itemVO.setChartId(chartEvent.getChartId());
            itemVO.setChartName(candidateChart == null ? ("图表 " + chartEvent.getChartId()) : defaultIfBlank(candidateChart.getName(), "图表 " + chartEvent.getChartId()));
            List<String> reasons = new ArrayList<>();
            if (!intersection.isEmpty()) {
                reasons.add("共享数据集：" + String.join(", ", intersection));
            }
            if (sameInputFingerprint) {
                reasons.add("输入数据指纹一致（同源数据）");
            }
            if (businessScopeOverlap) {
                reasons.add("业务范围重叠（时间区间+门店集合）");
            }
            itemVO.setReason(String.join("；", reasons));
            impacted.add(itemVO);
        }
        impacted.sort(Comparator.comparing(LineageImpactItemVO::getChartId));
        impactVO.setImpactedCharts(impacted);
        impactVO.setImpactedCount(impacted.size());
        return impactVO;
    }

    @Override
    public List<LineageFixTaskVO> getFixTasksByChartId(Long chartId) {
        if (chartId == null || chartId <= 0) {
            return Collections.emptyList();
        }
        LineageGraphVO graph = getAssetGraphByChartId(chartId, 2);
        if (graph == null || graph.getEdges() == null) {
            return Collections.emptyList();
        }
        for (LineageGraphEdgeVO edge : graph.getEdges()) {
            if (edge == null || "high".equals(edge.getConfidence())) {
                continue;
            }
            upsertFixTask(chartId, edge);
        }
        QueryWrapper<LineageFixTask> taskQw = new QueryWrapper<>();
        taskQw.eq("chartId", chartId);
        taskQw.eq("isDelete", 0);
        taskQw.orderByDesc("updateTime");
        List<LineageFixTask> tasks = lineageFixTaskMapper.selectList(taskQw);
        List<LineageFixTaskVO> result = new ArrayList<>();
        for (LineageFixTask task : tasks) {
            if (task == null) {
                continue;
            }
            LineageFixTaskVO vo = new LineageFixTaskVO();
            vo.setId(task.getId());
            vo.setChartId(task.getChartId());
            vo.setFromNodeKey(task.getFromNodeKey());
            vo.setToNodeKey(task.getToNodeKey());
            vo.setEdgeType(task.getEdgeType());
            vo.setEvidenceSource(task.getEvidenceSource());
            vo.setConfidence(task.getConfidence());
            vo.setFixSuggestion(task.getFixSuggestion());
            vo.setEventMetaTemplate(task.getEventMetaTemplate());
            vo.setStatus(defaultIfBlank(task.getStatus(), "pending"));
            result.add(vo);
        }
        return result;
    }

    @Override
    public boolean updateFixTaskStatus(Long taskId, String status) {
        if (taskId == null || taskId <= 0 || StringUtils.isBlank(status)) {
            return false;
        }
        LineageFixTask task = lineageFixTaskMapper.selectById(taskId);
        if (task == null || task.getIsDelete() != null && task.getIsDelete() == 1) {
            return false;
        }
        LineageFixTask update = new LineageFixTask();
        update.setId(taskId);
        update.setStatus(status);
        return lineageFixTaskMapper.updateById(update) > 0;
    }

    private void fillBasisInfo(LineageTraceVO vo, List<LineageEvent> events) {
        if (vo == null || events == null || events.isEmpty()) {
            return;
        }
        LineageEvent latest = events.get(events.size() - 1);
        Date start = null;
        Date end = null;
        for (LineageEvent event : events) {
            if (event == null || event.getCreateTime() == null) {
                continue;
            }
            if (start == null || event.getCreateTime().before(start)) {
                start = event.getCreateTime();
            }
            if (end == null || event.getCreateTime().after(end)) {
                end = event.getCreateTime();
            }
        }
        vo.setDataTimeRange(formatRange(start, end));
        vo.setDataFingerprint(defaultIfBlank(latest.getInputDigest(), null));
        vo.setResultFingerprint(defaultIfBlank(latest.getOutputDigest(), null));
    }

    private String formatRange(Date start, Date end) {
        if (start == null || end == null) {
            return null;
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(start) + " ~ " + sdf.format(end);
    }

    private LineageEvent getPreviousLatestTraceEvent(Long chartId, String currentTraceId) {
        QueryWrapper<LineageEvent> previousQw = new QueryWrapper<>();
        previousQw.eq("chartId", chartId);
        previousQw.eq("isDelete", 0);
        previousQw.ne("traceId", currentTraceId);
        previousQw.orderByDesc("createTime");
        previousQw.orderByDesc("id");
        previousQw.last("limit 1");
        return lineageEventMapper.selectOne(previousQw);
    }

    private LineageGraphNodeVO newNode(String nodeKey, String nodeType, String name, String status) {
        LineageGraphNodeVO nodeVO = new LineageGraphNodeVO();
        nodeVO.setNodeKey(nodeKey);
        nodeVO.setNodeType(nodeType);
        nodeVO.setName(name);
        nodeVO.setStatus(status);
        return nodeVO;
    }

    private LineageGraphEdgeVO newEdge(String fromNodeKey, String toNodeKey, String edgeType, String evidenceSource, String confidence, String evidenceDetail) {
        LineageGraphEdgeVO edgeVO = new LineageGraphEdgeVO();
        edgeVO.setFromNodeKey(fromNodeKey);
        edgeVO.setToNodeKey(toNodeKey);
        edgeVO.setEdgeType(edgeType);
        edgeVO.setEvidenceSource(evidenceSource);
        edgeVO.setConfidence(confidence);
        edgeVO.setEvidenceDetail(evidenceDetail);
        edgeVO.setFixSuggestion(buildFixSuggestion(evidenceSource, fromNodeKey, toNodeKey, edgeType));
        edgeVO.setEventMetaTemplate(buildEventMetaTemplate(evidenceSource, fromNodeKey, toNodeKey, edgeType));
        return edgeVO;
    }

    private String buildEvidenceDetail(String evidence, String datasetName, boolean input) {
        String ioType = input ? "输入" : "输出";
        if ("event_meta".equals(evidence)) {
            return ioType + "数据集 `" + datasetName + "` 来自 eventMeta 结构化字段 datasetsIn/datasetsOut。";
        }
        if ("text_parse".equals(evidence)) {
            return ioType + "数据集 `" + datasetName + "` 由文本规则解析得到（SQL 关键字或键值匹配）。";
        }
        return "系统内置关系推导。";
    }

    private String buildFixSuggestion(String evidenceSource, String fromNodeKey, String toNodeKey, String edgeType) {
        if (!"text_parse".equals(evidenceSource)) {
            return "当前边可信度较高，无需额外修复。";
        }
        return "建议在上游记录血缘事件时写入结构化 eventMeta（datasetsIn/datasetsOut），"
                + "并确保 `" + fromNodeKey + " -> " + toNodeKey + "` 这条 `" + edgeType + "` 关系来自明确字段而非文本推断。";
    }

    private String buildEventMetaTemplate(String evidenceSource, String fromNodeKey, String toNodeKey, String edgeType) {
        if (!"text_parse".equals(evidenceSource)) {
            return "";
        }
        String fromDataset = fromNodeKey.startsWith("dataset:") ? fromNodeKey.substring("dataset:".length()) : "";
        String toDataset = toNodeKey.startsWith("dataset:") ? toNodeKey.substring("dataset:".length()) : "";
        JsonObject template = new JsonObject();
        template.addProperty("edgeHint", fromNodeKey + " -> " + toNodeKey + " (" + edgeType + ")");
        JsonArray datasetsIn = new JsonArray();
        JsonArray datasetsOut = new JsonArray();
        if ("read".equals(edgeType) || "derive".equals(edgeType)) {
            if (StringUtils.isNotBlank(fromDataset)) {
                datasetsIn.add(fromDataset);
            }
        }
        if ("write".equals(edgeType)) {
            if (StringUtils.isNotBlank(toDataset)) {
                datasetsOut.add(toDataset);
            }
        }
        template.add("datasetsIn", datasetsIn);
        template.add("datasetsOut", datasetsOut);
        template.addProperty("note", "请在 recordLineage 时通过 req.setEventMetaJson(...) 写入该结构");
        return GSON.toJson(template);
    }

    private void upsertFixTask(Long chartId, LineageGraphEdgeVO edge) {
        if (chartId == null || edge == null) {
            return;
        }
        String taskKey = digest(chartId + "|" + defaultIfBlank(edge.getFromNodeKey(), "") + "|" +
                defaultIfBlank(edge.getToNodeKey(), "") + "|" + defaultIfBlank(edge.getEdgeType(), ""));
        QueryWrapper<LineageFixTask> qw = new QueryWrapper<>();
        qw.eq("taskKey", taskKey);
        qw.eq("isDelete", 0);
        LineageFixTask existing = lineageFixTaskMapper.selectOne(qw);
        if (existing != null) {
            LineageFixTask update = new LineageFixTask();
            update.setId(existing.getId());
            update.setFixSuggestion(edge.getFixSuggestion());
            update.setEventMetaTemplate(edge.getEventMetaTemplate());
            update.setConfidence(edge.getConfidence());
            update.setEvidenceSource(edge.getEvidenceSource());
            lineageFixTaskMapper.updateById(update);
            return;
        }
        LineageFixTask task = new LineageFixTask();
        task.setChartId(chartId);
        task.setTaskKey(taskKey);
        task.setFromNodeKey(edge.getFromNodeKey());
        task.setToNodeKey(edge.getToNodeKey());
        task.setEdgeType(edge.getEdgeType());
        task.setEvidenceSource(edge.getEvidenceSource());
        task.setConfidence(edge.getConfidence());
        task.setFixSuggestion(edge.getFixSuggestion());
        task.setEventMetaTemplate(edge.getEventMetaTemplate());
        task.setStatus("pending");
        lineageFixTaskMapper.insert(task);
    }

    private String eventLabel(LineageEvent event) {
        String eventType = event == null ? null : event.getEventType();
        if (StringUtils.isBlank(eventType)) {
            return "任务节点";
        }
        if ("SOURCE_INGESTED".equals(eventType)) {
            return "数据接入";
        }
        if ("DATA_CLEANED".equals(eventType)) {
            return "数据清洗";
        }
        if ("METRIC_COMPUTED".equals(eventType)) {
            return "指标计算";
        }
        if ("CHART_RENDERED".equals(eventType)) {
            return "图表渲染";
        }
        if ("AI_INTERPRETED".equals(eventType)) {
            return "AI 解释";
        }
        return eventType;
    }

    private String latestStatus(List<LineageEvent> events) {
        if (events == null || events.isEmpty()) {
            return "unknown";
        }
        LineageEvent latest = events.get(events.size() - 1);
        return defaultIfBlank(latest.getStatus(), "unknown");
    }

    private Set<String> extractDatasetsIn(List<LineageEvent> events) {
        return extractDatasets(events, true);
    }

    private Set<String> extractDatasetsOut(List<LineageEvent> events) {
        return extractDatasets(events, false);
    }

    private Set<String> extractDatasets(List<LineageEvent> events, boolean readInput) {
        if (events == null || events.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> datasets = new LinkedHashSet<>();
        for (LineageEvent event : events) {
            if (event == null) {
                continue;
            }
            String eventMeta = defaultIfBlank(event.getEventMeta(), "");
            if (StringUtils.isNotBlank(eventMeta)) {
                Set<String> fromStructured = parseDatasetsFromEventMeta(eventMeta, readInput ? "datasetsIn" : "datasetsOut");
                if (!fromStructured.isEmpty()) {
                    datasets.addAll(fromStructured);
                }
                // 注意：eventMeta 中已包含结构化 datasetsIn/datasetsOut。
                // 若再用正则从 eventMeta 文本里解析，会把 outputs 误当成 inputs（或反之），导致出现 text_parse / medium 的伪边。
            }
            String logicRef = defaultIfBlank(event.getLogicRef(), "");
            if (StringUtils.isNotBlank(logicRef)) {
                datasets.addAll(parseDatasetFromText(logicRef));
            }
        }
        if (datasets.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> sanitized = new HashSet<>();
        for (String dataset : datasets) {
            String value = defaultIfBlank(dataset, "").replace("`", "").trim();
            if (StringUtils.isBlank(value)) {
                continue;
            }
            sanitized.add(value.toLowerCase());
        }
        return sanitized;
    }

    private Map<String, String> extractDatasetEvidence(List<LineageEvent> events, boolean readInput) {
        if (events == null || events.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> evidenceMap = new LinkedHashMap<>();
        String key = readInput ? "datasetsIn" : "datasetsOut";
        for (LineageEvent event : events) {
            if (event == null) {
                continue;
            }
            String eventMeta = defaultIfBlank(event.getEventMeta(), "");
            if (StringUtils.isNotBlank(eventMeta)) {
                Set<String> structured = parseDatasetsFromEventMeta(eventMeta, key);
                for (String dataset : structured) {
                    String clean = cleanDatasetName(defaultIfBlank(dataset, "")).toLowerCase();
                    if (StringUtils.isNotBlank(clean)) {
                        evidenceMap.put(clean, "event_meta");
                    }
                }
                // 同 extractDatasets：避免从 eventMeta 文本里二次正则解析，防止把 datasetsOut 误标为 text_parse。
            }
            String logicRef = defaultIfBlank(event.getLogicRef(), "");
            if (StringUtils.isNotBlank(logicRef)) {
                Set<String> parsed = parseDatasetFromText(logicRef);
                for (String dataset : parsed) {
                    String clean = cleanDatasetName(defaultIfBlank(dataset, "")).toLowerCase();
                    if (StringUtils.isNotBlank(clean) && !evidenceMap.containsKey(clean)) {
                        evidenceMap.put(clean, "text_parse");
                    }
                }
            }
        }
        return evidenceMap;
    }

    private Set<String> parseDatasetsFromEventMeta(String text, String key) {
        if (StringUtils.isBlank(text) || StringUtils.isBlank(key)) {
            return Collections.emptySet();
        }
        try {
            JsonElement element = JsonParser.parseString(text);
            if (element == null || !element.isJsonObject()) {
                return Collections.emptySet();
            }
            JsonObject object = element.getAsJsonObject();
            JsonElement datasetsElement = object.get(key);
            if (datasetsElement == null || !datasetsElement.isJsonArray()) {
                return Collections.emptySet();
            }
            Set<String> datasets = new LinkedHashSet<>();
            for (JsonElement item : datasetsElement.getAsJsonArray()) {
                if (item != null && item.isJsonPrimitive()) {
                    String value = cleanDatasetName(item.getAsString());
                    if (StringUtils.isNotBlank(value)) {
                        datasets.add(value);
                    }
                }
            }
            return datasets;
        } catch (Exception ignored) {
            return Collections.emptySet();
        }
    }

    private Set<String> parseDatasetFromText(String text) {
        if (StringUtils.isBlank(text)) {
            return Collections.emptySet();
        }
        Set<String> result = new LinkedHashSet<>();
        Pattern sqlPattern = Pattern.compile("(?i)(?:from|join|into|update|table)\\s+([a-zA-Z0-9_.`]+)");
        Matcher sqlMatcher = sqlPattern.matcher(text);
        while (sqlMatcher.find()) {
            String table = sqlMatcher.group(1);
            if (StringUtils.isNotBlank(table) && !isNoiseToken(table)) {
                result.add(cleanDatasetName(table));
            }
        }
        Pattern keyValuePattern = Pattern.compile("(?i)(?:dataset|table|tableName|sourceTable|targetTable)\\s*[\":=]+\\s*\"?([a-zA-Z0-9_.`]+)");
        Matcher keyValueMatcher = keyValuePattern.matcher(text);
        while (keyValueMatcher.find()) {
            String table = keyValueMatcher.group(1);
            if (StringUtils.isNotBlank(table) && !isNoiseToken(table)) {
                result.add(cleanDatasetName(table));
            }
        }
        Pattern arrayPattern = Pattern.compile("(?i)\"datasets(?:In|Out)\"\\s*:\\s*\\[(.*?)\\]");
        Matcher arrayMatcher = arrayPattern.matcher(text);
        while (arrayMatcher.find()) {
            String arrayBody = defaultIfBlank(arrayMatcher.group(1), "");
            Matcher valueMatcher = Pattern.compile("\"([a-zA-Z0-9_.`]+)\"").matcher(arrayBody);
            while (valueMatcher.find()) {
                String table = valueMatcher.group(1);
                if (StringUtils.isNotBlank(table) && !isNoiseToken(table)) {
                    result.add(cleanDatasetName(table));
                }
            }
        }
        return result;
    }

    private String mergeEventMeta(String eventMetaJson, List<String> datasetsIn, List<String> datasetsOut) {
        JsonObject root = parseJsonObject(eventMetaJson);
        addDatasets(root, "datasetsIn", datasetsIn);
        addDatasets(root, "datasetsOut", datasetsOut);
        return root.size() == 0 ? defaultIfBlank(eventMetaJson, null) : GSON.toJson(root);
    }

    private JsonObject parseJsonObject(String raw) {
        if (StringUtils.isBlank(raw)) {
            return new JsonObject();
        }
        try {
            JsonElement element = JsonParser.parseString(raw);
            if (element != null && element.isJsonObject()) {
                return element.getAsJsonObject();
            }
        } catch (Exception ignored) {
            // ignore
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("rawMeta", defaultIfBlank(raw, ""));
        return obj;
    }

    private void addDatasets(JsonObject root, String key, List<String> datasets) {
        if (root == null || datasets == null || datasets.isEmpty()) {
            return;
        }
        JsonArray array = new JsonArray();
        for (String dataset : datasets) {
            String clean = cleanDatasetName(dataset);
            if (StringUtils.isNotBlank(clean)) {
                array.add(clean.toLowerCase());
            }
        }
        if (array.size() > 0) {
            root.add(key, array);
        }
    }

    private boolean isNoiseToken(String token) {
        if (StringUtils.isBlank(token)) {
            return true;
        }
        String lower = token.toLowerCase();
        return "select".equals(lower) || "where".equals(lower) || "set".equals(lower) || "on".equals(lower);
    }

    private String cleanDatasetName(String raw) {
        if (StringUtils.isBlank(raw)) {
            return raw;
        }
        String value = raw.trim();
        while (value.endsWith(",") || value.endsWith(";")) {
            value = value.substring(0, value.length() - 1);
        }
        value = value.replace("`", "");
        return value;
    }

    private String digest(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String hex = Integer.toHexString(b & 0xff);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private String defaultIfBlank(String s, String dft) {
        return StringUtils.isBlank(s) ? dft : s.trim();
    }

    private ChartBusinessScope parseChartBusinessScope(Chart chart) {
        ChartBusinessScope scope = new ChartBusinessScope();
        if (chart == null || StringUtils.isBlank(chart.getChartData())) {
            return scope;
        }
        CsvTableUtils.Table table = CsvTableUtils.parse(chart.getChartData());
        if (table.getHeaders().isEmpty() || table.getRows().isEmpty()) {
            return scope;
        }
        int dateIdx = table.indexOfHeaderLike("date", "日期", "statDate", "day");
        int storeIdIdx = table.indexOfHeaderLike("storeId", "门店id", "门店ID");
        int storeCodeIdx = table.indexOfHeaderLike("storeCode", "门店编码");
        int storeNameIdx = table.indexOfHeaderLike("storeName", "门店名称", "门店");
        for (List<String> row : table.getRows()) {
            LocalDate date = parseDate(getCell(row, dateIdx));
            if (date != null) {
                if (scope.minDate == null || date.isBefore(scope.minDate)) {
                    scope.minDate = date;
                }
                if (scope.maxDate == null || date.isAfter(scope.maxDate)) {
                    scope.maxDate = date;
                }
            }
            String storeKey = buildStoreKey(getCell(row, storeIdIdx), getCell(row, storeCodeIdx), getCell(row, storeNameIdx));
            if (StringUtils.isNotBlank(storeKey)) {
                scope.storeKeys.add(storeKey);
            }
        }
        return scope;
    }

    private boolean isBusinessScopeOverlap(ChartBusinessScope a, ChartBusinessScope b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.minDate == null || a.maxDate == null || b.minDate == null || b.maxDate == null) {
            return false;
        }
        boolean timeOverlap = !a.maxDate.isBefore(b.minDate) && !b.maxDate.isBefore(a.minDate);
        if (!timeOverlap) {
            return false;
        }
        if (a.storeKeys.isEmpty() || b.storeKeys.isEmpty()) {
            return false;
        }
        Set<String> intersection = new HashSet<>(a.storeKeys);
        intersection.retainAll(b.storeKeys);
        return !intersection.isEmpty();
    }

    private String buildStoreKey(String storeId, String storeCode, String storeName) {
        String id = defaultIfBlank(storeId, "");
        if (StringUtils.isNotBlank(id)) {
            return "id:" + id;
        }
        String code = defaultIfBlank(storeCode, "");
        if (StringUtils.isNotBlank(code)) {
            return "code:" + code.toLowerCase();
        }
        String name = defaultIfBlank(storeName, "");
        if (StringUtils.isNotBlank(name)) {
            return "name:" + name.toLowerCase();
        }
        return null;
    }

    private LocalDate parseDate(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        String text = raw.trim();
        try {
            return LocalDate.parse(text, DATE_FORMATTER);
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(text);
        } catch (Exception ignored) {
        }
        if (text.contains("/")) {
            try {
                return LocalDate.parse(text.replace('/', '-'));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String getCell(List<String> row, int idx) {
        if (row == null || idx < 0 || idx >= row.size()) {
            return "";
        }
        return defaultIfBlank(row.get(idx), "");
    }

    private static class ChartBusinessScope {
        private LocalDate minDate;
        private LocalDate maxDate;
        private final Set<String> storeKeys = new HashSet<>();
    }
}
