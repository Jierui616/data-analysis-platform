package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@TableName(value = "lineage_fix_task")
@Data
public class LineageFixTask implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long chartId;

    private String taskKey;

    private String fromNodeKey;

    private String toNodeKey;

    private String edgeType;

    private String evidenceSource;

    private String confidence;

    private String fixSuggestion;

    private String eventMetaTemplate;

    /**
     * pending / resolved
     */
    private String status;

    private Date createTime;

    private Date updateTime;

    @TableLogic
    private Integer isDelete;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
