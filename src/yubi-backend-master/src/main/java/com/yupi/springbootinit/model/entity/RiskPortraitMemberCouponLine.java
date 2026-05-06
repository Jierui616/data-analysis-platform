package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;

/**
 * Imported member coupon redemption lines; DDL {@code sql/risk_portrait_schema.sql}.
 */
@TableName(value = "risk_portrait_member_coupon_line")
@Data
public class RiskPortraitMemberCouponLine implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String memberId;

    private Date txnTime;

    private String storeName;

    private String couponName;

    private BigDecimal faceValue;

    private BigDecimal discountAmount;

    private String status;

    private Date createTime;

    private Date updateTime;

    @TableLogic
    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}
