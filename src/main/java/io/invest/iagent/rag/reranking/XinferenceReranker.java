package io.invest.iagent.rag.reranking;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.annotation.JSONField;
import io.invest.iagent.rag.config.RagProperties;
import io.invest.iagent.rag.retrieve.dto.SearchResult;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基于 Xinference（<a href="https://inference.readthedocs.io/">inference.readthedocs.io</a>）的 Reranker。
 *
 * <p>调用 Xinference 提供的 OpenAI 兼容接口 {@code POST {baseUrl}/v1/rerank}，对召回文档按相关性打分。
 * 通过 {@code app.rag.rerank.provider=xinference} 启用。</p>
 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "app.rag.rerank", name = "provider", havingValue = "xinference")
public class XinferenceReranker implements Reranker {

    @jakarta.annotation.Resource
    private RagProperties ragProperties;

    private HttpClient httpClient;

    @PostConstruct
    public void init() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(ragProperties.getRerank().getTimeoutSeconds()))
                .build();
    }

    @Override
    public List<SearchResult> rerank(String query, List<SearchResult> results) {
        if (results == null || results.size() <= 1) {
            return results;
        }

        try {
            RerankResult response = this.doRerank(query, results);
            if (response == null || response.results == null || response.results.isEmpty()) {
                log.warn("Rerank returned empty response, keeping original order");
                return results;
            }

            // index -> 相关性分数（Xinference reranker 返回 0-1）
            Map<Integer, Double> scores = response.results.stream()
                    .collect(Collectors.toMap(RerankItem::index, RerankItem::score, (t1, t2) -> t1));

            // 组合分：baseScore * 0.3 + modelScore * 0.7
            List<SearchResult> sorted = new ArrayList<>(results);
            for (int i = 0; i < sorted.size(); i++) {
                SearchResult r = sorted.get(i);
                double modelScore = scores.getOrDefault(i, 0.5);
                double baseScore = r.score;
                double composite = baseScore * 0.3 + modelScore * 0.7;
                r.metadata.put("base_score", String.valueOf(baseScore));
                r.metadata.put("model_score", String.valueOf(modelScore));
                r.score = composite;
            }
            sorted.sort((a, b) -> Double.compare(b.score, a.score));
            return sorted;
        } catch (Exception e) {
            log.warn("Rerank failed: {}, keeping original order", e.getMessage());
            return results;
        }
    }

    /**
     * 调用 Xinference rerank 服务（OpenAI 兼容 /v1/rerank 接口），对文档按相关性打分。
     *
     * <p>不显式设置 {@code top_n}，以获取全部文档的分数用于组合分计算；{@code return_documents=false}
     * 避免响应中回传原文，减小报文体积。</p>
     */
    public RerankResult doRerank(String query, List<SearchResult> documents) throws IOException, InterruptedException {
        // 仅发送文本内容，按位置与入参 results 对齐
        List<String> docTexts = documents.stream()
                .map(r -> StringUtils.defaultString(r.getContent()))
                .toList();

        Map<String, Object> body = new HashMap<>();
        body.put("model", ragProperties.getRerank().getModel());
        body.put("query", query);
        body.put("documents", docTexts);
        body.put("return_documents", false);

        String baseUrl = StringUtils.removeEnd(ragProperties.getRerank().getBaseUrl(), "/");
        // Xinference 的 OpenAI 兼容路径统一在 /v1 下；若用户已在 base-url 中配置了 /v1，则不重复追加
        String url = (baseUrl.endsWith("/v1") ? baseUrl : baseUrl + "/v1") + "/rerank";

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(ragProperties.getRerank().getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer "+ragProperties.getRerank().getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(body)));
        // apiKey 可选（本地 Xinference 默认可空）
        if (StringUtils.isNotBlank(ragProperties.getRerank().getApiKey())) {
            builder.header("Authorization", "Bearer " + ragProperties.getRerank().getApiKey());
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("Rerank API returned status {}: {}", response.statusCode(), response.body());
            return null;
        }
        return JSON.parseObject(response.body(), RerankResult.class);
    }

    public record RerankResult(List<RerankItem> results) {}

    public record RerankItem(int index, @JSONField(name = "relevance_score") double score) {}
}
