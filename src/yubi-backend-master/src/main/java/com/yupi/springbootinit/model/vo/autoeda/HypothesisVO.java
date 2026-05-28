package com.yupi.springbootinit.model.vo.autoeda;

import lombok.Data;

import java.util.List;

@Data
public class HypothesisVO {

    private String hypothesisId;

    private String type;

    private String statement;

    private Double confidencePrior;

    private Double confidencePosterior;

    private String status;

    private String lineagePath;

    private List<EvidenceChainVO> evidenceChain;

    private List<String> sourceQueryIds;

    /** 父假设ID（LLM生成时可能指定） */
    private String parentHypothesisId;
}
