package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
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

    private String sessionId;

    private String hypothesisId;

    private String type;

    private String statement;

    private String evidenceJson;

    private Double confidencePrior;

    private Double confidencePosterior;

    private String status;

    private String lineagePath;

    private String parentHypothesisId;

    private String sourceQueryIds;

    private Date createTime;

    private Date updateTime;

    private static final long serialVersionUID = 1L;
}
