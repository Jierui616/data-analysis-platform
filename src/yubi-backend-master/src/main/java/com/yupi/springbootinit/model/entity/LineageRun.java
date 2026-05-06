package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@TableName(value = "lineage_run")
@Data
public class LineageRun implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String runId;

    private String traceId;

    private Long chartId;

    private String status;

    private Date startTime;

    private Date endTime;

    private Long rowCountIn;

    private Long rowCountOut;

    private String partitionRange;

    private Long costMs;

    private String metaJson;

    private Date createTime;

    private Date updateTime;

    @TableLogic
    private Integer isDelete;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
