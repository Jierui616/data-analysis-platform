package com.yupi.springbootinit.service;

import com.yupi.springbootinit.model.entity.User;
import java.util.Map;

/**
 * 风险与信用画像：复用餐饮 BI 同源数据，不侵入既有 RestaurantBiService 行为。
 * <p>
 * 留痕表 {@code risk_portrait_hit_log} 与 MySQL 英文 COMMENT 对齐，DDL 见 {@code sql/risk_portrait_schema.sql}；
 * 分页接口 {@code /risk-portrait/hit-log/page}，决策接口 {@code /risk-portrait/decision}（persist 时写表）。
 */
public interface RiskPortraitService {

    /**
     * 总览：可见范围内有数据的门店数、高风险门店数、待关注预警条数等。
     */
    Map<String, Object> getPortraitOverview(User loginUser, Map<String, Object> requestBody);

    /**
     * 单店画像：稳定性 / 可信度 / 价值 及说明、建议（基于门店日指标与预警事件）。
     */
    Map<String, Object> getStorePortrait(User loginUser, Map<String, Object> requestBody);

    /**
     * 命中与留痕分页（当前用户；管理员可看全部）。数据源表 risk_portrait_hit_log，列含义与 DDL 英文 COMMENT 一致。
     */
    Map<String, Object> pageHitLog(User loginUser, Map<String, Object> requestBody);

    /**
     * 统一决策：按场景输出命中规则与建议；persist=true 时插入 risk_portrait_hit_log（action/ruleCode/ruleSummary/detailJson 等）。
     */
    Map<String, Object> runDecision(User loginUser, Map<String, Object> requestBody);

    /** 按会员 ID、手机号或支付账号查询交易与券核销明细。 */
    Map<String, Object> getMemberActivityDetails(User loginUser, Map<String, Object> requestBody);

    /** 总部会员关注名单分页（风险等级、门店关键词等筛选）。 */
    Map<String, Object> getMemberWatchlist(User loginUser, Map<String, Object> requestBody);

    /** 匿名用户弱标识分桶聚合与脱敏摘要。 */
    Map<String, Object> getAnonymousPortraitSummary(User loginUser, Map<String, Object> requestBody);
}
