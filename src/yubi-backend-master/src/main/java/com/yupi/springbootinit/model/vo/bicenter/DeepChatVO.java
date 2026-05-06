package com.yupi.springbootinit.model.vo.bicenter;

import lombok.Data;

import java.util.List;

@Data
public class DeepChatVO {
    private String answer;
    private List<String> followUps;
    private List<String> assumptions;
    private List<ActionItemVO> actionItems;

    @Data
    public static class ActionItemVO {
        private String priority;
        private String title;
        private String owner;
        private String expectedBenefit;
        private String eta;
    }
}
