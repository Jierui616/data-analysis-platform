package com.yupi.springbootinit.model.dto.bigdata;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 平台事件（Kafka JSON），供后续血缘与 ClickHouse platform_event_ch 消费。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BigDataPlatformEvent implements Serializable {

    private String eventId;

    private String eventType;

    private String traceId;

    private Long chartId;

    private String importType;

    private Long operatorId;

    private String status;

    private String payload;

    private long eventTimeMs;
}
