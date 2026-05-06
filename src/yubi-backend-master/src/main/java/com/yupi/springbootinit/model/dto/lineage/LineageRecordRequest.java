package com.yupi.springbootinit.model.dto.lineage;

import lombok.Data;

import java.util.List;

@Data
public class LineageRecordRequest {
    private String traceId;
    private Long chartId;
    private String eventType;
    private String inputText;
    private String outputText;
    private String ruleVersion;
    private String logicRef;
    private String eventMetaJson;
    private List<String> datasetsIn;
    private List<String> datasetsOut;
    private String status;
    private Long operatorId;
}
