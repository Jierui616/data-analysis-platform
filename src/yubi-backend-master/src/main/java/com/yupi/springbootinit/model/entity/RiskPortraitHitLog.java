package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * Maps to table {@code risk_portrait_hit_log} (DDL: {@code sql/risk_portrait_schema.sql}).
 * Column semantics match MySQL COMMENT (English); used by {@code POST /api/risk-portrait/hit-log/page}
 * and written when {@code POST /api/risk-portrait/decision} runs with {@code persist=true}.
 */
@TableName(value = "risk_portrait_hit_log")
@Data
public class RiskPortraitHitLog implements Serializable {

    /** Primary key (auto increment). */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** Operator user id (who triggered the decision / audit row). */
    private Long userId;

    /** Business hit time for this log row. */
    private Date statTime;

    /** Subject domain: store | member | anonymous. */
    private String subjectType;

    /** Stable subject key within subjectType (e.g. store id string). */
    private String subjectId;

    /** Decision scene: store_credit | coupon_control | trade_intercept. */
    private String scene;

    /** Outcome bucket: INTERCEPT | REVIEW | PASS | DECISION. */
    private String action;

    /** Machine-readable rule identifier. */
    private String ruleCode;

    /** Short human-readable summary (shown in UI “rule” column). */
    private String ruleSummary;

    /** JSON snapshot of decision output (truncated server-side if very large). */
    private String detailJson;

    private Date createTime;

    private Date updateTime;

    @TableLogic
    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}
