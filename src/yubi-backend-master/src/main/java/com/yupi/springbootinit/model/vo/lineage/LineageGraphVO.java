package com.yupi.springbootinit.model.vo.lineage;

import lombok.Data;

import java.util.Collections;
import java.util.List;

@Data
public class LineageGraphVO {
    private String traceId;
    private Long chartId;
    private List<LineageGraphNodeVO> nodes = Collections.emptyList();
    private List<LineageGraphEdgeVO> edges = Collections.emptyList();
}
