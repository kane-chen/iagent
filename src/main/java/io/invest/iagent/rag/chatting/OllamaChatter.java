package io.invest.iagent.rag.chatting;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.invest.iagent.rag.config.RagProperties;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class OllamaChatter implements Chatter {

    @Autowired
    private RagProperties ragProperties;

    private HttpClient httpClient;

    @PostConstruct
    public void init() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        return chat(ChatRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .build());
    }

    /**
     * 调用LLM chat接口（全参数控制）。
     *
     * @param request 请求参数
     * @return 模型输出文本；当content字段为空时自动回退到reasoning_content/reasoning/thinking字段原文（失败时返回空字符串）
     */
    protected String chat(ChatRequest request) {
        try {
            // define
            String baseUrl = ragProperties.getLlm().getBaseUrl();
            String model = ragProperties.getLlm().getModel() ;
            String apiKey = ragProperties.getLlm().getApiKey() ;
            // request
            JSONObject body = buildRequestBody(model,request);
            int timeoutSeconds = request.timeoutSeconds != null
                    ? request.timeoutSeconds : ragProperties.getLlm().getTimeoutSeconds();
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json");
            if (StringUtils.isNotBlank(apiKey)) {
                reqBuilder.header("Authorization", "Bearer " + apiKey);
            }
            reqBuilder.POST(HttpRequest.BodyPublishers.ofString(
                    JSON.toJSONString(body), StandardCharsets.UTF_8));

            HttpResponse<String> response = httpClient.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("LLM chat HTTP " + response.statusCode() + ": " +
                        StringUtils.abbreviate(response.body(), 300));
            }
            String content = extractContent(response.body()) ;
            return StringUtils.defaultString(content);
        } catch (Exception e) {
            log.warn("LLM chat failed: {}", e.getMessage());
            return "";
        }
    }

    private String extractContent(String response){
        if(StringUtils.isBlank(response)){
            return null ;
        }
        ChatResponse res = JSON.parseObject(response, ChatResponse.class) ;
        ChatResponse.ChatMessage message = Optional.ofNullable(res.getChoices())
                .map(choices -> choices.get(0))
                .map(ChatResponse.ChatChoice::getMessage).orElse(null);
        if(Objects.isNull(message)){
            return null ;
        }
        return StringUtils.firstNonBlank(message.getContent(), message.getReasoning()) ;
    }

    // ------------------------------------------------------------------
    // Reasoning extraction helpers
    // ------------------------------------------------------------------

    /**
     * 从文本（通常是reasoning_content）中提取结构化JSON。
     * 先通过括号匹配查找包含keywords/ranked/sufficient等key的JSON对象，
     * 回退使用正则查找。适用于查询改写、rerank、sufficiency判断等结构化输出场景。
     */
    public String extractJsonFromText(String text) {
        if (StringUtils.isBlank(text)) return "";
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '{') continue;
            int depth = 0;
            int end = -1;
            for (int j = i; j < text.length(); j++) {
                char c = text.charAt(j);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) { end = j; break; }
                }
            }
            if (end > i) {
                String candidate = text.substring(i, end + 1);
                if (isTargetJson(candidate)) {
                    return candidate;
                }
            }
        }
        Matcher m = Pattern.compile("\\{[^{}]*\"(keywords|ranked|sufficient)\"[^{}]*\\}")
                .matcher(text);
        if (m.find()) {
            return m.group();
        }
        return "";
    }

    /**
     * 从reasoning文本中提取最终答案（适用于答案合成场景）。
     * 查找"最终答案："、"答："等标记后的内容，找不到时返回原文。
     */
    public String extractAnswerFromReasoning(String reasoning) {
        if (StringUtils.isBlank(reasoning)) return "";
        String[] markers = {"最终答案：", "最终答案:", "答案：", "答案:", "答：", "答:",
                "## 回答", "## 回答内容", "## 结论", "Final Answer:", "Answer:"};
        for (String marker : markers) {
            int idx = reasoning.lastIndexOf(marker);
            if (idx >= 0) {
                String candidate = reasoning.substring(idx + marker.length()).trim();
                if (StringUtils.isNotBlank(candidate)) {
                    return candidate;
                }
            }
        }
        return reasoning;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private JSONObject buildRequestBody(String model,ChatRequest req) {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", req.temperature != null ? req.temperature : 0.3);
        body.put("max_tokens", req.maxTokens);
        body.put("stream", false);

        if (req.disableThinking) {
            body.put("think", false);
            JSONObject extra = new JSONObject();
            extra.put("think", false);
            body.put("chat_template_kwargs", extra);
        }

        JSONArray messages = new JSONArray();
        messages.add(msg("system", req.systemPrompt));
        messages.add(msg("user", req.userPrompt));
        body.put("messages", messages);
        return body;
    }

    private boolean isTargetJson(String candidate) {
        try {
            JSONObject obj = JSON.parseObject(candidate);
            return obj.containsKey("keywords")
                    || obj.containsKey("ranked")
                    || obj.containsKey("sufficient");
        } catch (Exception e) {
            return false;
        }
    }

    private JSONObject msg(String role, String content) {
        JSONObject m = new JSONObject();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    // ------------------------------------------------------------------
    // Chat request parameters
    // ------------------------------------------------------------------

    @Data
    @Builder
    public static class ChatRequest {
        String systemPrompt;
        String userPrompt;
        Double temperature;
        Integer maxTokens;
        Integer timeoutSeconds;
        boolean disableThinking = true;
    }

    @Data
    public static class ChatResponse {
        String id;
        String object;
        Long created;
        String model;
        List<ChatResponse.ChatChoice> choices;
        ChatResponse.Usage usage;

        @Data
        public static class ChatChoice {
            @JsonProperty("index")
            Integer index;
            @JsonProperty("message")
            ChatResponse.ChatMessage message;
            @JsonProperty("finish_reason")
            String finishReason;
        }

        @Data
        public static class Usage {
            @JsonProperty("prompt_tokens")
            Long promptTokens;
            @JsonProperty("completion_tokens")
            Long completionTokens;
            @JsonProperty("total_tokens")
            Long totalTokens;
        }

        @Data
        public static class ChatMessage {
            @JsonProperty("role")
            String role;
            @JsonProperty("content")
            String content;
            @JsonProperty("reasoning")
            @JsonAlias(value = {"reasoning_content", "thinking"})
            String reasoning ;
        }
    }

}
