-- =============================================================================
-- Risk portrait: persistence for hit log / decision audit
-- Run once against database `yubi` (see spring.datasource.url). Prerequisite:
--   sql/create_table.sql (base tables) so app login / user table exists.
-- Java entity: com.yupi.springbootinit.model.entity.RiskPortraitHitLog
-- APIs: POST /api/risk-portrait/hit-log/page , POST /api/risk-portrait/decision
-- DB COMMENT strings are English-only (UTF-8 pipe-safe from Windows CLI).
-- =============================================================================
use yubi;

create table if not exists risk_portrait_hit_log
(
    id           bigint auto_increment comment 'pk' primary key,
    userId       bigint                                 not null comment 'operator user id',
    statTime     datetime     default CURRENT_TIMESTAMP not null comment 'hit time',
    subjectType  varchar(32)                            not null comment 'store member anonymous',
    subjectId    varchar(128)                           not null comment 'subject key',
    scene        varchar(64)                            not null comment 'store_credit coupon_control trade_intercept',
    action       varchar(32)                            not null comment 'INTERCEPT REVIEW PASS DECISION',
    ruleCode     varchar(256) default ''                not null comment 'rule code',
    ruleSummary  varchar(512)                           null comment 'rule summary',
    detailJson   mediumtext                             null comment 'decision snapshot json',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment 'created',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment 'updated',
    isDelete     tinyint      default 0                 not null comment 'logic delete',
    index idx_user_time (userId, statTime),
    index idx_scene_time (scene, statTime)
) comment 'risk_portrait_hit_log' collate = utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- Member portrait import tables (watchlist + activity lines). Used when
-- risk-portrait.member-demo-mode=false (see application.yml). English COMMENT only.
-- -----------------------------------------------------------------------------
create table if not exists risk_portrait_member_watchlist
(
    id                   bigint auto_increment comment 'pk' primary key,
    memberId             varchar(64)                             not null comment 'stable member key',
    displayRef           varchar(64)                             not null comment 'masked label for ui',
    riskLevel            varchar(32)                             not null comment 'high mid low chinese label',
    tags                 varchar(512) default ''                 not null comment 'comma list or json array',
    orderCount           int          default 0                  not null comment 'order count in window',
    couponRedeemCount    int          default 0                  not null comment 'coupon redeem count',
    refundHint           varchar(32)  default ''                 not null comment 'refund tendency label',
    lastActive           datetime                                null comment 'last activity time',
    topStore             varchar(128) default ''                 not null comment 'primary store name',
    periodStart          date                                    not null comment 'row coverage start',
    periodEnd            date                                    not null comment 'row coverage end',
    createTime           datetime     default CURRENT_TIMESTAMP   not null comment 'created',
    updateTime           datetime     default CURRENT_TIMESTAMP   not null on update CURRENT_TIMESTAMP comment 'updated',
    isDelete             tinyint      default 0                  not null comment 'logic delete',
    index idx_member_watch_period (periodStart, periodEnd),
    index idx_member_watch_member (memberId),
    index idx_member_watch_risk (riskLevel)
) comment 'risk_portrait_member_watchlist' collate = utf8mb4_unicode_ci;

create table if not exists risk_portrait_member_order_line
(
    id             bigint auto_increment comment 'pk' primary key,
    memberId       varchar(64)                           not null comment 'member key',
    txnTime        datetime                              not null comment 'transaction time',
    storeName      varchar(128) default ''               not null comment 'store display name',
    amount         decimal(14, 2) default 0.00           not null comment 'order amount',
    channel        varchar(64)  default ''               not null comment 'pay channel label',
    orderNoMasked  varchar(64)  default ''               not null comment 'masked order id',
    createTime     datetime     default CURRENT_TIMESTAMP not null comment 'created',
    updateTime     datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment 'updated',
    isDelete       tinyint      default 0                 not null comment 'logic delete',
    index idx_morder_member_time (memberId, txnTime)
) comment 'risk_portrait_member_order_line' collate = utf8mb4_unicode_ci;

create table if not exists risk_portrait_member_coupon_line
(
    id              bigint auto_increment comment 'pk' primary key,
    memberId        varchar(64)                           not null comment 'member key',
    txnTime         datetime                              not null comment 'redeem time',
    storeName       varchar(128) default ''               not null comment 'store display name',
    couponName      varchar(128) default ''               not null comment 'coupon name',
    faceValue       decimal(14, 2) default 0.00           not null comment 'face value',
    discountAmount  decimal(14, 2) default 0.00           not null comment 'discount amount',
    status          varchar(32)  default ''               not null comment 'status label',
    createTime      datetime     default CURRENT_TIMESTAMP not null comment 'created',
    updateTime      datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment 'updated',
    isDelete        tinyint      default 0                 not null comment 'logic delete',
    index idx_mcoupon_member_time (memberId, txnTime)
) comment 'risk_portrait_member_coupon_line' collate = utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- Anonymous portrait bucket rows. Used when risk-portrait.anonymous-demo-mode=false.
-- -----------------------------------------------------------------------------
create table if not exists risk_portrait_anonymous_cluster
(
    id                   bigint auto_increment comment 'pk' primary key,
    anonymousBucketId    varchar(64)                              not null comment 'bucket id shown in ui',
    linkedWeakSignals    varchar(1024) default ''                   not null comment 'json array or comma separated masked signals',
    eventCount7d         int           default 0                    not null comment 'events last 7d',
    riskTag              varchar(32)   default ''                   not null comment 'risk label',
    lastSeen             datetime                                   null comment 'last activity',
    topStoreHint         varchar(128)  default ''                   not null comment 'store hint',
    createTime           datetime      default CURRENT_TIMESTAMP    not null comment 'created',
    updateTime           datetime      default CURRENT_TIMESTAMP    not null on update CURRENT_TIMESTAMP comment 'updated',
    isDelete             tinyint       default 0                    not null comment 'logic delete',
    index idx_anon_bucket (anonymousBucketId),
    index idx_anon_risk (riskTag)
) comment 'risk_portrait_anonymous_cluster' collate = utf8mb4_unicode_ci;
