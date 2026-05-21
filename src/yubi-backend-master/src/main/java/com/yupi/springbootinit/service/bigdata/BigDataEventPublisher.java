package com.yupi.springbootinit.service.bigdata;

import com.google.gson.Gson;
import com.yupi.springbootinit.config.bigdata.BigDataProperties;
import com.yupi.springbootinit.model.dto.bigdata.BigDataPlatformEvent;
import java.util.UUID;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class BigDataEventPublisher {

    private static final Gson GSON = new Gson();

    @Resource
    private BigDataProperties bigDataProperties;

    @Autowired(required = false)
    @Qualifier("bigDataKafkaTemplate")
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired(required = false)
    private ClickHouseMetricRepository clickHouseMetricRepository;

    public void publishChartGenCompleted(Long chartId, String traceId, String status, Long operatorId) {
        BigDataPlatformEvent event = BigDataPlatformEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("CHART_GEN_COMPLETED")
                .traceId(traceId)
                .chartId(chartId)
                .operatorId(operatorId)
                .status(status)
                .eventTimeMs(System.currentTimeMillis())
                .build();
        publish(bigDataProperties.getKafka().getTopics().getChartGenCompleted(), event);
    }

    public void publishImportCompleted(String importType, Long operatorId, String status, String summary) {
        BigDataPlatformEvent event = BigDataPlatformEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("IMPORT_COMPLETED")
                .importType(importType)
                .operatorId(operatorId)
                .status(status)
                .payload(summary)
                .eventTimeMs(System.currentTimeMillis())
                .build();
        publish(bigDataProperties.getKafka().getTopics().getImportCompleted(), event);
    }

    private void publish(String topic, BigDataPlatformEvent event) {
        String json = GSON.toJson(event);
        persistToClickHouse(event, json);
        if (!bigDataProperties.getKafka().isEnabled() || kafkaTemplate == null) {
            log.debug("Kafka 未启用，事件仅本地记录: type={}", event.getEventType());
            return;
        }
        if (StringUtils.isBlank(topic)) {
            return;
        }
        try {
            String key = event.getChartId() != null ? String.valueOf(event.getChartId()) : event.getEventId();
            kafkaTemplate.send(topic, key, json);
            log.info("Kafka event sent topic={} type={}", topic, event.getEventType());
        } catch (Exception e) {
            log.warn("Kafka 发送失败（不影响主流程）: {}", e.getMessage());
        }
    }

    private void persistToClickHouse(BigDataPlatformEvent event, String json) {
        if (clickHouseMetricRepository == null) {
            return;
        }
        try {
            clickHouseMetricRepository.insertPlatformEvent(
                    event.getEventId(),
                    event.getEventType(),
                    event.getTraceId(),
                    event.getChartId(),
                    json);
        } catch (Exception e) {
            log.warn("ClickHouse platform_event 写入失败: {}", e.getMessage());
        }
    }
}
