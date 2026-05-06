package com.yupi.springbootinit.model.vo.lineage;

import lombok.Data;

@Data
public class LineageFixTaskVO {
    private Long id;
    private Long chartId;
    private String fromNodeKey;
    private String toNodeKey;
    private String edgeType;
    private String evidenceSource;
    private String confidence;
    private String fixSuggestion;
    private String eventMetaTemplate;
    private String status;
}
