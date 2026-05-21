-- ClickHouse 初始化（docker-entrypoint-initdb.d 挂载执行）
CREATE DATABASE IF NOT EXISTS yubi_analytics;

-- 门店日指标 OLAP 表（由 MySQL store_daily_metric 同步）
CREATE TABLE IF NOT EXISTS yubi_analytics.store_daily_metric_ch
(
    statDate          Date,
    storeId           UInt64,
    revenue           Decimal(18, 2),
    grossMarginRate   Decimal(18, 4),
    avgTicket         Decimal(18, 2),
    tableTurnover     Decimal(18, 4),
    seatOccupancy     Decimal(18, 4),
    takeawayRatio     Decimal(18, 4),
    dineInRatio       Decimal(18, 4),
    prepTimeAvgMin    Decimal(18, 2),
    cancelRate        Decimal(18, 4),
    badReviewRate     Decimal(18, 4),
    mysqlId           UInt64,
    syncedAt          DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(syncedAt)
PARTITION BY toYYYYMM(statDate)
ORDER BY (storeId, statDate, mysqlId);

-- 平台事件事实表（Kafka 消费者 / 血缘二期可写入）
CREATE TABLE IF NOT EXISTS yubi_analytics.platform_event_ch
(
    eventId     String,
    eventType   String,
    traceId     String,
    chartId     Nullable(UInt64),
    payload     String,
    eventTime   DateTime DEFAULT now()
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(eventTime)
ORDER BY (eventTime, eventType, eventId)
TTL eventTime + INTERVAL 365 DAY;
