package io.invest.iagent.rag.embedding.embedder;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.rag.config.RagProperties;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

@Service
public class OllamaEmbedder implements Embedder{

    @Autowired
    private RagProperties config ;

    private HttpClient httpClient;

    @PostConstruct
    public void init() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(300))
                .build();
    }

    @Override
    public float[] embed(String text) {
        try {
            // define
            String model = config.getEmbedding().getModel() ;
            String baseUrl = config.getEmbedding().getUrl() ;
            // request
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("input", text);
            // response
            HttpResponse<String> resp = post(baseUrl, body);
            if (resp.statusCode() == 404 && StringUtils.endsWith(baseUrl, "/api/embed")) {
                baseUrl = StringUtils.removeEnd(baseUrl, "/api/embed") + "/api/embeddings" ;
                return embedLegacy(model,baseUrl, text);
            }
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IOException("Embedding request failed: HTTP " + resp.statusCode() + " " + resp.body());
            }
            // parse
            JSONObject json = JSON.parseObject(resp.body());
            JSONArray arr = embeddingArray(json);
            return toFloatArray(arr);
        } catch (Exception e) {
            throw new RuntimeException("embed() failed: " + e.getMessage(), e);
        }
    }

    private float[] embedLegacy(String model, String url, String text) throws IOException, InterruptedException {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("prompt", text);
        HttpResponse<String> resp = post(url, body);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Legacy embedding request failed: HTTP " + resp.statusCode());
        }
        JSONObject json = JSON.parseObject(resp.body());
        return toFloatArray(json.getJSONArray("embedding"));
    }

    private JSONArray embeddingArray(JSONObject json) {
        JSONArray embeddings = json.getJSONArray("embeddings");
        if (!CollectionUtils.isEmpty(embeddings)) {
            Object first = embeddings.get(0);
            if (first instanceof JSONArray arr){
                return arr;
            }
            return embeddings;
        }
        JSONArray embedding = json.getJSONArray("embedding");
        if (embedding != null){
            return embedding;
        }
        JSONArray data = json.getJSONArray("data");
        if (!CollectionUtils.isEmpty(data)) {
            return data.getJSONObject(0).getJSONArray("embedding");
        }
        throw new IllegalArgumentException("Embedding response does not contain embeddings/embedding/data[0].embedding");
    }

    private float[] toFloatArray(JSONArray arr) {
        float[] result = new float[arr.size()];
        IntStream.range(0, arr.size()).forEach(i -> result[i] = arr.getFloatValue(i));
        return result;
    }

    private HttpResponse<String> post(String url, JSONObject body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(300))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(body), StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (CollectionUtils.isEmpty(texts)) {
            return List.of();
        }
        try {
            // define
            String model = config.getEmbedding().getModel() ;
            String baseUrl = config.getEmbedding().getUrl() ;
            // request
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("input", texts);
            // response
            HttpResponse<String> resp = post(baseUrl, body);
            if (resp.statusCode() == 404 && StringUtils.endsWith(baseUrl, "/api/embed")) {
                // Fallback: one-by-one
                String legacyUrl = StringUtils.removeEnd(baseUrl, "/api/embed") + "/api/embeddings";
                List<float[]> result = new ArrayList<>();
                for (String t : texts){
                    result.add(embedLegacy(model,legacyUrl, t));
                }
                return result;
            }
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IOException("Batch embed failed: HTTP " + resp.statusCode() + " " + resp.body());
            }
            JSONObject json = JSON.parseObject(resp.body());
            // /api/embed returns {"embeddings": [[...],[...]]}
            JSONArray embeddings = json.getJSONArray("embeddings");
            if (!CollectionUtils.isEmpty(embeddings)) {
                // If embeddings is a list of arrays (batch)
                Object first = embeddings.get(0);
                if (first instanceof JSONArray) {
                    List<float[]> result = new ArrayList<>(embeddings.size());
                    for (int i = 0; i < embeddings.size(); i++) {
                        result.add(toFloatArray(embeddings.getJSONArray(i)));
                    }
                    return result;
                } else {
                    // Single embedding wrapped in embeddings
                    return List.of(toFloatArray(embeddings));
                }
            }
            // Fallback: data[0].embedding (OpenAI format)
            JSONArray data = json.getJSONArray("data");
            if (!CollectionUtils.isEmpty(data)) {
                List<float[]> result = new ArrayList<>(data.size());
                for (int i = 0; i < data.size(); i++) {
                    result.add(toFloatArray(data.getJSONObject(i).getJSONArray("embedding")));
                }
                return result;
            }
            throw new IOException("Unrecognized embedding response shape: " + StringUtils.abbreviate(resp.body(), 300));
        } catch (Exception e) {
            throw new RuntimeException("embedBatch() failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int dimension() {
        return config.getEmbedding().getDimension();
    }

    @Override
    public String model() {
        return config.getEmbedding().getModel();
    }

}
