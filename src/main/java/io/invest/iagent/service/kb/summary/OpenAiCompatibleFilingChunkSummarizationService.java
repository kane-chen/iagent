package io.invest.iagent.service.kb.summary;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.service.kb.model.KnowledgeBaseChunkDTO;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

public class OpenAiCompatibleFilingChunkSummarizationService implements FilingChunkSummarizationService {

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int maxInputChars;
    private final int timeoutSeconds;

    public OpenAiCompatibleFilingChunkSummarizationService(String baseUrl, String apiKey, String model, int maxInputChars, int timeoutSeconds) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.max(timeoutSeconds, 1))).build();
        this.baseUrl = StringUtils.removeEnd(baseUrl, "/");
        this.apiKey = apiKey;
        this.model = model;
        this.maxInputChars = maxInputChars;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public Optional<String> summarize(KnowledgeBaseChunkDTO chunk) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("temperature", 0.1);
            body.put("messages", messages(chunk));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(Math.max(timeoutSeconds, 1)))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + StringUtils.defaultString(apiKey))
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Summary request failed: " + response.statusCode() + " " + response.body());
            }
            JSONObject json = JSON.parseObject(response.body());
            JSONArray choices = json.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return Optional.empty();
            }
            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            String summary = message == null ? null : message.getString("content");
            return Optional.ofNullable(StringUtils.trimToNull(summary));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String model() {
        return model;
    }

    private JSONArray messages(KnowledgeBaseChunkDTO chunk) {
        JSONArray messages = new JSONArray();
        JSONObject system = new JSONObject();
        system.put("role", "system");
        system.put("content", "你是财报知识库预处理器。请只基于原文生成事实性分片摘要，保留数字、期间、风险、原因、财务指标和章节上下文，不要补充原文没有的信息。");
        messages.add(system);

        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", "请用1到3条简洁要点总结以下财报分片，便于后续向量检索候选过滤。\n"
                + "股票: " + StringUtils.defaultString(chunk.getTicker()) + "\n"
                + "文档: " + StringUtils.defaultString(chunk.getDocumentId()) + "\n"
                + "章节: " + StringUtils.defaultString(chunk.getSectionTitle()) + "\n"
                + "类别: " + StringUtils.defaultString(chunk.getCategory()) + "\n"
                + "原文:\n" + StringUtils.left(StringUtils.defaultString(chunk.getText()), Math.max(maxInputChars, 1)));
        messages.add(user);
        return messages;
    }
}
