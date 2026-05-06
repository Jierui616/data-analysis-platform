package com.yupi.springbootinit.model.entity.restaurant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;

@TableName(value = "alert_rule")
@Data
public class AlertRule implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String ruleName;

    private String metricCode;

    private String compareOp;

    private BigDecimal thresholdValue;

    private String severity;

    private Integer enabled;

    private Date createTime;

    private Date updateTime;

    @TableLogic
    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}
