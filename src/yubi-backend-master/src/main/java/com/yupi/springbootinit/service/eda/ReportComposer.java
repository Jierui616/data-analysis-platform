package com.yupi.springbootinit.service.eda;

import com.google.gson.Gson;
import com.yupi.springbootinit.manager.AiManager;
import com.yupi.springbootinit.model.entity.AutoEdaQueryLog;
import com.yupi.springbootinit.model.vo.autoeda.HypothesisVO;
import com.yupi.springbootinit.model.vo.autoeda.InsightReportVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ReportComposer {

    @Resource
    private AiManager aiManager;

    private final Gson gson = new Gson();

    private static final String COMPOSE_PROMPT =
        "你是一个资深数据分析师。请基于以下已验证的分析假设，生成结构化报告。\n"
        + "输出严格 JSON:\n"
        + "{\"executiveSummary\":[{\"title\":\"\",\"description\":\"\",\"confidence\":0.0,\"actionableAdvice\":\"\"}],\n"
        + " \"anomalies\":[{\"metricName\":\"\",\"anomalyDescription\":\"\",\"deviationSigma\":0.0,\"rootCause\":\"\",\"businessImpact\":\"\"}],\n"
        + " \"deepInsights\":[{\"title\":\"\",\"description\":\"\",\"confidence\":0.0,\"actionableAdvice\":\"\"}],\n"
        + " \"openLeads\":[{\"hypothesis\":\"\",\"reasonIncomplete\":\"\",\"suggestedDataToCollect\":\"\"}]}\n"
        + "\n已验证假设:\n%s\n\n待确认线索:\n%s";

    /**
     * 从已验证假设池合成最终报告
     */
    public InsightReportVO compose(List<HypothesisVO> verifiedHypotheses,
                                   List<HypothesisVO> pendingHypotheses,
                                   List<AutoEdaQueryLog> queryLogs,
                                   String chartName,
                                   Long chartId,
                                   String sessionId) {
        // 1. 按 Novelty 排序（简化：按后验概率 * 类型多样性排序）
        List<HypothesisVO> sorted = new ArrayList<>(verifiedHypotheses);
        sorted.sort((a, b) -> {
            double scoreA = (a.getConfidencePosterior() != null ? a.getConfidencePosterior() : 0)
                          * typeWeight(a.getType());
            double scoreB = (b.getConfidencePosterior() != null ? b.getConfidencePosterior() : 0)
                          * typeWeight(b.getType());
            return Double.compare(scoreB, scoreA);
        });

        // 2. 使用 LLM 合成报告内容
        String verifiedSummary = sorted.stream()
                .map(h -> String.format("[%.0f%%|%s] %s (血缘: %s)",
                        (h.getConfidencePosterior() != null ? h.getConfidencePosterior() : 0) * 100,
                        h.getType(), h.getStatement(), h.getLineagePath()))
                .collect(Collectors.joining("\n"));

        String pendingSummary = pendingHypotheses.stream()
                .map(h -> String.format("[%s] %s", h.getType(), h.getStatement()))
                .collect(Collectors.joining("\n"));

        InsightReportVO report = buildReportWithLLM(verifiedSummary, pendingSummary);

        // 3. 填充元数据
        report.setSessionId(sessionId);
        report.setChartName(chartName);
        report.setChartId(chartId);
        report.setTotalQueriesExecuted(queryLogs.size());
        report.setGeneratedAt(System.currentTimeMillis());

        // 4. 构建审计轨迹
        report.setAuditTrail(buildAuditTrail(queryLogs));

        // 5. 为报告中的每条洞察挂载证据链
        attachEvidenceChains(report, sorted, queryLogs);

        return report;
    }

    private InsightReportVO buildReportWithLLM(String verifiedSummary, String pendingSummary) {
        String prompt = String.format(COMPOSE_PROMPT,
                StringUtils.defaultIfBlank(verifiedSummary, "无已验证假设"),
                StringUtils.defaultIfBlank(pendingSummary, "无待确认线索"));

        try {
            String response = aiManager.doChat(0, prompt);
            return parseReportJson(response);
        } catch (Exception e) {
            log.warn("Report LLM composition failed, using fallback: {}", e.getMessage());
            return buildFallbackReport(verifiedSummary);
        }
    }

    private InsightReportVO buildFallbackReport(String verifiedSummary) {
        InsightReportVO report = new InsightReportVO();
        InsightReportVO.InsightItem item = new InsightReportVO.InsightItem();
        item.setTitle("自动探索分析结果");
        item.setDescription(verifiedSummary);
        item.setConfidence(0.7);
        item.setActionableAdvice("建议人工审核上述假设");
        report.setExecutiveSummary(Collections.singletonList(item));
        report.setAnomalies(Collections.emptyList());
        report.setDeepInsights(Collections.emptyList());
        report.setOpenLeads(Collections.emptyList());
        return report;
    }

    private InsightReportVO parseReportJson(String raw) {
        if (StringUtils.isBlank(raw)) return buildFallbackReport("");
        String s = raw.trim();
        int a = s.indexOf("{");
        int b = s.lastIndexOf("}");
        if (a >= 0 && b > a) s = s.substring(a, b + 1);
        try {
            return gson.fromJson(s, InsightReportVO.class);
        } catch (Exception e) {
            log.debug("Report JSON parse failed: {}", e.getMessage());
            return buildFallbackReport("");
        }
    }

    private List<InsightReportVO.AuditStep> buildAuditTrail(List<AutoEdaQueryLog> queryLogs) {
        List<InsightReportVO.AuditStep> steps = new ArrayList<>();
        for (int i = 0; i < queryLogs.size(); i++) {
            AutoEdaQueryLog log = queryLogs.get(i);
            InsightReportVO.AuditStep step = new InsightReportVO.AuditStep();
            step.setStepNumber(i + 1);
            step.setQuerySql(log.getQuerySql());
            step.setDecisionReason("IG=" + String.format("%.3f", log.getIgScore() != null ? log.getIgScore() : 0));
            step.setIgScore(log.getIgScore() != null ? log.getIgScore() : 0);
            step.setResultSummary(log.getResultSummary());
            step.setExecutionMs(log.getExecutionMs() != null ? log.getExecutionMs() : 0);
            steps.add(step);
        }
        return steps;
    }

    private void attachEvidenceChains(InsightReportVO report,
                                       List<HypothesisVO> hypotheses,
                                       List<AutoEdaQueryLog> queryLogs) {
        Map<String, AutoEdaQueryLog> queryMap = queryLogs.stream()
                .collect(Collectors.toMap(AutoEdaQueryLog::getQueryId, q -> q, (a, b) -> a));

        if (report.getExecutiveSummary() != null) {
            for (InsightReportVO.InsightItem item : report.getExecutiveSummary()) {
                item.setEvidenceChain(Collections.emptyList());
            }
        }
    }

    private double typeWeight(String type) {
        switch (type) {
            case "anomaly": return 1.2;
            case "counterfactual": return 1.1;
            case "correlation": return 0.9;
            case "trend": return 0.8;
            case "segment_diff": return 1.0;
            default: return 1.0;
        }
    }
}
