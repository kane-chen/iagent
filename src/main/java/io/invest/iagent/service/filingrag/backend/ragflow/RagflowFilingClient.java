package io.invest.iagent.service.filingrag.backend.ragflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.invest.iagent.service.filingrag.FilingRagConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RAGFlow HTTP client tailored for the filing RAG subsystem.
 * Mirrors the patterns in {@code io.invest.iagent.service.kb.backend.ragflow.RagflowClient} but uses Jackson.
 */
@Slf4j
public class RagflowFilingClient {

    private final FilingRagConfig.Ragflow properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagflowFilingClient(FilingRagConfig.Ragflow properties) {
        this.properties = properties;
        if (StringUtils.isBlank(properties.getApiKey())) {
            throw new IllegalStateException("app.filing-rag.ragflow.api-key 未配置");
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(properties.getRequestTimeoutSeconds(), 30)))
                .build();
    }

    public JsonNode findDatasetByName(String name) {
        int page = 1, pageSize = 100;
        for (int p = 0; p < 10; p++) {
            JsonNode data = getJson("/api/v1/datasets?page=" + page + "&page_size=" + pageSize);
            if (data == null || !data.isArray() || data.isEmpty()) return null;
            for (JsonNode ds : data) {
                if (StringUtils.equals(ds.path("name").asText(), name)) return ds;
            }
            if (data.size() < pageSize) return null;
            page++;
        }
        return null;
    }

    public JsonNode createDataset(String name) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("chunk_method", "naive");
        ObjectNode pc = body.putObject("parser_config");
        pc.put("chunk_token_num", 100000); // Treat each uploaded file as a single chunk (we pre-chunked in Java)
        return postJson("/api/v1/datasets", body);
    }

    public String ensureDataset(String name) {
        JsonNode existing = findDatasetByName(name);
        if (existing != null && existing.hasNonNull("id")) return existing.get("id").asText();
        JsonNode created = createDataset(name);
        if (created == null || !created.hasNonNull("id")) {
            throw new RagflowFilingClientException(-1, "create dataset failed, empty id: " + name);
        }
        return created.get("id").asText();
    }

    public void deleteDataset(String datasetId) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            ArrayNode ids = body.putArray("ids");
            ids.add(datasetId);
            deleteJson("/api/v1/datasets", body);
        } catch (Exception e) {
            log.warn("delete dataset {} failed: {}", datasetId, e.getMessage());
        }
    }

    public List<JsonNode> listDocuments(String datasetId) {
        List<JsonNode> results = new ArrayList<>();
        int pageSize = 100;
        for (int page = 1; page <= 50; page++) {
            JsonNode data = getJson("/api/v1/datasets/" + datasetId + "/documents?page=" + page + "&page_size=" + pageSize);
            if (data == null) break;
            JsonNode docs = data.has("docs") ? data.get("docs") : data;
            if (docs == null || !docs.isArray() || docs.isEmpty()) break;
            docs.forEach(results::add);
            if (docs.size() < pageSize) break;
        }
        return results;
    }

    /** Uploads content as a single .txt file; RAGFlow will treat it as one document. */
    public JsonNode uploadTextDocument(String datasetId, String filename, String content, Map<String, Object> metaFields)
            throws IOException, InterruptedException {
        String boundary = "----FilingRagBoundary" + UUID.randomUUID().toString().replace("-", "");
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        byte[] body = buildMultipartBody(boundary, filename, contentBytes);
        HttpRequest request = baseRequestBuilder("/api/v1/datasets/" + datasetId + "/documents")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode parsed = handleResponse(request, response);
        if (parsed != null && parsed.isArray() && !parsed.isEmpty()) {
            JsonNode doc = parsed.get(0);
            if (metaFields != null && !metaFields.isEmpty()) {
                updateDocumentMeta(datasetId, doc.get("id").asText(), metaFields);
            }
            return doc;
        }
        return parsed;
    }

    public void updateDocumentMeta(String datasetId, String documentId, Map<String, Object> metaFields) {
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode meta = body.putObject("meta_fields");
        metaFields.forEach((k, v) -> setJsonValue(meta, k, v));
        putJson("/api/v1/datasets/" + datasetId + "/documents/" + documentId, body);
    }

    public void parseDocuments(String datasetId, List<String> documentIds) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode arr = body.putArray("document_ids");
        documentIds.forEach(arr::add);
        postJson("/api/v1/datasets/" + datasetId + "/chunks", body);
    }

    public JsonNode getDocument(String datasetId, String documentId) {
        int page = 1, pageSize = 100;
        for (int p = 0; p < 20; p++) {
            JsonNode data = getJson("/api/v1/datasets/" + datasetId + "/documents?page=" + page + "&page_size=" + pageSize);
            if (data == null) return null;
            JsonNode docs = data.has("docs") ? data.get("docs") : data;
            if (docs == null || !docs.isArray() || docs.isEmpty()) return null;
            for (JsonNode doc : docs) {
                if (StringUtils.equals(doc.path("id").asText(), documentId)) return doc;
            }
            if (docs.size() < pageSize) return null;
            page++;
        }
        return null;
    }

    public void deleteDocuments(String datasetId, List<String> documentIds) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode arr = body.putArray("ids");
        documentIds.forEach(arr::add);
        deleteJson("/api/v1/datasets/" + datasetId + "/documents", body);
    }

    public JsonNode retrieve(String datasetId, String question, int topK, Map<String, String> metaFilter) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("question", question);
        ArrayNode datasets = body.putArray("dataset_ids");
        datasets.add(datasetId);
        body.put("top_k", topK);
        body.put("similarity_threshold", properties.getSimilarityThreshold());
        body.put("vector_similarity_weight", 1.0 - properties.getKeywordWeight());
        body.put("keyword", true);
        if (metaFilter != null && !metaFilter.isEmpty()) {
            ObjectNode meta = body.putObject("meta");
            metaFilter.forEach(meta::put);
        }
        return postJson("/api/v1/retrieval", body);
    }

    // ------------------------------------------------------------------
    // Low-level HTTP
    // ------------------------------------------------------------------

    private JsonNode getJson(String path) {
        HttpRequest request = baseRequestBuilder(path).GET().build();
        return execute(request);
    }

    private JsonNode postJson(String path, ObjectNode body) {
        try {
            HttpRequest request = baseRequestBuilder(path)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            return execute(request);
        } catch (IOException e) {
            throw new RagflowFilingClientException(-1, "serialize body failed: " + e.getMessage(), e);
        }
    }

    private JsonNode putJson(String path, ObjectNode body) {
        try {
            HttpRequest request = baseRequestBuilder(path)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            return execute(request);
        } catch (IOException e) {
            throw new RagflowFilingClientException(-1, "serialize body failed: " + e.getMessage(), e);
        }
    }

    private void deleteJson(String path, ObjectNode body) {
        try {
            HttpRequest request = baseRequestBuilder(path)
                    .header("Content-Type", "application/json")
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            execute(request);
        } catch (IOException e) {
            throw new RagflowFilingClientException(-1, "serialize body failed: " + e.getMessage(), e);
        }
    }

    private HttpRequest.Builder baseRequestBuilder(String path) {
        String url = StringUtils.removeEnd(properties.getBaseUrl(), "/") + path;
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey());
    }

    private JsonNode execute(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return handleResponse(request, response);
        } catch (IOException e) {
            throw new RagflowFilingClientException(-1, "HTTP IO error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagflowFilingClientException(-1, "HTTP interrupted", e);
        }
    }

    private JsonNode handleResponse(HttpRequest request, HttpResponse<String> response) {
        int status = response.statusCode();
        String bodyText = response.body();
        if (status < 200 || status >= 300) {
            log.warn("ragflow-filing {} {} -> HTTP {}: {}", request.method(), request.uri(), status, StringUtils.abbreviate(bodyText, 500));
            throw new RagflowFilingClientException(status, "HTTP " + status + ": " + StringUtils.abbreviate(bodyText, 200));
        }
        if (StringUtils.isBlank(bodyText)) return null;
        try {
            JsonNode root = objectMapper.readTree(bodyText);
            int code = root.path("code").asInt(0);
            if (code != 0) {
                String msg = root.path("message").asText("unknown");
                log.warn("ragflow-filing {} {} -> code {} msg {}", request.method(), request.uri(), code, msg);
                throw new RagflowFilingClientException(code, "RAGFlow error: code=" + code + " msg=" + msg);
            }
            return root.has("data") ? root.get("data") : root;
        } catch (IOException e) {
            throw new RagflowFilingClientException(-1, "parse json failed: " + e.getMessage(), e);
        }
    }

    private byte[] buildMultipartBody(String boundary, String filename, byte[] content) throws IOException {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n\r\n";
        String footer = "\r\n--" + boundary + "--\r\n";
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        byte[] footerBytes = footer.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[headerBytes.length + content.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, out, 0, headerBytes.length);
        System.arraycopy(content, 0, out, headerBytes.length, content.length);
        System.arraycopy(footerBytes, 0, out, headerBytes.length + content.length, footerBytes.length);
        return out;
    }

    private void setJsonValue(ObjectNode node, String key, Object v) {
        if (v == null) { node.putNull(key); return; }
        if (v instanceof String s) { node.put(key, s); return; }
        if (v instanceof Boolean b) { node.put(key, b); return; }
        if (v instanceof Integer i) { node.put(key, i.intValue()); return; }
        if (v instanceof Long l) { node.put(key, l.longValue()); return; }
        if (v instanceof Double d) { node.put(key, d.doubleValue()); return; }
        if (v instanceof Float f) { node.put(key, f.floatValue()); return; }
        if (v instanceof Number n) { node.put(key, n.toString()); return; }
        node.put(key, v.toString());
    }

    public static Map<String, Object> newMeta() { return new LinkedHashMap<>(); }
}
