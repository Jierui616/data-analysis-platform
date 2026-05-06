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

@TableName(value = "alert_event")
@Data
public class AlertEvent implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private LocalDate statDate;

    private Long storeId;

    private Long ruleId;

    private String metricCode;

    private BigDecimal metricValue;

    private BigDecimal thresholdValue;

    private String severity;

    private String status;

    private String message;

    private Date createTime;

    private Date updateTime;

    @TableLogic
    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}
