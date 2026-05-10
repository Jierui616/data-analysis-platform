# 建表脚本
-- 创建库
create database if not exists yubi;

-- 切换库
use yubi;

-- 用户表
create table if not exists `user`
(
    id           bigint auto_increment comment 'id' primary key,
    userAccount  varchar(256)                           not null comment '账号',
    userPassword varchar(512)                           not null comment '密码',
    userName     varchar(256)                           null comment '用户昵称',
    userAvatar   varchar(1024)                          null comment '用户头像',
    userRole     varchar(256) default 'user'            not null comment '用户角色：user/admin',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint      default 0                 not null comment '是否删除',
    index idx_userAccount (userAccount)
    ) comment '用户' collate = utf8mb4_unicode_ci;

-- 图表表
create table if not exists chart
(
    id           bigint auto_increment comment 'id' primary key,
    goal				 text  null comment '分析目标',
    `name`               varchar(128) null comment '图表名称',
    chartData    text  null comment '图表数据',
    chartDataRef text  null comment '图表原始数据外置对象引用 JSON（storage.chartData 开启时使用）',
    chartType	   varchar(128) null comment '图表类型',
    genChart		 text	 null comment '生成的图表数据',
    genResult		 text	 null comment '生成的分析结论',
    resultMeta    text   null comment '结构化结果（摘要/KPI/问题/行动）',
    status       varchar(128) not null default 'wait' comment 'wait,running,succeed,failed',
    execMessage  text   null comment '执行信息',
    userId       bigint null comment '创建用户 id',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint      default 0                 not null comment '是否删除',
    -- bi-center / chart 列表：按 userId + isDelete 过滤，并按 createTime 倒序取最近
    index idx_chart_user_delete_ctime (userId, isDelete, createTime)
) comment '图表信息表' collate = utf8mb4_unicode_ci;

-- 如果你的 chart 表已存在且不想重跑建表，可单独执行下面语句（如索引已存在会报错，忽略即可）
-- create index idx_chart_user_delete_ctime on chart(userId, isDelete, createTime);

-- 可选：历史库升级
-- alter table chart add column resultMeta text null comment '结构化结果（摘要/KPI/问题/行动）' after genResult;

-- 图表行动项表（管理者闭环执行）
create table if not exists chart_action
(
    id              bigint auto_increment comment 'id' primary key,
    chartId         bigint                                 not null comment '图表 id',
    task            varchar(512)                           not null comment '行动项',
    owner           varchar(128)                           null comment '负责人',
    dueDate         varchar(32)                            null comment '截止时间（YYYY-MM-DD）',
    status          varchar(32)  default 'pending'         not null comment 'pending/in_progress/completed',
    priority        varchar(32)  default 'medium'          not null comment 'high/medium/low',
    expectedBenefit varchar(256)                           null comment '预期收益',
    createTime      datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime      datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete        tinyint      default 0                 not null comment '是否删除',
    index idx_chart_action_chart_status (chartId, status)
) comment '图表行动项表' collate = utf8mb4_unicode_ci;

-- 血缘事件表（记录来源 -> 清洗 -> 计算 -> 图表 -> AI 全链路节点）
create table if not exists lineage_event
(
    id           bigint auto_increment comment 'id' primary key,
    traceId      varchar(64)                           not null comment '一次分析链路唯一标识',
    chartId      bigint                                null comment '关联图表 id',
    eventType    varchar(64)                           not null comment '事件类型',
    inputDigest  varchar(128)                          null comment '输入摘要 hash',
    outputDigest varchar(128)                          null comment '输出摘要 hash',
    ruleVersion  varchar(64)                           null comment '规则版本',
    logicRef     varchar(128)                          null comment '逻辑引用（公式/Prompt 版本）',
    eventMeta    text                                  null comment '事件元数据 JSON',
    status       varchar(32) default 'succeed'         not null comment 'succeed/failed',
    operatorId   bigint                                null comment '操作者（用户/系统）',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint      default 0                not null comment '是否删除',
    index idx_traceId (traceId),
    index idx_chartId_createTime (chartId, createTime)
) comment '数据血缘事件表' collate = utf8mb4_unicode_ci;

