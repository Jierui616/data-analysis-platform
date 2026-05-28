package com.yupi.springbootinit.model.vo.autoeda;

import lombok.Data;

/**
 * 自主探索页图表下拉选项（来自 MySQL chart 表）
 */
@Data
public class AutoEdaChartOptionVO {

    private Long id;

    private String name;

    private String goal;

    private String chartType;

    /** succeed / running / failed 等 */
    private String status;
}
