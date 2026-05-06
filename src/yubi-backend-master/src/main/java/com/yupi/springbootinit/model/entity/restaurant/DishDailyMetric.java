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

@TableName(value = "dish_daily_metric")
@Data
public class DishDailyMetric implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private LocalDate statDate;

    private Long storeId;

    private String dishName;

    private String categoryName;

    private Long salesQty;

    private BigDecimal revenue;

    private BigDecimal grossMarginRate;

    private BigDecimal refundRate;

    private Date createTime;

    private Date updateTime;

    @TableLogic
    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}
