package com.yupi.springbootinit.job;

import com.yupi.springbootinit.config.bigdata.BigDataProperties;
import com.yupi.springbootinit.service.bigdata.MetricSyncService;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "bigdata.sync", name = "scheduled-enabled", havingValue = "true")
@Slf4j
public class MetricSyncScheduler {

    @Resource
    private MetricSyncService metricSyncService;

    @Resource
    private BigDataProperties bigDataProperties;

    @Scheduled(cron = "${bigdata.sync.cron:0 0 2 * * ?}")
    public void scheduledSync() {
        if (!bigDataProperties.getClickhouse().isEnabled()) {
            return;
        }
        log.info("定时同步门店日指标 → ClickHouse 开始");
        metricSyncService.syncStoreDailyMetrics();
    }
}
