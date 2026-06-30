package io.invest.iagent.service.kb.embedding;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class ModelEmbeddingService implements EmbeddingService {

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int dimension;

    public ModelEmbeddingService(String baseUrl, String apiKey, String model, int dimension) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.baseUrl = StringUtils.removeEnd(baseUrl, "/");
        this.apiKey = apiKey;
        this.model = model;
        this.dimension = dimension;
    }

    @Override
    public List<Float> embed(String text) {
        try{
            HttpResponse<String> response = postEmbeddingRequest(baseUrl, text, false);
            if(response.statusCode() == 404 && StringUtils.endsWith(baseUrl, "/api/embed")){
                response = postEmbeddingRequest(StringUtils.removeEnd(baseUrl, "/api/embed") + "/api/embeddings", text, true);
            }
            if(response.statusCode() < 200 || response.statusCode() >= 300){
                throw new IOException("Embedding request failed: " + response.statusCode() + " " + response.body());
            }
            JSONObject json = JSON.parseObject(response.body());
            JSONArray embedding = embeddingArray(json);
            List<Float> result = new ArrayList<>(embedding.size());
            for(int i=0;i<embedding.size();i++){
                result.add(embedding.getFloatValue(i));
            }
            return result;
        }catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    private HttpResponse<String> postEmbeddingRequest(String uri, String text, boolean legacyPrompt) throws IOException, InterruptedException {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put(legacyPrompt ? "prompt" : "input", text);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(body)));
        if(StringUtils.isNotBlank(apiKey)){
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JSONArray embeddingArray(JSONObject json) {
        JSONArray embeddings = json.getJSONArray("embeddings");
        if(embeddings != null && !embeddings.isEmpty()){
            Object first = embeddings.get(0);
            if(first instanceof JSONArray array){
                return array;
            }
            return embeddings;
        }
        JSONArray embedding = json.getJSONArray("embedding");
        if(embedding != null){
            return embedding;
        }
        JSONArray data = json.getJSONArray("data");
        if(data != null && !data.isEmpty()){
            JSONObject first = data.getJSONObject(0);
            if(first != null && first.getJSONArray("embedding") != null){
                return first.getJSONArray("embedding");
            }
        }
        throw new IllegalArgumentException("Embedding response does not contain embeddings/embedding/data[0].embedding");
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String model() {
        return model;
    }
}
