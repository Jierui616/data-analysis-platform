package com.yupi.springbootinit.model.entity.restaurant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;
import lombok.Data;

@TableName(value = "store_daily_metric")
@Data
public class StoreDailyMetric implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private LocalDate statDate;

    private Long storeId;

    private BigDecimal revenue;

    private BigDecimal grossMarginRate;

    private BigDecimal avgTicket;

    private BigDecimal tableTurnover;

    private BigDecimal seatOccupancy;

    private BigDecimal takeawayRatio;

    private BigDecimal dineInRatio;

    private BigDecimal prepTimeAvgMin;

    private BigDecimal cancelRate;

    private BigDecimal badReviewRate;

    private Date createTime;

    private Date updateTime;

    @TableLogic
    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}
