package com.yupi.springbootinit.service.bigdata;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yupi.springbootinit.config.bigdata.BigDataProperties;
import com.yupi.springbootinit.mapper.StoreDailyMetricMapper;
import com.yupi.springbootinit.model.entity.restaurant.StoreDailyMetric;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MetricSyncService {

    private final AtomicReference<Instant> lastSyncAt = new AtomicReference<>();

    @Resource
    private StoreDailyMetricMapper storeDailyMetricMapper;

    @Resource
    private BigDataProperties bigDataProperties;

    @Autowired(required = false)
    private ClickHouseMetricRepository clickHouseMetricRepository;

    public boolean isClickHouseReady() {
        return bigDataProperties.getClickhouse().isEnabled() && clickHouseMetricRepository != null;
    }

    public Instant getLastSyncAt() {
        return lastSyncAt.get();
    }

    /**
     * 全量同步 MySQL store_daily_metric → ClickHouse。
     */
    public Map<String, Object> syncStoreDailyMetrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!isClickHouseReady()) {
            result.put("success", false);
            result.put("message", "ClickHouse 未启用，请在配置中设置 bigdata.clickhouse.enabled=true");
            return result;
        }
        QueryWrapper<StoreDailyMetric> qw = new QueryWrapper<>();
        qw.eq("isDelete", 0);
        List<StoreDailyMetric> rows = storeDailyMetricMapper.selectList(qw);
        long before = clickHouseMetricRepository.countStoreDailyRows();
        clickHouseMetricRepository.truncateStoreDaily();
        clickHouseMetricRepository.insertStoreDailyBatch(rows);
        long after = clickHouseMetricRepository.countStoreDailyRows();
        lastSyncAt.set(Instant.now());
        result.put("success", true);
        result.put("mysqlRows", rows.size());
        result.put("clickHouseRowsBefore", before);
        result.put("clickHouseRowsAfter", after);
        result.put("syncedAt", lastSyncAt.get().toString());
        log.info("ClickHouse sync done: mysql={} ch={}", rows.size(), after);
        return result;
    }
}
