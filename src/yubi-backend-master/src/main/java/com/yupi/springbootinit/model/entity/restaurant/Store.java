package com.yupi.springbootinit.model.entity.restaurant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;

@TableName(value = "store")
@Data
public class Store implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String storeCode;

    private String storeName;

    private String region;

    private String city;

    private BigDecimal areaSqm;

    private String status;

    private Date createTime;

    private Date updateTime;

    @TableLogic
    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}
