-- 连锁餐饮领域扩展表（MVP + 占位能力）
-- 使用前请先执行 create_table.sql
use yubi;

create table if not exists `store`
(
    id           bigint auto_increment comment 'id' primary key,
    storeCode    varchar(64)                            not null comment '门店编码',
    storeName    varchar(128)                           not null comment '门店名称',
    region       varchar(64)                            not null comment '区域',
    city         varchar(64)                            null comment '城市',
    areaSqm      decimal(10, 2)                         null comment '面积(平米)',
    status       varchar(32)  default 'open'           not null comment 'open/closed',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint      default 0                 not null comment '是否删除',
    unique key uk_store_code (storeCode),
    index idx_region_status (region, status)
) comment '门店表' collate = utf8mb4_unicode_ci;

create table if not exists store_user_role
(
    id           bigint auto_increment comment 'id' primary key,
    userId       bigint                                 not null comment '用户 id',
    storeId      bigint                                 null comment '门店 id（hq 可为空）',
    roleCode     varchar(32)                            not null comment 'hq_manager/region_manager/store_manager',
    region       varchar(64)                            null comment '区域（region_manager 需要）',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint      default 0                 not null comment '是否删除',
    index idx_user_role (userId, roleCode),
    index idx_store_role (storeId, roleCode)
) comment '用户门店角色表' collate = utf8mb4_unicode_ci;

create table if not exists store_daily_metric
(
    id               bigint auto_increment comment 'id' primary key,
    statDate         date                                   not null comment '统计日期',
    storeId          bigint                                 not null comment '门店 id',
    revenue          decimal(14, 2)                         not null default 0 comment '营业额',
    grossMarginRate  decimal(8, 4)                          not null default 0 comment '毛利率',
    avgTicket        decimal(10, 2)                         not null default 0 comment '客单价',
    tableTurnover    decimal(8, 2)                          not null default 0 comment '翻台率',
    seatOccupancy    decimal(8, 4)                          not null default 0 comment '上座率',
    takeawayRatio    decimal(8, 4)                          not null default 0 comment '外卖占比',
    dineInRatio      decimal(8, 4)                          not null default 0 comment '堂食占比',
    prepTimeAvgMin   decimal(8, 2)                          not null default 0 comment '平均出餐时长(分钟)',
    cancelRate       decimal(8, 4)                          not null default 0 comment '退单率',
    badReviewRate    decimal(8, 4)                          not null default 0 comment '差评率',
    createTime       datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime       datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete         tinyint      default 0                 not null comment '是否删除',
    unique key uk_store_daily (statDate, storeId),
    index idx_store_date (storeId, statDate)
) comment '门店日经营指标表' collate = utf8mb4_unicode_ci;

create table if not exists dish_daily_metric
(
    id               bigint auto_increment comment 'id' primary key,
    statDate         date                                   not null comment '统计日期',
    storeId          bigint                                 not null comment '门店 id',
    dishName         varchar(128)                           not null comment '菜品名',
    categoryName     varchar(64)                            null comment '菜品类目',
    salesQty         bigint                                 not null default 0 comment '销量',
    revenue          decimal(14, 2)                         not null default 0 comment '销售额',
    grossMarginRate  decimal(8, 4)                          not null default 0 comment '毛利率',
    refundRate       decimal(8, 4)                          not null default 0 comment '退单率',
    createTime       datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime       datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete         tinyint      default 0                 not null comment '是否删除',
    unique key uk_dish_daily (statDate, storeId, dishName),
    index idx_store_category (storeId, categoryName)
) comment '菜品日指标表' collate = utf8mb4_unicode_ci;

create table if not exists campaign_metric
(
    id                  bigint auto_increment comment 'id' primary key,
    statDate            date                                   not null comment '统计日期',
    storeId             bigint                                 not null comment '门店 id',
    campaignName        varchar(128)                           not null comment '活动名称',
    channelName         varchar(64)                            not null comment '渠道',
    spendAmount         decimal(14, 2)                         not null default 0 comment '投放花费',
    exposureCount       bigint                                 not null default 0 comment '曝光',
    clickCount          bigint                                 not null default 0 comment '点击',
    orderCount          bigint                                 not null default 0 comment '下单数',
    conversionRate      decimal(8, 4)                          not null default 0 comment '转化率',
    roi                 decimal(10, 4)                         not null default 0 comment 'ROI',
    createTime          datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime          datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete            tinyint      default 0                 not null comment '是否删除',
    index idx_store_campaign_date (storeId, campaignName, statDate)
) comment '活动复盘指标表' collate = utf8mb4_unicode_ci;

