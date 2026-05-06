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
 * Imported member order lines for activity API; DDL {@code sql/risk_portrait_schema.sql}.
 */
@TableName(value = "risk_portrait_member_order_line")
@Data
public class RiskPortraitMemberOrderLine implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String memberId;

    private Date txnTime;

    private String storeName;

    private BigDecimal amount;

    private String channel;

    private String orderNoMasked;

    private Date createTime;

    private Date updateTime;

    @TableLogic
    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}
