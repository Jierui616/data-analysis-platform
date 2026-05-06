package com.yupi.springbootinit.model.vo.bicenter;

import lombok.Data;

import java.util.List;

@Data
public class AiBlockVO {
    private String summary;
    private List<String> risks;
    private List<String> reasons;
    private List<String> suggestions;
}

