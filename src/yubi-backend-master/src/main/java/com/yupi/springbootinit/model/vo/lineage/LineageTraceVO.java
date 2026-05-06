package com.yupi.springbootinit.model.vo.lineage;

import com.yupi.springbootinit.model.entity.ExplainSnapshot;
import com.yupi.springbootinit.model.entity.LineageEvent;
import lombok.Data;

import java.util.List;

@Data
public class LineageTraceVO {
    private String traceId;
    private Long chartId;
    private List<LineageEvent> events;
    private List<ExplainSnapshot> explanations;
    /**
     * 当前链路的数据时间范围（起止）
     */
    private String dataTimeRange;
    /**
     * 本次数据指纹（用于判断输入数据是否变化）
     */
    private String dataFingerprint;
    /**
     * 本次结果指纹（用于判断输出结果是否变化）
     */
    private String resultFingerprint;
    /**
     * 对比上一次链路后，数据是否变化
     */
    private Boolean dataChanged;
    /**
     * 对比上一次的差异说明
     */
    private String differenceReason;
}
