package com.yupi.springbootinit.model.dto.lineage;

import lombok.Data;

@Data
public class ExplainRecordRequest {
    private String traceId;
    private Long chartId;
    private String explainType;
    private String explainData;
    private String promptVersion;
    private String modelName;
}
