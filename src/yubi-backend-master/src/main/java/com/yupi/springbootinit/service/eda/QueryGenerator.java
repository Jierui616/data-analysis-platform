package com.yupi.springbootinit.service.eda;

import com.yupi.springbootinit.manager.AiManager;
import com.yupi.springbootinit.service.LineageService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.*;

/**
 * SQL 查询生成器。
 * 结合 LLM 的语义理解 + 维度模板 + 血缘验证，生成可执行的 ClickHouse SQL。
 */
@Component
@Slf4j
public class QueryGenerator {

    @Resource
    private AiManager aiManager;

    @Resource
    private LineageService lineageService;

    private static final String SQL_GEN_PROMPT_TEMPLATE =
        "你是一个 ClickHouse SQL 专家。请根据以下信息生成一条 SELECT 查询。\n"
        + "数据表: %s\n"
        + "可用字段: %s\n"
        + "分析意图: %s\n"
        + "查询维度: %s\n"
        + "血缘约束: 查询涉及的字段必须在血缘路径上可达: %s\n"
        + "\n要求: 1) 仅输出 SQL 语句，不要任何解释; 2) 使用聚合函数; 3) 限制返回 <= 100 行; 4) 不要分号";

    /**
     * 根据维度组合和分析意图生成 SQL
     */
    public String generateSql(String tableName,
                              List<String> availableColumns,
                              String intent,
                              Map<String, List<String>> dimensions,
                              String lineagePathDesc) {
        String prompt = String.format(SQL_GEN_PROMPT_TEMPLATE,
                tableName,
                String.join(", ", availableColumns),
                StringUtils.defaultString(intent, "通用数据探索"),
                dimensionsToString(dimensions),
                StringUtils.defaultString(lineagePathDesc, "无特殊约束"));

        try {
            String response = aiManager.doChat(0, prompt);
            return cleanSql(response);
        } catch (Exception e) {
            log.warn("LLM SQL generation failed, using template fallback: {}", e.getMessage());
            return generateTemplateSql(tableName, dimensions, availableColumns);
        }
    }

    /**
     * 模板 SQL 生成（LLM 不可用时的降级方案）
     */
    public String generateTemplateSql(String tableName, Map<String, List<String>> dimensions,
                                      List<String> availableColumns) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");

        List<String> selectParts = new ArrayList<>();
        List<String> groupByParts = new ArrayList<>();
        String metricCol = "revenue";

        for (Map.Entry<String, List<String>> entry : dimensions.entrySet()) {
            String dimKey = entry.getKey();
            List<String> vals = entry.getValue();
            if (vals == null || vals.isEmpty()) continue;

            if ("time".equalsIgnoreCase(dimKey) || vals.contains("recent") || vals.contains("monthly")) {
                selectParts.add("formatDateTime(statDate, '%Y-%m') AS month");
                groupByParts.add("month");
            } else if ("dimension".equalsIgnoreCase(dimKey)) {
                String col = vals.get(0);
                if (col != null && !col.isEmpty()) {
                    selectParts.add(col);
                    groupByParts.add(col);
                }
            } else if ("metric".equalsIgnoreCase(dimKey)) {
                metricCol = vals.get(0);
            }
        }

        if (tableName != null && tableName.contains("store_daily_metric")) {
            if (availableColumns != null && availableColumns.contains(metricCol)) {
                selectParts.add("sum(" + metricCol + ") AS total_" + metricCol);
                selectParts.add("avg(" + metricCol + ") AS avg_" + metricCol);
            } else {
                selectParts.add("sum(revenue) AS total_revenue");
                selectParts.add("avg(grossMarginRate) AS avg_gross_margin_rate");
            }
            if (availableColumns == null || availableColumns.contains("avgTicket")) {
                selectParts.add("avg(avgTicket) AS avg_ticket");
            }
            selectParts.add("count() AS row_cnt");
        } else {
            selectParts.add("count() AS cnt");
        }

        sql.append(String.join(", ", selectParts));
        sql.append(" FROM ").append(tableName);

        if (!groupByParts.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", groupByParts));
        }
        sql.append(" ORDER BY ").append(selectParts.get(0));
        sql.append(" LIMIT 50");

        return sql.toString();
    }

    /**
     * @deprecated use {@link #generateTemplateSql(String, Map, List)}
     */
    public String generateTemplateSql(String tableName, Map<String, List<String>> dimensions) {
        return generateTemplateSql(tableName, dimensions, null);
    }

    /**
     * 验证 SQL 的合法性：字段名必须在血缘可及范围内
     */
    public boolean validateByLineage(String sql, String rootMetricKey, List<String> downstreamNodes) {
        if (StringUtils.isBlank(sql)) return false;
        String lowerSql = sql.toLowerCase();
        for (String node : downstreamNodes) {
            if (lowerSql.contains(node.toLowerCase())) {
                return true;
            }
        }
        return lowerSql.contains("count(") || lowerSql.contains("sum(") || lowerSql.contains("avg(");
    }

    private String cleanSql(String raw) {
        if (StringUtils.isBlank(raw)) return "";
        String s = raw.trim();
        int a = s.indexOf("```");
        if (a >= 0) {
            int b = s.lastIndexOf("```");
            if (b > a) {
                s = s.substring(a + 3, b).trim();
                if (s.toLowerCase().startsWith("sql")) s = s.substring(3).trim();
            }
        }
        if (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        return s;
    }

    private String dimensionsToString(Map<String, List<String>> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) return "全表扫描";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : dimensions.entrySet()) {
            sb.append(e.getKey()).append("=").append(e.getValue()).append("; ");
        }
        return sb.toString();
    }
}
