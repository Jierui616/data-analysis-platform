package com.yupi.springbootinit.manager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.BusinessException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 对接 AI：使用阿里云百炼（DashScope OpenAI 兼容接口）。
 */
@Service
public class AiManager {

    private static final String DASHSCOPE_CHAT_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String MODEL_UNAVAILABLE_MSG = "模型不可用";

    @Value("${dashscope.api-key:}")
    private String dashscopeApiKey;

    @Value("${dashscope.model:qwen-plus}")
    private String dashscopeModel;

    @Resource
    private ObjectMapper objectMapper;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * AI 对话（modelId 在百炼模式下忽略，使用 dashscope.model）
     */
    public String doChat(long modelId, String message) {
        return doChat(modelId, message, "");
    }

    /**
     * AI 对话：可指定百炼模型名覆盖默认 dashscope.model
     */
    public String doChat(long modelId, String message, String dashscopeModelOverride) {
        if (!isDashScopeEnabled()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, MODEL_UNAVAILABLE_MSG);
        }
        return doChatDashScope(message, dashscopeModelOverride);
    }

    private boolean isDashScopeEnabled() {
        if (StringUtils.isBlank(dashscopeApiKey)) {
            return false;
        }
        String k = dashscopeApiKey.trim();
        if (k.contains("替换") || k.startsWith("your-") || k.equals("sk-xxx")) {
            return false;
        }
        return true;
    }

    /**
     * 将百炼 HTTP 错误转为可读的业务异常（含免费额度用尽等常见码）
     */
    private BusinessException mapDashScopeHttpError(HttpStatusCodeException e) {
        return new BusinessException(ErrorCode.SYSTEM_ERROR, MODEL_UNAVAILABLE_MSG);
    }

    private String doChatDashScope(String message, String dashscopeModelOverride) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(dashscopeApiKey.trim());

        Map<String, Object> userMsg = new HashMap<>(2);
        userMsg.put("role", "user");
        userMsg.put("content", message);
        Map<String, Object> body = new HashMap<>(2);
        String model = StringUtils.isNotBlank(dashscopeModelOverride) ? dashscopeModelOverride.trim() : dashscopeModel.trim();
        body.put("model", model);
        body.put("messages", Collections.singletonList(userMsg));

        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(DASHSCOPE_CHAT_URL, entity, String.class);
            String json = response.getBody();
            if (StringUtils.isBlank(json)) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, MODEL_UNAVAILABLE_MSG);
            }
            JsonNode root = objectMapper.readTree(json);
            if (root.has("error")) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, MODEL_UNAVAILABLE_MSG);
            }
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.size() == 0) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, MODEL_UNAVAILABLE_MSG);
            }
            String content = choices.get(0).path("message").path("content").asText("");
            if (StringUtils.isBlank(content)) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, MODEL_UNAVAILABLE_MSG);
            }
            return content;
        } catch (BusinessException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            throw mapDashScopeHttpError(e);
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, MODEL_UNAVAILABLE_MSG);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, MODEL_UNAVAILABLE_MSG);
        }
    }
}
