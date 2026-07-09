package io.invest.iagent.service.filingrag.embed;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Embedding provider backed by a local Ollama server (or any OpenAI-compatible endpoint).
 * <p>
 * Tries {@code /api/embed} (batch) first; on 404 falls back to {@code /api/embeddings} (single).
 */
@Slf4j
public class OllamaEmbeddingProvider implements EmbeddingProvider {

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String model;
    private final int dimension;

    public OllamaEmbeddingProvider(String embedUrl, String model, int dimension) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.baseUrl = StringUtils.removeEnd(embedUrl, "/");
        this.model = model;
        this.dimension = dimension;
    }

    @Override
    public List<Float> embed(String text) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("input", text);
            HttpResponse<String> resp = post(baseUrl, body);
            if (resp.statusCode() == 404 && StringUtils.endsWith(baseUrl, "/api/embed")) {
                return embedLegacy(StringUtils.removeEnd(baseUrl, "/api/embed") + "/api/embeddings", text);
            }
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IOException("Embedding request failed: HTTP " + resp.statusCode() + " " + resp.body());
            }
            JSONObject json = JSON.parseObject(resp.body());
            JSONArray arr = embeddingArray(json);
            return toFloatList(arr);
        } catch (Exception e) {
            throw new RuntimeException("embed() failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("input", texts);
            HttpResponse<String> resp = post(baseUrl, body);
            if (resp.statusCode() == 404 && StringUtils.endsWith(baseUrl, "/api/embed")) {
                // Fallback: one-by-one
                String legacyUrl = StringUtils.removeEnd(baseUrl, "/api/embed") + "/api/embeddings";
                List<List<Float>> result = new ArrayList<>();
                for (String t : texts) result.add(embedLegacy(legacyUrl, t));
                return result;
            }
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IOException("Batch embed failed: HTTP " + resp.statusCode() + " " + resp.body());
            }
            JSONObject json = JSON.parseObject(resp.body());
            // /api/embed returns {"embeddings": [[...],[...]]}
            JSONArray embeddings = json.getJSONArray("embeddings");
            if (embeddings != null && !embeddings.isEmpty()) {
                // If embeddings is a list of arrays (batch)
                Object first = embeddings.get(0);
                if (first instanceof JSONArray) {
                    List<List<Float>> result = new ArrayList<>(embeddings.size());
                    for (int i = 0; i < embeddings.size(); i++) {
                        result.add(toFloatList(embeddings.getJSONArray(i)));
                    }
                    return result;
                } else {
                    // Single embedding wrapped in embeddings
                    return List.of(toFloatList(embeddings));
                }
            }
            // Fallback: data[0].embedding (OpenAI format)
            JSONArray data = json.getJSONArray("data");
            if (data != null && !data.isEmpty()) {
                List<List<Float>> result = new ArrayList<>(data.size());
                for (int i = 0; i < data.size(); i++) {
                    result.add(toFloatList(data.getJSONObject(i).getJSONArray("embedding")));
                }
                return result;
            }
            throw new IOException("Unrecognized embedding response shape: " + StringUtils.abbreviate(resp.body(), 300));
        } catch (Exception e) {
            throw new RuntimeException("embedBatch() failed: " + e.getMessage(), e);
        }
    }

    private List<Float> embedLegacy(String url, String text) throws IOException, InterruptedException {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("prompt", text);
        HttpResponse<String> resp = post(url, body);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Legacy embedding request failed: HTTP " + resp.statusCode());
        }
        JSONObject json = JSON.parseObject(resp.body());
        return toFloatList(json.getJSONArray("embedding"));
    }

    private JSONArray embeddingArray(JSONObject json) {
        JSONArray embeddings = json.getJSONArray("embeddings");
        if (embeddings != null && !embeddings.isEmpty()) {
            Object first = embeddings.get(0);
            if (first instanceof JSONArray arr) return arr;
            return embeddings;
        }
        JSONArray embedding = json.getJSONArray("embedding");
        if (embedding != null) return embedding;
        JSONArray data = json.getJSONArray("data");
        if (data != null && !data.isEmpty()) {
            return data.getJSONObject(0).getJSONArray("embedding");
        }
        throw new IllegalArgumentException("Embedding response does not contain embeddings/embedding/data[0].embedding");
    }

    private List<Float> toFloatList(JSONArray arr) {
        List<Float> list = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) list.add(arr.getFloatValue(i));
        return list;
    }

    private HttpResponse<String> post(String url, JSONObject body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(body), StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Override
    public int dimension() { return dimension; }

    @Override
    public String model() { return model; }
}
