package com.yupi.springbootinit.utils;

import org.apache.commons.lang3.StringUtils;

/**
 * 智能图表：发给大模型的用户消息（须含固定分隔符，供后端解析）
 */
public final class BiPromptUtils {

    /** 与 ChartController 解析逻辑一致：五个全角「【」 */
    public static final String SPLIT_MARKER = "【【【【【";

    private BiPromptUtils() {
    }

    /**
     * 构建「分析需求 + 原始数据 + 输出格式约束」的完整用户消息。
     * 兼容本地小模型（Ollama qwen2.5:7b 等），给出具体输出示例以引导格式遵守。
     */
    public static String buildChartAiUserMessage(String goal, String chartType, String csvData) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个数据分析师和ECharts专家。下面给出分析需求和CSV数据。\n\n");
        sb.append("【输出格式 — 必须严格遵守，不要添加任何额外文字】\n");
        sb.append("你的输出必须恰好包含 2 个分隔符 ").append(SPLIT_MARKER).append("，将内容分为三段：\n\n");
        sb.append("第一段（可选）：简短说明，一句话以内，例如\"基于提供的数据生成图表如下\"\n");
        sb.append("第二段（必须）：一个合法的 JSON 对象，可直接被 JSON.parse 解析。\n");
        sb.append("  - 所有 key 必须用双引号包裹，例如 \"title\"、\"xAxis\"、\"series\"\n");
        sb.append("  - 所有字符串值必须用双引号，例如 \"柱状图\"\n");
        sb.append("  - 不允许尾逗号\n");
        sb.append("  - 不要用 markdown 代码块包裹，直接输出纯 JSON\n");
        sb.append("第三段（必须）：数据分析结论，使用「结论要点」「关键发现」「行动建议」三个小标题，每段不超过2条\n\n");

        sb.append("输出示例：\n");
        sb.append("基于数据生成图表如下\n");
        sb.append(SPLIT_MARKER).append("\n");
        sb.append("{\"title\":{\"text\":\"销售趋势\"},\"tooltip\":{},\"xAxis\":{\"type\":\"category\",\"data\":[\"1月\",\"2月\",\"3月\"]},\"yAxis\":{\"type\":\"value\"},\"series\":[{\"data\":[12000,15000,11000],\"type\":\"bar\"}]}\n");
        sb.append(SPLIT_MARKER).append("\n");
        sb.append("结论要点：销售额在2月达到峰值15000元，3月回落至11000元。\n");
        sb.append("关键发现：2月环比增长25%，3月环比下降26.7%，波动较大。\n");
        sb.append("行动建议：分析3月下滑原因，关注季节性波动对销售的影响。\n\n");

        sb.append("现在请严格按照以上格式，根据以下数据进行分析：\n\n");
        sb.append("分析需求：\n");
        String userGoal = goal;
        if (StringUtils.isNotBlank(chartType)) {
            userGoal += "，请使用" + chartType;
        }
        sb.append(userGoal).append("\n");
        sb.append("原始数据：\n");
        sb.append(csvData).append("\n");
        return sb.toString();
    }
}
