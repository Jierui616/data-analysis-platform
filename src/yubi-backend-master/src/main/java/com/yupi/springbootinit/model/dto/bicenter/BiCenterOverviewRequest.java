package com.yupi.springbootinit.model.dto.bicenter;

import lombok.Data;

/**
 * 商业智能分析中心：概览请求（复用智能分析上传的数据）
 */
@Data
public class BiCenterOverviewRequest {

    /**
     * 可选：指定图表 id，不传则取当前用户最近一次成功的图表
     */
    private Long chartId;

    /**
     * 可选：时间周期描述（展示用）
     */
    private String period;

    /**
     * 可选：公司名称（投资者视角展示用）
     */
    private String company;
}

