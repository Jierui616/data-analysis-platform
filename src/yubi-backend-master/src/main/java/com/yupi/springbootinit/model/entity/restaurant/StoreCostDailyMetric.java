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

@TableName(value = "store_cost_daily_metric")
@Data
public class StoreCostDailyMetric implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private LocalDate statDate;

    private Long storeId;

    private BigDecimal ingredientCost;

    private BigDecimal packagingCost;

    private BigDecimal energyCost;

    private BigDecimal laborCost;

    private BigDecimal wasteAmount;

    private BigDecimal wasteRate;

    private BigDecimal inventoryLossRate;

    private BigDecimal platformCommission;

    private Date createTime;

    private Date updateTime;

    @TableLogic
    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}
