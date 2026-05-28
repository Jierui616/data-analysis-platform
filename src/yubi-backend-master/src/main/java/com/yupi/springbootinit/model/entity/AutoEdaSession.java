package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
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

    private String sessionId;

    private Long chartId;

    private Long userId;

    private String status;

    private String configJson;

    private String stateJson;

    private String resultJson;

    private Integer totalQueries;

    private Date startTime;

    private Date endTime;

    private Date createTime;

    private Date updateTime;

    @TableLogic
    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}
