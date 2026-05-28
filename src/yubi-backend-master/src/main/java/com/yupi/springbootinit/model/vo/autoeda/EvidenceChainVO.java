package com.yupi.springbootinit.model.vo.autoeda;

import lombok.Data;

@Data
public class EvidenceChainVO {

    private Integer stepOrder;

    private String queryId;

    private String querySql;

    private String resultSummary;

    private Integer resultRowCount;

    private String lineagePath;

    private Double igScore;
}
