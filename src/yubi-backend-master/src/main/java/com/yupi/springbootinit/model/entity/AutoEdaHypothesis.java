package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@TableName(value = "auto_eda_hypothesis")
@Data
public class AutoEdaHypothesis implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private String sessionId;

    @TableField("hypothesis_id")
    private String hypothesisId;

    @TableField("`type`")
    private String type;

    private String statement;

    @TableField("evidence_json")
    private String evidenceJson;

    @TableField("confidence_prior")
    private Double confidencePrior;

    @TableField("confidence_posterior")
    private Double confidencePosterior;

    private String status;

    @TableField("lineage_path")
    private String lineagePath;

    @TableField("parent_hypothesis_id")
    private String parentHypothesisId;

    @TableField("source_query_ids")
    private String sourceQueryIds;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;

    private static final long serialVersionUID = 1L;
}
