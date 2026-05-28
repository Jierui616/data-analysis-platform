package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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

    @TableField("session_id")
    private String sessionId;

    @TableField("query_id")
    private String queryId;

    @TableField("query_sql")
    private String querySql;

    @TableField("query_dimensions")
    private String queryDimensions;

    @TableField("ig_score")
    private Double igScore;

    @TableField("ig_breakdown_json")
    private String igBreakdownJson;

    @TableField("result_summary")
    private String resultSummary;

    @TableField("result_row_count")
    private Integer resultRowCount;

    @TableField("execution_ms")
    private Integer executionMs;

    @TableField("cost_estimated")
    private Integer costEstimated;

    @TableField("lineage_path")
    private String lineagePath;

    @TableField("create_time")
    private Date createTime;

    private static final long serialVersionUID = 1L;
}
