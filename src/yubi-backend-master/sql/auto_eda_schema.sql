-- auto_eda_schema.sql
-- InfoGain-EDA 探索会话、假设与查询日志表

CREATE TABLE IF NOT EXISTS auto_eda_session (
    id              BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    session_id      VARCHAR(64)     NOT NULL COMMENT '会话UUID',
    chart_id        BIGINT          NOT NULL COMMENT '关联图表ID',
    user_id         BIGINT          NOT NULL COMMENT '发起用户ID',
    status          VARCHAR(32)     NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/COMPLETED/STOPPED/FAILED',
    config_json     TEXT            COMMENT '探索配置JSON (maxQueryBudget, targetMetrics, weights)',
    state_json      MEDIUMTEXT      COMMENT '当前探索状态快照 (S_explored, trace)',
    result_json     MEDIUMTEXT      COMMENT '最终报告JSON',
    total_queries   INT             DEFAULT 0 COMMENT '已执行查询总数',
    start_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    end_time        DATETIME        COMMENT '结束时间',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_delete       TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_id (session_id),
    KEY idx_user_id (user_id),
    KEY idx_chart_id (chart_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AutoEDA探索会话';

CREATE TABLE IF NOT EXISTS auto_eda_hypothesis (
    id              BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    session_id      VARCHAR(64)     NOT NULL COMMENT '关联会话ID',
    hypothesis_id   VARCHAR(64)     NOT NULL COMMENT '假设唯一ID',
    type            VARCHAR(32)     NOT NULL COMMENT 'anomaly/correlation/trend/segment_diff',
    statement       VARCHAR(1024)   NOT NULL COMMENT '假设自然语言陈述',
    evidence_json   TEXT            COMMENT '证据链JSON',
    confidence_prior    DOUBLE      DEFAULT 0.3 COMMENT '先验概率',
    confidence_posterior DOUBLE   DEFAULT 0.3 COMMENT '后验概率',
    status          VARCHAR(32)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACTIVE/VERIFIED/DISCARDED',
    lineage_path    VARCHAR(1024)   COMMENT '血缘链路径',
    parent_hypothesis_id VARCHAR(64) COMMENT '父假设ID',
    source_query_ids    VARCHAR(1024) COMMENT '来源查询ID列表',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_hypothesis (session_id, hypothesis_id),
    KEY idx_session_status (session_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AutoEDA假设';

CREATE TABLE IF NOT EXISTS auto_eda_query_log (
    id              BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    session_id      VARCHAR(64)     NOT NULL COMMENT '关联会话ID',
    query_id        VARCHAR(64)     NOT NULL COMMENT '查询唯一ID',
    query_sql       TEXT            NOT NULL COMMENT '实际执行的SQL',
    query_dimensions VARCHAR(512)   COMMENT '查询涉及的维度组合',
    ig_score        DOUBLE          COMMENT '信息增益分数',
    ig_breakdown_json VARCHAR(512)  COMMENT 'IG各分项得分JSON',
    result_summary  TEXT            COMMENT '查询结果摘要',
    result_row_count INT            DEFAULT 0 COMMENT '结果行数',
    execution_ms    INT             DEFAULT 0 COMMENT '执行耗时毫秒',
    cost_estimated  INT             DEFAULT 0 COMMENT '预估扫描行数',
    lineage_path    VARCHAR(1024)   COMMENT '血缘路径',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_query (session_id, query_id),
    KEY idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AutoEDA查询日志';
