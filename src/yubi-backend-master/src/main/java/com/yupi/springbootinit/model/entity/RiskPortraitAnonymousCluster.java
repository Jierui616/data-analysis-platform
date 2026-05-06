package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * Imported anonymous bucket rows; DDL {@code sql/risk_portrait_schema.sql}.
 */
@TableName(value = "risk_portrait_anonymous_cluster")
@Data
public class RiskPortraitAnonymousCluster implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String anonymousBucketId;

    /** JSON array or comma-separated masked weak ids. */
    private String linkedWeakSignals;

    private Integer eventCount7d;

    private String riskTag;

    private Date lastSeen;

    private String topStoreHint;

    private Date createTime;

    private Date updateTime;

    @TableLogic
    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}
