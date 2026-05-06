package com.yupi.springbootinit.model.vo.lineage;

import lombok.Data;

@Data
public class LineageGraphEdgeVO {
    private String fromNodeKey;
    private String toNodeKey;
    private String edgeType;
    /**
     * 证据来源：event_meta / text_parse / system
     */
    private String evidenceSource;
    /**
     * 可信度：high / medium
     */
    private String confidence;
    /**
     * 证据详情说明
     */
    private String evidenceDetail;
    /**
     * 修复建议
     */
    private String fixSuggestion;
    /**
     * 推荐 eventMeta 模板（JSON 字符串）
     */
    private String eventMetaTemplate;
}
