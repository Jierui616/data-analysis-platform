package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@TableName(value = "chart_action")
@Data
public class ChartAction implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long chartId;

    private String task;

    private String owner;

    private String dueDate;

    /**
     * pending / in_progress / completed
     */
    private String status;

    /**
     * high / medium / low
     */
    private String priority;

    private String expectedBenefit;

    private Date createTime;

    private Date updateTime;

    @TableLogic
    private Integer isDelete;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
