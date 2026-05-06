package com.yupi.springbootinit.utils;

import org.apache.commons.lang3.StringUtils;

import java.util.*;

/**
 * 轻量 CSV 解析工具（复用 chart.chartData 的 CSV 文本）
 * <p>
 * 约束：chartData 是 ExcelUtils.multipartToCsvData() 的输出，通常是 UTF-8、逗号分隔、无复杂引号转义。
 */
public class CsvTableUtils {

    public static class Table {
        private final List<String> headers;
        private final List<List<String>> rows;

        public Table(List<String> headers, List<List<String>> rows) {
            this.headers = headers;
            this.rows = rows;
        }

        public List<String> getHeaders() {
            return headers;
        }

        public List<List<String>> getRows() {
            return rows;
        }

        public int rowCount() {
            return rows.size();
        }

        public int colCount() {
            return headers.size();
        }

        public int indexOfHeaderLike(String... candidates) {
            if (headers == null) return -1;
            for (int i = 0; i < headers.size(); i++) {
                String h = StringUtils.defaultString(headers.get(i)).trim().toLowerCase(Locale.ROOT);
                for (String c : candidates) {
                    if (StringUtils.isBlank(c)) continue;
                    String cc = c.trim().toLowerCase(Locale.ROOT);
                    if (h.equals(cc) || h.contains(cc)) {
                        return i;
                    }
                }
            }
            return -1;
        }

        public List<Double> numericColumn(int colIdx) {
            List<Double> out = new ArrayList<>();
            if (colIdx < 0) return out;
            for (List<String> r : rows) {
                if (r == null || colIdx >= r.size()) continue;
                Double v = tryParseNumber(r.get(colIdx));
                if (v != null) out.add(v);
            }
            return out;
        }
    }

    public static Table parse(String csv) {
        if (StringUtils.isBlank(csv)) {
            return new Table(Collections.emptyList(), Collections.emptyList());
        }
        String[] lines = csv.split("\\r?\\n");
        List<String> cleanLines = new ArrayList<>();
        for (String l : lines) {
            if (StringUtils.isBlank(l)) continue;
            cleanLines.add(l.trim());
        }
        if (cleanLines.isEmpty()) {
            return new Table(Collections.emptyList(), Collections.emptyList());
        }
        List<String> headers = splitCsvLine(cleanLines.get(0));
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i < cleanLines.size(); i++) {
            List<String> row = splitCsvLine(cleanLines.get(i));
            // 对齐列数
            while (row.size() < headers.size()) row.add("");
            rows.add(row);
        }
        return new Table(headers, rows);
    }

    private static List<String> splitCsvLine(String line) {
        // 简化：按逗号切分并去掉首尾空格（假设无复杂引号嵌套）
        String[] parts = StringUtils.defaultString(line).split(",", -1);
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            out.add(StringUtils.defaultString(p).trim());
        }
        return out;
    }

    public static Double tryParseNumber(String s) {
        if (StringUtils.isBlank(s)) return null;
        String x = s.trim();
        x = x.replaceAll("[,%￥¥\\s]", "");
        x = x.replaceAll("[，]", "");
        try {
            return Double.parseDouble(x);
        } catch (Exception e) {
            return null;
        }
    }
}

