package com.yupi.springbootinit.model.vo.autoeda;

import lombok.Data;

import java.util.Date;

@Data
public class AutoEdaSessionSummaryVO {

    private String sessionId;

    private Long chartId;

    private String chartName;

    private String status;

    private Integer totalQueries;

    private Date startTime;

    private Date endTime;

    private Date createTime;
}
