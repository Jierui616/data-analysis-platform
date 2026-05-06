package com.yupi.springbootinit.model.dto.chart;

import lombok.Data;

import java.io.Serializable;

@Data
public class ChartRerunRequest implements Serializable {

    private Long chartId;

    private static final long serialVersionUID = 1L;
}
