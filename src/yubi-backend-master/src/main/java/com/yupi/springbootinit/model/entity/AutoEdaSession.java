package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@TableName(value = "auto_eda_session")
@Data
public class AutoEdaSession implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private String sessionId;

    @TableField("chart_id")
    private Long chartId;

    @TableField("user_id")
    private Long userId;

    private String status;

    @TableField("config_json")
    private String configJson;

    @TableField("state_json")
    private String stateJson;

    @TableField("result_json")
    private String resultJson;

    @TableField("total_queries")
    private Integer totalQueries;

    @TableField("start_time")
    private Date startTime;

    @TableField("end_time")
    private Date endTime;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;

    @TableLogic
    @TableField("is_delete")
    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}
