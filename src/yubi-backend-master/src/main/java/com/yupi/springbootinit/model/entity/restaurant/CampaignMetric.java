package com.yupi.springbootinit.model.entity.restaurant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;
import lombok.Data;

@TableName(value = "campaign_metric")
@Data
public class CampaignMetric implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private LocalDate statDate;

    private Long storeId;

    private String campaignName;

    private String channelName;

    private BigDecimal spendAmount;

    private Long exposureCount;

    private Long clickCount;

    private Long orderCount;

    private BigDecimal conversionRate;

    private BigDecimal roi;

    private Date createTime;

    private Date updateTime;

    @TableLogic
    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}
