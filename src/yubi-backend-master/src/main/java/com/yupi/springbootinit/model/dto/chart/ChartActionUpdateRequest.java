package com.yupi.springbootinit.model.dto.chart;

import lombok.Data;

import java.io.Serializable;

@Data
public class ChartActionUpdateRequest implements Serializable {

    private Long id;

    private Long chartId;

    private String status;

    private String owner;

    private String dueDate;

    private static final long serialVersionUID = 1L;
}
