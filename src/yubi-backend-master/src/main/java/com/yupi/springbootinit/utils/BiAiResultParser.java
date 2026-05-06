package com.yupi.springbootinit.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.BusinessException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 解析智能图表 AI 返回：优先按 {@link BiPromptUtils#SPLIT_MARKER} 分段；否则从正文抽取首个平衡 JSON 作为 ECharts option。
 */
public final class BiAiResultParser {

    private static final Logger log = LoggerFactory.getLogger(BiAiResultParser.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private BiAiResultParser() {
    }

    public static final class Parsed {
        private final String genChart;
        private final String genResult;

        public Parsed(String genChart, String genResult) {
            this.genChart = genChart;
            this.genResult = genResult;
        }

        public String getGenChart() {
            return genChart;
        }

        public String getGenResult() {
            return genResult;
        }
    }

    public static Parsed parse(String raw) {
        if (StringUtils.isBlank(raw)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 生成错误");
        }
        String result = raw.trim();

        String[] splits = result.split(BiPromptUtils.SPLIT_MARKER);
        if (splits.length >= 3) {
            String genChart = stripMarkdownFence(splits[1].trim()).trim();
            String genResult = splits[2].trim();
            assertJsonObject(genChart);
            if (StringUtils.isBlank(genResult)) {
                genResult = "（模型未输出文字结论，请结合图表理解。）";
            }
            return new Parsed(genChart, genResult);
        }

        String work = stripMarkdownFence(result);
        work = work.replaceFirst("^【+\\s*", "");
        int jsonStart = work.indexOf('{');
        if (jsonStart < 0) {
            logFailurePreview(result);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 生成错误");
        }
        String json = extractBalancedJson(work, jsonStart);
        if (json == null) {
            logFailurePreview(result);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 生成错误");
        }
        try {
            JSON.readTree(json);
        } catch (Exception e) {
            logFailurePreview(result);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 返回的图表配置不是合法 JSON");
        }
        String after = work.substring(jsonStart + json.length()).trim();
        String genResult = StringUtils.isNotBlank(after)
                ? after
                : "（模型未单独输出文字结论，请结合图表理解。）";
        log.warn("AI 未按约定分隔符输出，已用 JSON 抽取作为备用解析");
        return new Parsed(json.trim(), genResult);
    }

    private static void assertJsonObject(String genChart) {
        if (StringUtils.isBlank(genChart)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 生成错误");
        }
        try {
            JsonNode node = JSON.readTree(genChart);
            if (!isValidEchartsOption(node)) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 返回的图表配置缺少有效 series，无法渲染图表");
            }
        } catch (Exception e) {
            if (e instanceof BusinessException) {
                throw (BusinessException) e;
            }
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 返回的图表配置不是合法 JSON");
        }
    }

    /**
     * 最小可渲染校验：
     * 1) 必须存在非空 series；
     * 2) series 元素应为对象或可识别节点。
     */
    private static boolean isValidEchartsOption(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        JsonNode series = node.get("series");
        if (series == null) {
            return false;
        }
        if (series.isArray()) {
            if (series.size() == 0) {
                return false;
            }
            for (JsonNode item : series) {
                if (item != null && item.isObject()) {
                    return true;
                }
            }
            return false;
        }
        return series.isObject();
    }

    private static void logFailurePreview(String result) {
        String preview = result.length() > 500 ? result.substring(0, 500) + "..." : result;
        log.warn("无法解析 AI 输出，预览: {}", preview);
    }

    private static String stripMarkdownFence(String s) {
        String t = s.trim();
        if (!t.startsWith("```")) {
            return t;
        }
        int nl = t.indexOf('\n');
        if (nl > 0) {
            t = t.substring(nl + 1);
        } else {
            t = t.length() > 3 ? t.substring(3) : "";
        }
        int end = t.lastIndexOf("```");
        if (end > 0) {
            t = t.substring(0, end).trim();
        }
        return t;
    }

    /**
     * 从 start 处（须为 '{'）抽取与之平衡的最外层 JSON 对象子串。
     */
    static String extractBalancedJson(String s, int start) {
        if (start < 0 || start >= s.length() || s.charAt(start) != '{') {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
