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
     * 构建「分析需求 + 原始数据 + 输出格式约束」的完整用户消息
     */
    public static String buildChartAiUserMessage(String goal, String chartType, String csvData) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是数据分析师和 ECharts（V5）专家。下面给出「分析需求」和「原始数据」（CSV，逗号分隔）。\n\n");
        sb.append("【输出格式 — 必须严格遵守】\n");
        sb.append("1. 全文不要使用 markdown，不要用 ``` 代码块。\n");
        sb.append("2. 使用分隔串 ").append(SPLIT_MARKER).append("（连续五个全角左方括号「【」）把内容分成三段。\n");
        sb.append("3. 第一段：可留空或一句话。\n");
        sb.append("4. 第二段：仅一个合法的 ECharts option 的 JSON 对象（可被 JSON.parse 解析）。\n");
        sb.append("5. 第三段：数据分析结论。要求：简练中文，总字数不超过 450 字；用「结论要点」「关键发现」「行动建议」三个小标题分段；每段各不超过 3 条，每条一句话；避免同义重复；数字保留关键项即可。\n");
        sb.append("6. 分隔串 ").append(SPLIT_MARKER).append(" 必须恰好出现 2 次。\n\n");

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
