package com.yupi.springbootinit.model.dto.autoeda;

import lombok.Data;

@Data
public class ContinueExplorationRequest {

    private String sessionId;

    /** 用户追问的自然语言 */
    private String followUpQuery;
}
