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
 * 对接 AI：支持本地 Ollama（OpenAI 兼容）与阿里云百炼 DashScope。
 * 默认 provider=ollama，使用本地 qwen2.5:7b。
 */
@Service
public class AiManager {

    private static final String DASHSCOPE_CHAT_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String MODEL_UNAVAILABLE_MSG = "模型不可用";

    @Value("${ai.provider:ollama}")
    private String aiProvider;

    @Value("${ollama.base-url:http://127.0.0.1:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.model:qwen2.5:7b}")
    private String ollamaModel;

    @Value("${ollama.fallback-to-dashscope:false}")
    private boolean ollamaFallbackToDashscope;

    @Value("${dashscope.api-key:}")
    private String dashscopeApiKey;

    @Value("${dashscope.model:qwen-plus}")
    private String dashscopeModel;

    @Resource
    private ObjectMapper objectMapper;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 当前 AI 是否可用（按 provider 检测）
     */
    public boolean isAvailable() {
        if (isOllamaPrimary()) {
            return pingOllama();
        }
        return isDashScopeEnabled();
    }

    /**
     * AI 对话（modelId 忽略；Ollama 用 ollama.model，百炼用 dashscope.model）
     */
    public String doChat(long modelId, String message) {
        return doChat(modelId, message, "");
    }

    /**
     * AI 对话：可指定模型名覆盖默认配置
     */
    public String doChat(long modelId, String message, String modelOverride) {
        if (isOllamaPrimary()) {
            try {
                return doChatOpenAiCompatible(resolveOllamaUrl(), resolveOllamaModel(modelOverride), message, false);
            } catch (BusinessException e) {
                if (ollamaFallbackToDashscope && isDashScopeEnabled()) {
                    return doChatDashScope(message, modelOverride);
                }
                throw e;
            }
        }
        if (!isDashScopeEnabled()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, MODEL_UNAVAILABLE_MSG);
        }
        return doChatDashScope(message, modelOverride);
    }

    private boolean isOllamaPrimary() {
        return "ollama".equalsIgnoreCase(StringUtils.trimToEmpty(aiProvider));
    }

    private String resolveOllamaUrl() {
        String base = StringUtils.trimToEmpty(ollamaBaseUrl);
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/v1/chat/completions";
    }

    private String resolveOllamaModel(String modelOverride) {
        // 仅当 override 形如 Ollama 模型名（含 tag，如 qwen2.5:7b）时才采用；否则用本地默认
        if (StringUtils.isNotBlank(modelOverride) && modelOverride.trim().contains(":")) {
            return modelOverride.trim();
        }
        return StringUtils.trimToEmpty(ollamaModel);
    }

    private boolean pingOllama() {
        try {
            String base = StringUtils.trimToEmpty(ollamaBaseUrl);
            if (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            restTemplate.getForEntity(base + "/api/tags", String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isDashScopeEnabled() {
        if (StringUtils.isBlank(dashscopeApiKey)) {
            return false;
        }
        String k = dashscopeApiKey.trim();
        return !(k.contains("替换") || k.startsWith("your-") || k.equals("sk-xxx"));
    }

    private BusinessException mapHttpError(HttpStatusCodeException e) {
        return new BusinessException(ErrorCode.SYSTEM_ERROR, MODEL_UNAVAILABLE_MSG);
    }

    private String doChatDashScope(String message, String modelOverride) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(dashscopeApiKey.trim());
        String model = StringUtils.isNotBlank(modelOverride) ? modelOverride.trim() : dashscopeModel.trim();
        return doChatOpenAiCompatible(DASHSCOPE_CHAT_URL, model, message, true, headers);
    }

    private String doChatOpenAiCompatible(String url, String model, String message, boolean useAuth) {
        return doChatOpenAiCompatible(url, model, message, useAuth, buildAuthHeaders(useAuth));
    }

    private HttpHeaders buildAuthHeaders(boolean useAuth) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (useAuth) {
            headers.setBearerAuth(dashscopeApiKey.trim());
        }
        return headers;
    }

    private String doChatOpenAiCompatible(String url, String model, String message,
                                          boolean useAuth, HttpHeaders headers) {
        Map<String, Object> userMsg = new HashMap<>(2);
        userMsg.put("role", "user");
        userMsg.put("content", message);
        Map<String, Object> body = new HashMap<>(4);
        body.put("model", model);
        body.put("messages", Collections.singletonList(userMsg));
        body.put("stream", false);

        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            return parseChatCompletion(response.getBody());
        } catch (BusinessException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            throw mapHttpError(e);
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, MODEL_UNAVAILABLE_MSG + ": " + e.getMessage());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, MODEL_UNAVAILABLE_MSG);
        }
    }

    private String parseChatCompletion(String json) throws Exception {
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
    }
}
