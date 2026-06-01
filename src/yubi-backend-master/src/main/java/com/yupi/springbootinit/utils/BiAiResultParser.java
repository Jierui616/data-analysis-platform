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
 * <p>
 * 兼容本地模型（Ollama 等）输出的 JavaScript 风格对象字面量（无引号 key、单引号字符串、尾逗号、赋值前缀）。
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

        // 优先按约定分隔符解析
        String[] splits = result.split(BiPromptUtils.SPLIT_MARKER);
        if (splits.length >= 3) {
            String genChart = stripMarkdownFence(splits[1].trim()).trim();
            String genResult = splits[2].trim();
            genChart = coerceToValidJson(genChart);
            assertJsonObject(genChart);
            if (StringUtils.isBlank(genResult)) {
                genResult = "（模型未输出文字结论，请结合图表理解。）";
            }
            return new Parsed(genChart, genResult);
        }

        // 备用路径：从正文抽取 JSON / JS 对象
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
        json = coerceToValidJson(json);
        try {
            JSON.readTree(json);
        } catch (Exception e) {
            logFailurePreview(result);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 返回的图表配置不是合法 JSON");
        }
        String after = work.substring(jsonStart + extractBalancedJsonLen(work, jsonStart)).trim();
        String genResult = StringUtils.isNotBlank(after)
                ? after
                : "（模型未单独输出文字结论，请结合图表理解。）";
        log.warn("AI 未按约定分隔符输出，已用 JSON 抽取作为备用解析");
        return new Parsed(json.trim(), genResult);
    }

    /**
     * 将 JavaScript 风格的对象字面量尽力转为合法 JSON：
     * 去掉赋值前缀、尾分号、单行注释，给无引号 key 加引号，单引号字符串转双引号，移除尾逗号。
     */
    static String coerceToValidJson(String raw) {
        if (StringUtils.isBlank(raw)) {
            return raw;
        }
        String s = raw.trim();

        // 1. 去掉赋值前缀：option = {...} / var option = {...}
        s = s.replaceAll("^(var|const|let|option|config|chartOption|opt)\\s+\\w*\\s*=\\s*", "");
        s = s.replaceAll("^\\w+\\s*=\\s*", "");

        // 2. 去掉尾部多余分号
        if (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }

        // 3. 去掉 // 单行注释（注意避免 http:// 被误伤）
        s = s.replaceAll("(?<!:)//[^\n]*", "");

        // 4. 逐字符归一化：给无引号 key 加双引号，单引号字符串转双引号，去掉尾逗号
        StringBuilder out = new StringBuilder();
        int len = s.length();
        int i = 0;
        while (i < len) {
            char c = s.charAt(i);

            // 双引号字符串：原样吞入
            if (c == '"') {
                out.append(c);
                i++;
                while (i < len) {
                    char sc = s.charAt(i);
                    out.append(sc);
                    if (sc == '\\' && i + 1 < len) {
                        out.append(s.charAt(++i));
                    } else if (sc == '"') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }

            // 单引号字符串：转成双引号
            if (c == '\'') {
                out.append('"');
                i++;
                while (i < len) {
                    char sc = s.charAt(i);
                    if (sc == '\\' && i + 1 < len) {
                        out.append(sc);
                        out.append(s.charAt(++i));
                    } else if (sc == '\'') {
                        out.append('"');
                        i++;
                        break;
                    } else {
                        out.append(sc);
                    }
                    i++;
                }
                continue;
            }

            // 尾逗号：逗号后的下一个非空白字符是 } 或 ] 则跳过
            if (c == ',') {
                int j = i + 1;
                while (j < len && (s.charAt(j) == ' ' || s.charAt(j) == '\t' || s.charAt(j) == '\n' || s.charAt(j) == '\r')) {
                    j++;
                }
                if (j < len && (s.charAt(j) == '}' || s.charAt(j) == ']')) {
                    i = j;
                    out.append(s.charAt(i));
                    i++;
                    continue;
                }
            }

            // 无引号 key：标识符紧跟空白和冒号
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$') {
                int keyEnd = i;
                while (keyEnd < len && isIdChar(s.charAt(keyEnd))) {
                    keyEnd++;
                }
                int colonCheck = keyEnd;
                while (colonCheck < len && (s.charAt(colonCheck) == ' ' || s.charAt(colonCheck) == '\t')) {
                    colonCheck++;
                }
                if (colonCheck < len && s.charAt(colonCheck) == ':') {
                    out.append('"');
                    out.append(s, i, keyEnd);
                    out.append('"');
                    i = keyEnd;
                    continue;
                }
            }

            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isIdChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '$';
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
        // 处理嵌套围栏：递归去掉最外层 ```
        while (t.startsWith("```")) {
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
        }
        return t;
    }

    /**
     * 从 start 处（须为 '{'）抽取与之平衡的最外层 JSON 对象子串。
     */
    static String extractBalancedJson(String s, int start) {
        int len = extractBalancedJsonLen(s, start);
        return len > 0 ? s.substring(start, start + len) : null;
    }

    static int extractBalancedJsonLen(String s, int start) {
        if (start < 0 || start >= s.length() || s.charAt(start) != '{') {
            return 0;
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
                    return i - start + 1;
                }
            }
        }
        return 0;
    }
}