-- 可解释快照表（沉淀指标口径、图表依据、AI Prompt/模型信息）
create table if not exists explain_snapshot
(
    id            bigint auto_increment comment 'id' primary key,
    traceId       varchar(64)                            not null comment '链路 traceId',
    chartId       bigint                                 null comment '关联图表 id',
    explainType   varchar(64)                            not null comment 'metric/chart/ai',
    explainData   text                                   not null comment '解释内容 JSON',
    promptVersion varchar(64)                            null comment 'Prompt 版本',
    modelName     varchar(128)                           null comment '模型标识',
    createTime    datetime      default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime    datetime      default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete      tinyint       default 0                not null comment '是否删除',
    index idx_traceId_type (traceId, explainType),
    index idx_chartId_createTime (chartId, createTime)
) comment '可解释分析快照表' collate = utf8mb4_unicode_ci;

-- 资产血缘节点表（dataset/task/metric/chart）
create table if not exists lineage_node
(
    id         bigint auto_increment comment 'id' primary key,
    nodeType   varchar(32)                            not null comment '节点类型',
    nodeKey    varchar(128)                           not null comment '节点唯一键',
    name       varchar(256)                           not null comment '节点显示名',
    chartId    bigint                                 null comment '关联图表 id',
    owner      varchar(64)                            null comment '责任人',
    metaJson   text                                   null comment '扩展元数据',
    createTime datetime      default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime datetime      default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete   tinyint       default 0                not null comment '是否删除',
    unique key uk_node_key (nodeKey),
    index idx_node_type (nodeType),
    index idx_chart_id (chartId)
) comment '资产血缘节点表' collate = utf8mb4_unicode_ci;

-- 资产血缘边表（节点关系）
create table if not exists lineage_edge
(
    id          bigint auto_increment comment 'id' primary key,
    fromNodeKey varchar(128)                           not null comment '起点 nodeKey',
    toNodeKey   varchar(128)                           not null comment '终点 nodeKey',
    edgeType    varchar(32)                            not null comment '关系类型 read/write/derive/use',
    runId       varchar(64)                            null comment '关联运行 runId',
    metaJson    text                                   null comment '扩展元数据',
    createTime  datetime      default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime  datetime      default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete    tinyint       default 0                not null comment '是否删除',
    index idx_from_node (fromNodeKey),
    index idx_to_node (toNodeKey),
    index idx_run_id (runId)
) comment '资产血缘边表' collate = utf8mb4_unicode_ci;

-- 血缘运行快照表（run 级汇总）
create table if not exists lineage_run
(
    id             bigint auto_increment comment 'id' primary key,
    runId          varchar(64)                            not null comment '运行 id',
    traceId        varchar(64)                            not null comment '关联 traceId',
    chartId        bigint                                 null comment '关联图表 id',
    status         varchar(32)                            not null comment '运行状态',
    startTime      datetime                               null comment '开始时间',
    endTime        datetime                               null comment '结束时间',
    rowCountIn     bigint                                 null comment '输入行数',
    rowCountOut    bigint                                 null comment '输出行数',
    partitionRange varchar(256)                           null comment '分区范围',
    costMs         bigint                                 null comment '运行耗时毫秒',
    metaJson       text                                   null comment '扩展元数据',
    createTime     datetime      default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime     datetime      default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete       tinyint       default 0                not null comment '是否删除',
    unique key uk_run_id (runId),
    index idx_trace_id (traceId),
    index idx_chart_id (chartId)
) comment '血缘运行快照表' collate = utf8mb4_unicode_ci;

-- 血缘修复任务表（低可信边治理闭环）
create table if not exists lineage_fix_task
(
    id                bigint auto_increment comment 'id' primary key,
    chartId           bigint                                 not null comment '图表 id',
    taskKey           varchar(128)                           not null comment '任务唯一键',
    fromNodeKey       varchar(128)                           not null comment '起点 nodeKey',
    toNodeKey         varchar(128)                           not null comment '终点 nodeKey',
    edgeType          varchar(32)                            not null comment '边类型',
    evidenceSource    varchar(32)                            null comment '证据来源',
    confidence        varchar(32)                            null comment '可信度',
    fixSuggestion     text                                   null comment '修复建议',
    eventMetaTemplate text                                   null comment '推荐模板',
    status            varchar(32)  default 'pending'         not null comment 'pending/resolved',
    createTime        datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime        datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete          tinyint      default 0                 not null comment '是否删除',
    unique key uk_task_key (taskKey),
    index idx_chart_status (chartId, status)
) comment '血缘修复任务表' collate = utf8mb4_unicode_ci;