create table if not exists store_cost_daily_metric
(
    id                  bigint auto_increment comment 'id' primary key,
    statDate            date                                   not null comment '统计日期',
    storeId             bigint                                 not null comment '门店 id',
    ingredientCost      decimal(14, 2)                         not null default 0 comment '食材成本',
    packagingCost       decimal(14, 2)                         not null default 0 comment '包装成本',
    energyCost          decimal(14, 2)                         not null default 0 comment '能耗成本',
    laborCost           decimal(14, 2)                         not null default 0 comment '人工成本',
    wasteAmount         decimal(14, 2)                         not null default 0 comment '报损金额',
    wasteRate           decimal(8, 4)                          not null default 0 comment '报损率',
    inventoryLossRate   decimal(8, 4)                          not null default 0 comment '库存损耗率',
    platformCommission  decimal(14, 2)                         not null default 0 comment '平台佣金',
    createTime          datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime          datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete            tinyint      default 0                 not null comment '是否删除',
    unique key uk_store_cost_daily (statDate, storeId),
    index idx_cost_store_date (storeId, statDate)
) comment '门店成本损耗日指标表' collate = utf8mb4_unicode_ci;

create table if not exists store_staff_daily_metric
(
    id                  bigint auto_increment comment 'id' primary key,
    statDate            date                                   not null comment '统计日期',
    storeId             bigint                                 not null comment '门店 id',
    shiftType           varchar(32)                            not null comment '班次：lunch/dinner/all_day',
    plannedStaff        bigint                                 not null default 0 comment '计划人数',
    actualStaff         bigint                                 not null default 0 comment '实到人数',
    workHours           decimal(10, 2)                         not null default 0 comment '工时',
    overtimeHours       decimal(10, 2)                         not null default 0 comment '加班工时',
    absentCount         bigint                                 not null default 0 comment '缺勤人数',
    ordersCount         bigint                                 not null default 0 comment '订单数',
    revenue             decimal(14, 2)                         not null default 0 comment '营收',
    avgPrepTimeMin      decimal(8, 2)                          not null default 0 comment '平均出餐时长(分钟)',
    createTime          datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime          datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete            tinyint      default 0                 not null comment '是否删除',
    unique key uk_store_staff_daily (statDate, storeId, shiftType),
    index idx_staff_store_date (storeId, statDate)
) comment '门店排班人效日指标表' collate = utf8mb4_unicode_ci;

create table if not exists restaurant_import_log
(
    id            bigint auto_increment comment 'id' primary key,
    userId        bigint                                 null comment '操作人',
    importType    varchar(32)                            not null comment 'cost/staff',
    importMode    varchar(32)                            not null default 'overwrite' comment 'overwrite/replace_range',
    totalRows     int                                    not null default 0 comment '总行数',
    successRows   int                                    not null default 0 comment '成功行数',
    invalidRows   int                                    not null default 0 comment '无效门店行数',
    startDate     date                                   null comment '覆盖起始日期',
    endDate       date                                   null comment '覆盖结束日期',
    status        varchar(16)                            not null default 'ok' comment 'ok/failed',
    message       varchar(255)                           null comment '消息',
    createTime    datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime    datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete      tinyint      default 0                 not null comment '是否删除',
    index idx_user_time (userId, createTime),
    index idx_type_time (importType, createTime)
) comment '餐饮导入日志表' collate = utf8mb4_unicode_ci;

create table if not exists alert_rule
(
    id                bigint auto_increment comment 'id' primary key,
    ruleName          varchar(128)                           not null comment '规则名',
    metricCode        varchar(64)                            not null comment '指标编码',
    compareOp         varchar(16)                            not null comment 'lt/gt/lte/gte',
    thresholdValue    decimal(14, 4)                         not null comment '阈值',
    severity          varchar(16)  default 'P2'             not null comment 'P0/P1/P2',
    enabled           tinyint      default 1                not null comment '是否启用',
    createTime        datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime        datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete          tinyint      default 0                not null comment '是否删除',
    unique key uk_rule_name (ruleName),
    index idx_metric_enabled (metricCode, enabled)
) comment '预警规则表' collate = utf8mb4_unicode_ci;

create table if not exists alert_event
(
    id                bigint auto_increment comment 'id' primary key,
    statDate          date                                   not null comment '触发日期',
    storeId           bigint                                 not null comment '门店 id',
    ruleId            bigint                                 not null comment '规则 id',
    metricCode        varchar(64)                            not null comment '指标编码',
    metricValue       decimal(14, 4)                         not null comment '指标值',
    thresholdValue    decimal(14, 4)                         not null comment '阈值',
    severity          varchar(16)                            not null comment 'P0/P1/P2',
    status            varchar(16)  default 'open'           not null comment 'open/closed',
    message           varchar(512)                           null comment '告警文案',
    createTime        datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime        datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete          tinyint      default 0                not null comment '是否删除',
    index idx_store_date_severity (storeId, statDate, severity),
    index idx_rule_status (ruleId, status)
) comment '预警事件表' collate = utf8mb4_unicode_ci;

