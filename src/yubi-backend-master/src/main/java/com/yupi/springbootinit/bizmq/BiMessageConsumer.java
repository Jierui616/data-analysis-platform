package com.yupi.springbootinit.bizmq;

import com.rabbitmq.client.Channel;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.constant.CommonConstant;
import com.yupi.springbootinit.constant.LineageConstant;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.manager.AiManager;
import com.yupi.springbootinit.model.dto.lineage.ExplainRecordRequest;
import com.yupi.springbootinit.model.dto.lineage.LineageRecordRequest;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.service.ChartService;
import com.yupi.springbootinit.service.LineageService;
import com.yupi.springbootinit.utils.BiAiResultParser;
import com.yupi.springbootinit.utils.BiPromptUtils;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class BiMessageConsumer {

    @Resource
    private ChartService chartService;

    @Resource
    private AiManager aiManager;

    @Resource
    private LineageService lineageService;

    // 指定程序监听的消息队列和确认机制
    @SneakyThrows
    @RabbitListener(queues = {BiMqConstant.BI_QUEUE_NAME}, ackMode = "MANUAL")
    public void receiveMessage(String message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        log.info("receiveMessage message = {}", message);
        if (StringUtils.isBlank(message)) {
            // 如果失败，消息拒绝
            channel.basicNack(deliveryTag, false, false);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "消息为空");
        }
        String[] parts = StringUtils.split(message, "|");
        long chartId = Long.parseLong(parts[0]);
        String traceId = (parts != null && parts.length > 1 && StringUtils.isNotBlank(parts[1]))
                ? parts[1]
                : lineageService.newTraceId();
        Chart chart = chartService.getById(chartId);
        if (chart == null) {
            channel.basicNack(deliveryTag, false, false);
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图表为空");
        }
        // 先修改图表任务状态为 “执行中”。等执行成功后，修改为 “已完成”、保存执行结果；执行失败后，状态修改为 “失败”，记录任务失败信息。
        Chart updateChart = new Chart();
        updateChart.setId(chart.getId());
        updateChart.setStatus("running");
        boolean b = chartService.updateById(updateChart);
        if (!b) {
            channel.basicNack(deliveryTag, false, false);
            handleChartUpdateError(chart.getId(), "更新图表执行中状态失败");
            recordLineage(traceId, chartId, LineageConstant.EVENT_METRIC_COMPUTED, chart.getGoal(), "running",
                    "mq_running_v1", "failed", chart.getUserId());
            return;
        }
        recordLineage(traceId, chartId, LineageConstant.EVENT_METRIC_COMPUTED, chart.getGoal(), chart.getChartType(),
                "mq_running_v1", "succeed", chart.getUserId());
        // 调用 AI
        String userInput = buildUserInput(chart);
        String result = aiManager.doChat(CommonConstant.BI_MODEL_ID, userInput);
        BiAiResultParser.Parsed parsed;
        try {
            parsed = BiAiResultParser.parse(result);
        } catch (BusinessException e) {
            log.warn("chartId={} {}", chart.getId(), e.getMessage());
            channel.basicNack(deliveryTag, false, false);
            handleChartUpdateError(chart.getId(), e.getMessage());
            recordLineage(traceId, chartId, LineageConstant.EVENT_AI_INTERPRETED, userInput, e.getMessage(),
                    "chart_ai_prompt_v1", "failed", chart.getUserId());
            return;
        }
        String genChart = parsed.getGenChart();
        String genResult = parsed.getGenResult();
        Chart updateChartResult = new Chart();
        updateChartResult.setId(chart.getId());
        updateChartResult.setGenChart(genChart);
        updateChartResult.setGenResult(genResult);
        // todo 建议定义状态为枚举值
        updateChartResult.setStatus("succeed");
        boolean updateResult = chartService.updateById(updateChartResult);
        if (!updateResult) {
            channel.basicNack(deliveryTag, false, false);
            handleChartUpdateError(chart.getId(), "更新图表成功状态失败");
            recordLineage(traceId, chartId, LineageConstant.EVENT_CHART_RENDERED, userInput, "更新图表成功状态失败",
                    "chart_option_v1", "failed", chart.getUserId());
            return;
        }
        recordLineage(traceId, chartId, LineageConstant.EVENT_CHART_RENDERED, userInput, genChart,
                "chart_option_v1", "succeed", chart.getUserId());
        recordLineage(traceId, chartId, LineageConstant.EVENT_AI_INTERPRETED, userInput, genResult,
                "chart_ai_prompt_v1", "succeed", chart.getUserId());
        recordAiExplain(traceId, chartId, "chart_ai_prompt_v1", "dashscope_or_yuapi", userInput, genResult);
        // 消息确认
        channel.basicAck(deliveryTag, false);
    }

    /**
     * 构建用户输入
     * @param chart
     * @return
     */
    private String buildUserInput(Chart chart) {
        return BiPromptUtils.buildChartAiUserMessage(
                chart.getGoal(), chart.getChartType(), chart.getChartData());
    }

    private void handleChartUpdateError(long chartId, String execMessage) {
        Chart updateChartResult = new Chart();
        updateChartResult.setId(chartId);
        updateChartResult.setStatus("failed");
        updateChartResult.setExecMessage(execMessage);
        boolean updateResult = chartService.updateById(updateChartResult);
        if (!updateResult) {
            log.error("更新图表失败状态失败" + chartId + "," + execMessage);
        }
    }

    private void recordLineage(String traceId, Long chartId, String eventType, String input, String output,
                               String logicRef, String status, Long operatorId) {
        LineageRecordRequest req = new LineageRecordRequest();
        req.setTraceId(traceId);
        req.setChartId(chartId);
        req.setEventType(eventType);
        req.setInputText(input);
        req.setOutputText(output);
        req.setRuleVersion("v1");
        req.setLogicRef(logicRef);
        List<String> datasetsIn = extractDatasets(input, logicRef);
        List<String> datasetsOut = extractDatasets(output, logicRef);
        req.setDatasetsIn(datasetsIn);
        req.setDatasetsOut(datasetsOut);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("logicRef", logicRef);
        meta.put("datasetsIn", datasetsIn);
        meta.put("datasetsOut", datasetsOut);
        req.setEventMetaJson(new com.google.gson.Gson().toJson(meta));
        req.setStatus(status);
        req.setOperatorId(operatorId);
        lineageService.recordEvent(req);
    }

    private List<String> extractDatasets(String text, String logicRef) {
        Set<String> datasets = new LinkedHashSet<>();
        collectDatasets(datasets, text);
        collectDatasets(datasets, logicRef);
        return new ArrayList<>(datasets);
    }

    private void collectDatasets(Set<String> datasets, String text) {
        if (datasets == null || StringUtils.isBlank(text)) {
            return;
        }
        Pattern sqlPattern = Pattern.compile("(?i)(?:from|join|into|update|table)\\s+([a-zA-Z0-9_.`]+)");
        Matcher sqlMatcher = sqlPattern.matcher(text);
        while (sqlMatcher.find()) {
            String table = cleanDataset(sqlMatcher.group(1));
            if (StringUtils.isNotBlank(table)) {
                datasets.add(table);
            }
        }
        Pattern kvPattern = Pattern.compile("(?i)(?:dataset|table|tableName|sourceTable|targetTable)\\s*[\":=]+\\s*\"?([a-zA-Z0-9_.`]+)");
        Matcher kvMatcher = kvPattern.matcher(text);
        while (kvMatcher.find()) {
            String table = cleanDataset(kvMatcher.group(1));
            if (StringUtils.isNotBlank(table)) {
                datasets.add(table);
            }
        }
    }

    private String cleanDataset(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        String value = raw.replace("`", "").trim();
        while (value.endsWith(",") || value.endsWith(";")) {
            value = value.substring(0, value.length() - 1);
        }
        value = value.toLowerCase();
        if ("select".equals(value) || "where".equals(value) || "set".equals(value) || "on".equals(value)) {
            return null;
        }
        return value;
    }

    private void recordAiExplain(String traceId, Long chartId, String promptVersion, String modelName,
                                 String prompt, String aiResult) {
        Map<String, Object> explain = new LinkedHashMap<>();
        explain.put("promptPreview", StringUtils.substring(prompt, 0, 2000));
        explain.put("resultPreview", StringUtils.substring(aiResult, 0, 2000));
        ExplainRecordRequest req = new ExplainRecordRequest();
        req.setTraceId(traceId);
        req.setChartId(chartId);
        req.setExplainType(LineageConstant.EXPLAIN_TYPE_AI);
        req.setPromptVersion(promptVersion);
        req.setModelName(modelName);
        req.setExplainData(new com.google.gson.Gson().toJson(explain));
        lineageService.recordExplain(req);
    }

}
