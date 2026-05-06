package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * Imported member watchlist rows for HQ list API; DDL {@code sql/risk_portrait_schema.sql}.
 */
@TableName(value = "risk_portrait_member_watchlist")
@Data
public class RiskPortraitMemberWatchlist implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String memberId;

    private String displayRef;

    private String riskLevel;

    /** Comma-separated tags or JSON array string. */
    private String tags;

    private Integer orderCount;

    private Integer couponRedeemCount;

    private String refundHint;

    private Date lastActive;

    private String topStore;

    private Date periodStart;

    private Date periodEnd;

    private Date createTime;

    private Date updateTime;

    @TableLogic
    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}
