package io.invest.iagent.service.filingrag.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

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

/**
 * 通用的OpenAI兼容chat/completions客户端。
 * <p>
 * 用于filingrag模块（以及kb模块）内所有需要调用LLM chat接口的组件——
 * 查询改写、语义rerank、答案合成、摘要等——避免每个组件重复创建HttpClient和编写HTTP调用逻辑。
 * <p>
 * 特性：
 * <ul>
 *   <li>支持API Key认证（Bearer token）</li>
 *   <li>每次调用可覆盖temperature、maxTokens、timeout参数</li>
 *   <li>默认禁用推理模型thinking模式，防止reasoning耗尽token导致content为空</li>
 *   <li>content为空时自动回退到reasoning_content/reasoning/thinking字段原文返回，
 *       调用方可进一步使用{@link #extractJsonFromText(String)}或
 *       {@link #extractAnswerFromReasoning(String)}提取目标内容</li>
 * </ul>
 */
@Slf4j
public class LlmClient {

    /** 默认max_tokens（针对结构化JSON输出预留足够空间） */
    private static final int DEFAULT_MAX_TOKENS = 10240;
    /** 默认连接超时（秒） */
    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    @Getter
    private final String model;
    private final int defaultTimeoutSeconds;

    public LlmClient(String baseUrl, String model, String apiKey, int defaultTimeoutSeconds) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(DEFAULT_CONNECT_TIMEOUT_SECONDS))
                .build();
        this.baseUrl = StringUtils.removeEnd(baseUrl, "/");
        this.model = model;
        this.apiKey = apiKey;
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    public LlmClient(String baseUrl, String model, int defaultTimeoutSeconds) {
        this(baseUrl, model, null, defaultTimeoutSeconds);
    }

    /**
     * 使用默认参数调用LLM chat接口（temperature=0.3, disableThinking=true）。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return 模型输出文本（失败时返回空字符串）
     */
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
    public String chat(ChatRequest request) {
        try {
            JSONObject body = buildRequestBody(request);

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(
                            request.timeoutSeconds != null ? request.timeoutSeconds : defaultTimeoutSeconds))
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

    private JSONObject buildRequestBody(ChatRequest req) {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", req.temperature != null ? req.temperature : 0.3);
        body.put("max_tokens", req.maxTokens != null ? req.maxTokens : DEFAULT_MAX_TOKENS);
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

    public static class ChatRequest {
        String systemPrompt;
        String userPrompt;
        Double temperature;
        Integer maxTokens;
        Integer timeoutSeconds;
        boolean disableThinking = true;

        private ChatRequest() {}

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private final ChatRequest req = new ChatRequest();

            public Builder systemPrompt(String v) { req.systemPrompt = v; return this; }
            public Builder userPrompt(String v) { req.userPrompt = v; return this; }
            public Builder temperature(double v) { req.temperature = v; return this; }
            public Builder maxTokens(int v) { req.maxTokens = v; return this; }
            public Builder timeoutSeconds(int v) { req.timeoutSeconds = v; return this; }
            public Builder disableThinking(boolean v) { req.disableThinking = v; return this; }

            public ChatRequest build() { return req; }
        }
    }

    @Data
    public static class ChatResponse {
        String id;
        String object;
        Long created;
        String model;
        List<ChatChoice> choices;
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
