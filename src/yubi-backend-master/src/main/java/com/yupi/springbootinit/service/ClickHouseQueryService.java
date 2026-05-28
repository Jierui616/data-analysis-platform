package com.yupi.springbootinit.service;

import java.util.List;
import java.util.Map;

public interface ClickHouseQueryService {

    /**
     * 发现指定表的所有列名（通过 DESCRIBE TABLE）
     */
    List<String> discoverColumns(String tableName);

    /**
     * 执行查询并返回结果行列表
     */
    List<Map<String, Object>> executeQuery(String sql);

    /**
     * 使用 EXPLAIN 预估查询扫描行数
     */
    long estimateRowCount(String sql);

    /**
     * 执行查询并同时返回结果 + 执行耗时(ms) + 实际扫描行数
     */
    QueryResult executeWithStats(String sql);

    class QueryResult {
        private List<Map<String, Object>> rows;
        private int rowCount;
        private long executionMs;
        private long scannedRows;

        public QueryResult(List<Map<String, Object>> rows, int rowCount, long executionMs, long scannedRows) {
            this.rows = rows;
            this.rowCount = rowCount;
            this.executionMs = executionMs;
            this.scannedRows = scannedRows;
        }

        public List<Map<String, Object>> getRows() { return rows; }
        public int getRowCount() { return rowCount; }
        public long getExecutionMs() { return executionMs; }
        public long getScannedRows() { return scannedRows; }
    }
}
