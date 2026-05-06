package com.yupi.springbootinit.model.vo.lineage;

import lombok.Data;

import java.util.Collections;
import java.util.List;

@Data
public class LineageImpactVO {
    private Long sourceChartId;
    private int impactedCount;
    private List<LineageImpactItemVO> impactedCharts = Collections.emptyList();
}
