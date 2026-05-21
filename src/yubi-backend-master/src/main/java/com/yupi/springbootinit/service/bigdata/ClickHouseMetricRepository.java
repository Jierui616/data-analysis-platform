package com.yupi.springbootinit.service.bigdata;

import com.yupi.springbootinit.model.entity.restaurant.StoreDailyMetric;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnBean(name = "clickHouseJdbcTemplate")
@Slf4j
public class ClickHouseMetricRepository {

    @Autowired
    @Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate jdbcTemplate;

    public long countStoreDailyRows() {
        Long cnt = jdbcTemplate.queryForObject(
                "SELECT count() FROM yubi_analytics.store_daily_metric_ch FINAL", Long.class);
        return cnt == null ? 0L : cnt;
    }

    public void truncateStoreDaily() {
        jdbcTemplate.execute("TRUNCATE TABLE IF EXISTS yubi_analytics.store_daily_metric_ch");
    }

    public void insertStoreDailyBatch(List<StoreDailyMetric> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO yubi_analytics.store_daily_metric_ch "
                + "(statDate, storeId, revenue, grossMarginRate, avgTicket, tableTurnover, seatOccupancy, "
                + "takeawayRatio, dineInRatio, prepTimeAvgMin, cancelRate, badReviewRate, mysqlId) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        List<Object[]> batch = new ArrayList<>();
        for (StoreDailyMetric row : rows) {
            if (row == null || row.getStatDate() == null || row.getStoreId() == null) {
                continue;
            }
            batch.add(new Object[]{
                    Date.valueOf(row.getStatDate()),
                    row.getStoreId(),
                    nullSafe(row.getRevenue()),
                    nullSafe(row.getGrossMarginRate()),
                    nullSafe(row.getAvgTicket()),
                    nullSafe(row.getTableTurnover()),
                    nullSafe(row.getSeatOccupancy()),
                    nullSafe(row.getTakeawayRatio()),
                    nullSafe(row.getDineInRatio()),
                    nullSafe(row.getPrepTimeAvgMin()),
                    nullSafe(row.getCancelRate()),
                    nullSafe(row.getBadReviewRate()),
                    row.getId() == null ? 0L : row.getId()
            });
        }
        jdbcTemplate.batchUpdate(sql, batch);
    }

    public List<StoreDailyMetric> queryStoreDaily(List<Long> storeIds, LocalDate start, LocalDate end) {
        if (storeIds == null || storeIds.isEmpty() || start == null || end == null) {
            return Collections.emptyList();
        }
        String inClause = String.join(",", Collections.nCopies(storeIds.size(), "?"));
        String sql = "SELECT statDate, storeId, revenue, grossMarginRate, avgTicket, tableTurnover, "
                + "seatOccupancy, takeawayRatio, dineInRatio, prepTimeAvgMin, cancelRate, badReviewRate, mysqlId "
                + "FROM yubi_analytics.store_daily_metric_ch FINAL "
                + "WHERE storeId IN (" + inClause + ") AND statDate >= ? AND statDate <= ? "
                + "ORDER BY statDate, storeId";
        List<Object> args = new ArrayList<>();
        args.addAll(storeIds);
        args.add(Date.valueOf(start));
        args.add(Date.valueOf(end));
        return jdbcTemplate.query(sql, args.toArray(), (rs, rowNum) -> mapRow(rs));
    }

    public void insertPlatformEvent(String eventId, String eventType, String traceId, Long chartId, String payload) {
        String sql = "INSERT INTO yubi_analytics.platform_event_ch (eventId, eventType, traceId, chartId, payload) "
                + "VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, eventId, eventType, traceId, chartId, payload);
    }

    private StoreDailyMetric mapRow(ResultSet rs) throws SQLException {
        StoreDailyMetric m = new StoreDailyMetric();
        Date d = rs.getDate("statDate");
        if (d != null) {
            m.setStatDate(d.toLocalDate());
        }
        m.setStoreId(rs.getLong("storeId"));
        m.setId(rs.getLong("mysqlId"));
        m.setRevenue(rs.getBigDecimal("revenue"));
        m.setGrossMarginRate(rs.getBigDecimal("grossMarginRate"));
        m.setAvgTicket(rs.getBigDecimal("avgTicket"));
        m.setTableTurnover(rs.getBigDecimal("tableTurnover"));
        m.setSeatOccupancy(rs.getBigDecimal("seatOccupancy"));
        m.setTakeawayRatio(rs.getBigDecimal("takeawayRatio"));
        m.setDineInRatio(rs.getBigDecimal("dineInRatio"));
        m.setPrepTimeAvgMin(rs.getBigDecimal("prepTimeAvgMin"));
        m.setCancelRate(rs.getBigDecimal("cancelRate"));
        m.setBadReviewRate(rs.getBigDecimal("badReviewRate"));
        return m;
    }

    private BigDecimal nullSafe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
