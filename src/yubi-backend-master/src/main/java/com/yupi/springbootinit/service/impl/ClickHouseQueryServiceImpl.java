package com.yupi.springbootinit.service.impl;

import com.yupi.springbootinit.service.ClickHouseQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

@Service
@Slf4j
public class ClickHouseQueryServiceImpl implements ClickHouseQueryService {

    @Value("${bigdata.clickhouse.url:jdbc:ch://localhost:8123/yubi_analytics}")
    private String clickhouseUrl;

    @Value("${bigdata.clickhouse.username:${bigdata.clickhouse.user:default}}")
    private String clickhouseUser;

    @Value("${bigdata.clickhouse.password:}")
    private String clickhousePassword;

    @Value("${autoeda.experiment.use-explain-cost:true}")
    private boolean useExplainCost;

    private Connection getConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", clickhouseUser);
        props.setProperty("password", clickhousePassword != null ? clickhousePassword : "");
        props.setProperty("socket_timeout", "30000");
        props.setProperty("connect_timeout", "10000");
        return DriverManager.getConnection(clickhouseUrl, props);
    }

    @Override
    public List<String> discoverColumns(String tableName) {
        List<String> columns = new ArrayList<>();
        String sql = "DESCRIBE TABLE " + tableName;
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        } catch (SQLException e) {
            log.error("Failed to discover columns for table {}: {}", tableName, e.getMessage());
        }
        return columns;
    }

    @Override
    public List<Map<String, Object>> executeQuery(String sql) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = getConnection();
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

    @Override
    public long estimateRowCount(String sql) {
        try {
            String explainSql = "EXPLAIN ESTIMATE " + sql;
            List<Map<String, Object>> rows = executeQuery(explainSql);
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

    @Override
    public QueryResult executeWithStats(String sql) {
        long start = System.currentTimeMillis();
        List<Map<String, Object>> rows = executeQuery(sql);
        long elapsed = System.currentTimeMillis() - start;
        long scanned = useExplainCost ? estimateRowCount(sql) : Math.max(rows.size(), 1);
        return new QueryResult(rows, rows.size(), elapsed, scanned);
    }
}
