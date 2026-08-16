package com.yupi.springbootinit.service.impl;

import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.ThrowUtils;
import com.yupi.springbootinit.service.ClickHouseQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * ClickHouse 查询服务实现。
 * 优先使用 Spring 管理的 HikariCP 连接池（{@code clickHouseJdbcTemplate}），
 * 未启用 ClickHouse 时回退到原始 JDBC 连接。
 */
@Service
@Slf4j
public class ClickHouseQueryServiceImpl implements ClickHouseQueryService {

    private static final Pattern READONLY_SELECT_PATTERN = Pattern.compile("^\\s*select\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOCKED_SQL_PATTERN = Pattern.compile(
            "\\b(insert|update|delete|drop|alter|truncate|create|grant|revoke|attach|detach|rename|optimize|system)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMIT_PATTERN = Pattern.compile("\\blimit\\s+\\d+", Pattern.CASE_INSENSITIVE);

    /**
     * 当 {@code bigdata.clickhouse.enabled=true} 时由 {@code ClickHouseConfig} 提供；
     * 关闭时此字段为 {@code null}，本服务回退至 {@link #executeQueryViaDriverManager}。
     */
    @Autowired(required = false)
    @Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate clickHouseJdbcTemplate;

    @Value("${bigdata.clickhouse.url:jdbc:ch://localhost:8123/yubi_analytics}")
    private String clickhouseUrl;

    @Value("${bigdata.clickhouse.username:${bigdata.clickhouse.user:default}}")
    private String clickhouseUser;

    @Value("${bigdata.clickhouse.password:}")
    private String clickhousePassword;

    @Value("${autoeda.experiment.use-explain-cost:true}")
    private boolean useExplainCost;

    // ---- Connection helpers ----

    private boolean hasPool() {
        return clickHouseJdbcTemplate != null;
    }

    private Connection getRawConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", clickhouseUser);
        props.setProperty("password", clickhousePassword != null ? clickhousePassword : "");
        props.setProperty("socket_timeout", "30000");
        props.setProperty("connect_timeout", "10000");
        return DriverManager.getConnection(clickhouseUrl, props);
    }

    // ---- Public API ----

    @Override
    public List<String> discoverColumns(String tableName) {
        List<String> columns = new ArrayList<>();
        String sql = "DESCRIBE TABLE " + tableName;
        try {
            if (hasPool()) {
                List<Map<String, Object>> rows = clickHouseJdbcTemplate.queryForList(sql);
                for (Map<String, Object> row : rows) {
                    Object name = row.get("name");
                    if (name != null) columns.add(String.valueOf(name));
                }
            } else {
                try (Connection conn = getRawConnection();
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) {
                        columns.add(rs.getString("name"));
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Failed to discover columns for table {}: {}", tableName, e.getMessage());
        }
        return columns;
    }

    @Override
    public List<Map<String, Object>> executeQuery(String sql) {
        return executeQueryInternal(sql, true);
    }

    private List<Map<String, Object>> executeQueryInternal(String sql, boolean validate) {
        if (validate) {
            validateReadonlySelect(sql);
            sql = appendDefaultLimit(sql);
        }
        if (hasPool()) {
            try {
                return clickHouseJdbcTemplate.queryForList(sql);
            } catch (Exception e) {
                log.error("ClickHouse query failed via pool: {}", e.getMessage());
                return Collections.emptyList();
            }
        }
        return executeQueryViaDriverManager(sql);
    }

    @Override
    public long estimateRowCount(String sql) {
        try {
            String explainSql = "EXPLAIN ESTIMATE " + sql;
            List<Map<String, Object>> rows = executeQueryInternal(explainSql, false);
            for (Map<String, Object> row : rows) {
                Object direct = row.get("rows");
                if (direct == null) {
                    direct = row.get("estimate");
                }
                if (direct instanceof Number) {
                    return ((Number) direct).longValue();
                }
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    String key = entry.getKey() != null ? entry.getKey().toLowerCase() : "";
                    if (key.contains("rows") || key.contains("estimate") || key.contains("read")) {
                        Object val = entry.getValue();
                        if (val instanceof Number) {
                            return ((Number) val).longValue();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("EXPLAIN ESTIMATE failed, returning default: {}", e.getMessage());
        }
        return 100000;
    }

    private void validateReadonlySelect(String sql) {
        ThrowUtils.throwIf(sql == null || sql.trim().isEmpty(), ErrorCode.PARAMS_ERROR, "SQL 不能为空");
        ThrowUtils.throwIf(!READONLY_SELECT_PATTERN.matcher(sql).find(), ErrorCode.PARAMS_ERROR, "仅允许 SELECT 查询");
        ThrowUtils.throwIf(BLOCKED_SQL_PATTERN.matcher(sql).find() || sql.contains(";"),
                ErrorCode.NO_AUTH_ERROR, "禁止执行写入或多语句 SQL");
    }

    private String appendDefaultLimit(String sql) {
        if (sql == null || LIMIT_PATTERN.matcher(sql).find()) {
            return sql;
        }
        return sql.trim() + " LIMIT 1000";
    }

    @Override
    public QueryResult executeWithStats(String sql) {
        long start = System.currentTimeMillis();
        List<Map<String, Object>> rows = executeQuery(sql);
        long elapsed = System.currentTimeMillis() - start;
        long scanned = useExplainCost ? estimateRowCount(sql) : Math.max(rows.size(), 1);
        return new QueryResult(rows, rows.size(), elapsed, scanned);
    }

    // ---- Private helpers ----

    /**
     * 回退方案：原始 JDBC（未启用 ClickHouse 连接池时使用）。
     */
    private List<Map<String, Object>> executeQueryViaDriverManager(String sql) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = getRawConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= colCount; i++) {
                    row.put(meta.getColumnName(i), rs.getObject(i));
                }
                result.add(row);
            }
        } catch (SQLException e) {
            log.error("ClickHouse query failed: {}", e.getMessage());
            return Collections.emptyList();
        }
        return result;
    }
}