-- 初始化门店
insert into `store` (storeCode, storeName, region, city, areaSqm, status)
values ('BJ001', '北京国贸店', '华北', '北京', 420.00, 'open'),
       ('SH001', '上海静安店', '华东', '上海', 360.00, 'open'),
       ('GZ001', '广州天河店', '华南', '广州', 390.00, 'open')
on duplicate key update updateTime = CURRENT_TIMESTAMP;

-- 初始化预警规则
insert into alert_rule (ruleName, metricCode, compareOp, thresholdValue, severity, enabled)
values ('店效周转偏低预警', 'tableTurnover', 'lt', 3.50, 'P1', 1),
       ('毛利率过低预警', 'grossMarginRate', 'lt', 0.55, 'P1', 1),
       ('退单率过高预警', 'cancelRate', 'gt', 0.06, 'P2', 1),
       ('拣货与收银响应偏慢预警', 'prepTimeAvgMin', 'gt', 22.00, 'P2', 1)
on duplicate key update updateTime = CURRENT_TIMESTAMP;

-- 用户角色示例（假设 id=1 为管理员）
insert into store_user_role (userId, storeId, roleCode, region)
values (1, null, 'hq_manager', null)
on duplicate key update updateTime = CURRENT_TIMESTAMP;

-- 门店日经营示例数据
insert into store_daily_metric
(statDate, storeId, revenue, grossMarginRate, avgTicket, tableTurnover, seatOccupancy, takeawayRatio, dineInRatio, prepTimeAvgMin, cancelRate, badReviewRate)
values
('2026-04-21', 1, 125000.00, 0.6120, 56.00, 4.20, 0.71, 0.38, 0.62, 18.20, 0.0280, 0.0120),
('2026-04-22', 1, 128500.00, 0.6050, 57.50, 4.30, 0.72, 0.39, 0.61, 18.00, 0.0290, 0.0110),
('2026-04-23', 1, 132300.00, 0.5980, 58.10, 4.45, 0.74, 0.40, 0.60, 17.80, 0.0300, 0.0110),
('2026-04-24', 1, 130000.00, 0.5920, 56.90, 4.10, 0.69, 0.41, 0.59, 18.50, 0.0340, 0.0130),
('2026-04-25', 1, 138200.00, 0.5880, 59.30, 4.55, 0.76, 0.42, 0.58, 19.10, 0.0370, 0.0150),
('2026-04-26', 1, 140800.00, 0.5800, 60.00, 4.70, 0.78, 0.43, 0.57, 19.30, 0.0400, 0.0160),
('2026-04-27', 1, 142500.00, 0.5760, 60.60, 4.75, 0.79, 0.44, 0.56, 19.80, 0.0430, 0.0180),
('2026-04-21', 2, 106000.00, 0.6180, 54.30, 3.90, 0.66, 0.36, 0.64, 17.50, 0.0240, 0.0100),
('2026-04-22', 2, 108300.00, 0.6140, 55.00, 4.00, 0.68, 0.36, 0.64, 17.40, 0.0250, 0.0100),
('2026-04-23', 2, 111200.00, 0.6090, 55.60, 4.08, 0.69, 0.37, 0.63, 17.30, 0.0260, 0.0110),
('2026-04-24', 2, 109900.00, 0.6030, 55.20, 3.95, 0.67, 0.38, 0.62, 17.90, 0.0290, 0.0110),
('2026-04-25', 2, 115000.00, 0.5990, 56.30, 4.12, 0.70, 0.39, 0.61, 18.20, 0.0310, 0.0120),
('2026-04-26', 2, 118800.00, 0.5940, 57.00, 4.20, 0.72, 0.40, 0.60, 18.40, 0.0340, 0.0130),
('2026-04-27', 2, 121600.00, 0.5890, 57.80, 4.28, 0.73, 0.41, 0.59, 18.70, 0.0360, 0.0140),
('2026-04-21', 3, 98000.00, 0.6020, 51.80, 3.70, 0.63, 0.47, 0.53, 20.30, 0.0420, 0.0190),
('2026-04-22', 3, 101200.00, 0.5980, 52.40, 3.78, 0.64, 0.48, 0.52, 20.10, 0.0440, 0.0200),
('2026-04-23', 3, 103600.00, 0.5920, 52.90, 3.82, 0.65, 0.49, 0.51, 20.50, 0.0460, 0.0210),
('2026-04-24', 3, 102500.00, 0.5880, 52.70, 3.76, 0.64, 0.49, 0.51, 21.00, 0.0480, 0.0220),
('2026-04-25', 3, 106800.00, 0.5830, 53.50, 3.88, 0.66, 0.50, 0.50, 21.30, 0.0500, 0.0240),
('2026-04-26', 3, 109200.00, 0.5780, 54.10, 3.96, 0.67, 0.51, 0.49, 21.60, 0.0520, 0.0250),
('2026-04-27', 3, 111700.00, 0.5730, 54.80, 4.02, 0.68, 0.52, 0.48, 22.10, 0.0550, 0.0260)
on duplicate key update updateTime = CURRENT_TIMESTAMP;

