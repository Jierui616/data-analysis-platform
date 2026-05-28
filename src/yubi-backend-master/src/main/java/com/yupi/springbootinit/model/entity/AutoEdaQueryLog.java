package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@TableName(value = "auto_eda_query_log")
@Data
public class AutoEdaQueryLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private String queryId;

    private String querySql;

    private String queryDimensions;

    private Double igScore;

    private String igBreakdownJson;

    private String resultSummary;

    private Integer resultRowCount;

    private Integer executionMs;

    private Integer costEstimated;

    private String lineagePath;

    private Date createTime;

    private static final long serialVersionUID = 1L;
}
