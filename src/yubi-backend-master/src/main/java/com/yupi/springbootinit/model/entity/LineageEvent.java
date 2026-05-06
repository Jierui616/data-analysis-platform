package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@TableName(value = "lineage_event")
@Data
public class LineageEvent implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String traceId;

    private Long chartId;

    private String eventType;

    private String inputDigest;

    private String outputDigest;

    private String ruleVersion;

    private String logicRef;

    private String eventMeta;

    private String status;

    private Long operatorId;

    private Date createTime;

    private Date updateTime;

    @TableLogic
    private Integer isDelete;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