-- 菜品指标示例数据
insert into dish_daily_metric
(statDate, storeId, dishName, categoryName, salesQty, revenue, grossMarginRate, refundRate)
values
('2026-04-27', 1, '招牌酸菜鱼', '主菜', 320, 49600.00, 0.63, 0.0140),
('2026-04-27', 1, '金汤肥牛', '主菜', 280, 44800.00, 0.60, 0.0160),
('2026-04-27', 1, '手工冰粉', '甜品', 410, 12300.00, 0.71, 0.0060),
('2026-04-27', 1, '鲜榨柠檬茶', '饮品', 520, 15600.00, 0.76, 0.0050),
('2026-04-27', 1, '爆炒牛蛙', '主菜', 95, 19950.00, 0.47, 0.0960),
('2026-04-27', 2, '招牌酸菜鱼', '主菜', 260, 40300.00, 0.61, 0.0130),
('2026-04-27', 2, '金汤肥牛', '主菜', 220, 35000.00, 0.59, 0.0170),
('2026-04-27', 2, '手工冰粉', '甜品', 330, 9900.00, 0.69, 0.0060),
('2026-04-27', 2, '鲜榨柠檬茶', '饮品', 450, 13500.00, 0.74, 0.0060),
('2026-04-27', 2, '藤椒鸡', '主菜', 88, 16720.00, 0.49, 0.0820),
('2026-04-27', 3, '招牌酸菜鱼', '主菜', 210, 32550.00, 0.58, 0.0170),
('2026-04-27', 3, '金汤肥牛', '主菜', 186, 29580.00, 0.56, 0.0190),
('2026-04-27', 3, '手工冰粉', '甜品', 280, 8400.00, 0.66, 0.0090),
('2026-04-27', 3, '鲜榨柠檬茶', '饮品', 390, 11700.00, 0.71, 0.0080),
('2026-04-27', 3, '麻辣香锅', '主菜', 76, 17480.00, 0.45, 0.1030)
on duplicate key update updateTime = CURRENT_TIMESTAMP;

-- 活动复盘示例数据
insert into campaign_metric
(statDate, storeId, campaignName, channelName, spendAmount, exposureCount, clickCount, orderCount, conversionRate, roi)
values
('2026-04-23', 1, '春季焕新套餐', '抖音团购', 9800.00, 105000, 9200, 1160, 0.1261, 3.42),
('2026-04-24', 1, '春季焕新套餐', '美团', 8600.00, 82000, 6100, 930, 0.1525, 3.66),
('2026-04-25', 1, '春季焕新套餐', '私域社群', 3200.00, 26000, 3900, 760, 0.1949, 4.10),
('2026-04-23', 2, '春季焕新套餐', '抖音团购', 7600.00, 76000, 6200, 790, 0.1274, 3.18),
('2026-04-24', 2, '春季焕新套餐', '美团', 6900.00, 64000, 5200, 730, 0.1404, 3.34),
('2026-04-25', 2, '春季焕新套餐', '私域社群', 2600.00, 22000, 3200, 620, 0.1938, 3.92),
('2026-04-23', 3, '春季焕新套餐', '抖音团购', 6400.00, 68000, 5100, 620, 0.1216, 2.86),
('2026-04-24', 3, '春季焕新套餐', '美团', 6200.00, 59000, 4700, 590, 0.1255, 3.01),
('2026-04-25', 3, '春季焕新套餐', '私域社群', 2100.00, 18000, 2600, 470, 0.1808, 3.45)
on duplicate key update updateTime = CURRENT_TIMESTAMP;

-- 预警事件示例数据
insert into alert_event
(statDate, storeId, ruleId, metricCode, metricValue, thresholdValue, severity, status, message)
values
('2026-04-27', 3, 1, 'tableTurnover', 3.20, 3.50, 'P1', 'open', '门店店效周转低于阈值，建议优化高峰补货与收银动线'),
('2026-04-27', 3, 3, 'cancelRate', 0.0550, 0.0600, 'P2', 'open', '门店退单率临近阈值，请复查拣货与收银准确性'),
('2026-04-27', 1, 4, 'prepTimeAvgMin', 19.80, 22.00, 'P2', 'closed', '门店拣货与收银响应时长已恢复至正常水平')
on duplicate key update updateTime = CURRENT_TIMESTAMP;
