package com.yupi.springbootinit.model.entity.restaurant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Date;
import lombok.Data;

@TableName(value = "restaurant_import_log")
@Data
public class RestaurantImportLog implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private String importType;

    private String importMode;

    private Integer totalRows;

    private Integer successRows;

    private Integer invalidRows;

    private LocalDate startDate;

    private LocalDate endDate;

    private String status;

    private String message;

    private Date createTime;

    private Date updateTime;

    @TableLogic
    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}
