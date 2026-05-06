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

@TableName(value = "store_staff_daily_metric")
@Data
public class StoreStaffDailyMetric implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private LocalDate statDate;

    private Long storeId;

    private String shiftType;

    private Long plannedStaff;

    private Long actualStaff;

    private BigDecimal workHours;

    private BigDecimal overtimeHours;

    private Long absentCount;

    private Long ordersCount;

    private BigDecimal revenue;

    private BigDecimal avgPrepTimeMin;

    private Date createTime;

    private Date updateTime;

    @TableLogic
    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}
