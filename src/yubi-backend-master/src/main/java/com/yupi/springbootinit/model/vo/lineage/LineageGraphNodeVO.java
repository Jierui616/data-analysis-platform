package com.yupi.springbootinit.model.vo.lineage;

import lombok.Data;

@Data
public class LineageGraphNodeVO {
    private String nodeKey;
    private String nodeType;
    private String name;
    private String status;
}
