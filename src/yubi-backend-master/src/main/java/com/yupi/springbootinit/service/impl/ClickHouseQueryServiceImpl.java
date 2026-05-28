package com.yupi.springbootinit.service.impl;

import com.yupi.springbootinit.service.ClickHouseQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

@Service
@ConditionalOnProperty(name = "bigdata.enabled", havingValue = "true")
@Slf4j
public class ClickHouseQueryServiceImpl implements ClickHouseQueryService {

    @Value("${bigdata.clickhouse.url:jdbc:ch://localhost:8123/yubi_analytics}")
    private String clickhouseUrl;

    @Value("${bigdata.clickhouse.user:default}")
    private String clickhouseUser;

    @Value("${bigdata.clickhouse.password:}")
    private String clickhousePassword;

    private Connection getConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", clickhouseUser);
        if (clickhousePassword != null && !clickhousePassword.isEmpty()) {
            props.setProperty("password", clickhousePassword);
        }
        return DriverManager.getConnection(clickhouseUrl, props);
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
            if (!rows.isEmpty()) {
                Object rowsObj = rows.get(0).getOrDefault("rows", rows.get(0).get("estimate"));
                if (rowsObj instanceof Number) {
                    return ((Number) rowsObj).longValue();
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
        long scanned = estimateRowCount(sql);
        return new QueryResult(rows, rows.size(), elapsed, scanned);
    }
}
