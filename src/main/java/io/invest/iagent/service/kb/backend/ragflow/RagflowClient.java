package io.invest.iagent.service.kb.backend.ragflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.invest.iagent.config.ApplicationProperties.RagflowProperties;
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
 * RAGFlow HTTP 客户端。
 * <p>
 * 覆盖 dataset / document / chunk-parse / retrieval 全部核心端点，是对 RAGFlow REST API 的薄封装：
 * <ul>
 *   <li>成功时返回解析后的 {@link JsonNode}（root 或 {@code data} 子节点）</li>
 *   <li>失败时抛 {@link RagflowClientException}，携带 HTTP 状态码与响应体，便于上层记录</li>
 * </ul>
 * 该客户端本身无重试逻辑；重试/轮询在 {@code RagflowKnowledgeBaseBackend} 中根据业务语义决定。
 */
@Slf4j
public class RagflowClient {

    private final RagflowProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagflowClient(RagflowProperties properties) {
        this.properties = properties;
        if (StringUtils.isBlank(properties.getApiKey())) {
            throw new IllegalStateException("app.ragflow.api-key 未配置：请在 application.properties 里设置，"
                    + "或用环境变量 APP_RAGFLOW_API_KEY=<你的 key> 注入");
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(properties.getRequestTimeoutSeconds(), 30)))
                .build();
    }

    // ---------------------------------------------------------------------
    // dataset
    // ---------------------------------------------------------------------

    /**
     * 按名称精确匹配 dataset，未找到返回 null。RAGFlow 的 name 全局唯一。
     * <p>
     * RAGFlow {@code GET /api/v1/datasets} 端点在部分版本上对未知 query 参数
     * （例如 {@code ?name=xxx}）会返回 401 而非 400，因此这里改为按页拉取再本地匹配。
     */
    public JsonNode findDatasetByName(String name) {
        int page = 1;
        int pageSize = 100;
        while (true) {
            JsonNode data = getJson("/api/v1/datasets?page=" + page + "&page_size=" + pageSize);
            if (data == null || !data.isArray() || data.isEmpty()) {
                return null;
            }
            for (JsonNode ds : data) {
                if (StringUtils.equals(ds.path("name").asText(), name)) {
                    return ds;
                }
            }
            if (data.size() < pageSize) {
                return null;
            }
            page++;
            // 硬上限：一次找库最多扫 1000 个 dataset，超过说明命名不合理，及时兜底
            if (page > 10) {
                return null;
            }
        }
    }

    /**
     * 创建 dataset。使用配置中的 parser / chunk 大小 / embedding 模型。
     */
    public JsonNode createDataset(String name) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("chunk_method", properties.getParserMethod());
        if (StringUtils.isNotBlank(properties.getEmbeddingModel())) {
            body.put("embedding_model", properties.getEmbeddingModel());
        }
        ObjectNode parserConfig = body.putObject("parser_config");
        parserConfig.put("chunk_token_num", properties.getChunkTokenNum());
        return postJson("/api/v1/datasets", body);
    }

    /**
     * 保证 dataset 存在：先查后建。返回 dataset id。
     */
    public String ensureDataset(String name) {
        JsonNode existing = findDatasetByName(name);
        if (existing != null && existing.hasNonNull("id")) {
            return existing.get("id").asText();
        }
        JsonNode created = createDataset(name);
        if (created == null || !created.hasNonNull("id")) {
            throw new RagflowClientException(-1, "create dataset failed, empty id: " + name);
        }
        return created.get("id").asText();
    }

    // ---------------------------------------------------------------------
    // document
    // ---------------------------------------------------------------------

    /**
     * 列出 dataset 下全部文档。用于 list() 与幂等 build 前的重复检测。
     * <p>
     * RAGFlow 强制 {@code page_size ≤ 100}，因此逐页拉取直到当页少于 pageSize 或到达兜底页上限。
     */
    public List<JsonNode> listDocuments(String datasetId) {
        List<JsonNode> results = new ArrayList<>();
        int pageSize = 100;
        for (int page = 1; page <= 50; page++) {
            JsonNode data = getJson("/api/v1/datasets/" + datasetId + "/documents?page=" + page + "&page_size=" + pageSize);
            if (data == null) {
                break;
            }
            JsonNode docs = data.has("docs") ? data.get("docs") : data;
            if (docs == null || !docs.isArray() || docs.isEmpty()) {
                break;
            }
            docs.forEach(results::add);
            if (docs.size() < pageSize) {
                break;
            }
        }
        return results;
    }

    /**
     * 上传单个文档（multipart/form-data）。返回创建的 document 节点。
     */
    public JsonNode uploadDocument(String datasetId, Path file) {
        String boundary = "----IAgentBoundary" + UUID.randomUUID().toString().replace("-", "");
        try {
            byte[] fileBytes = Files.readAllBytes(file);
            byte[] body = buildMultipartBody(boundary, file.getFileName().toString(), fileBytes);
            HttpRequest request = baseRequestBuilder("/api/v1/datasets/" + datasetId + "/documents")
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode parsed = handleResponse(request, response);
            // 上传接口返回的是 data:[{...}]，取第一条
            if (parsed != null && parsed.isArray() && !parsed.isEmpty()) {
                return parsed.get(0);
            }
            return parsed;
        } catch (IOException e) {
            throw new RagflowClientException(-1, "upload document IO error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagflowClientException(-1, "upload document interrupted", e);
        }
    }

    /**
     * 更新文档 metadata / meta_fields，用于携带 ticker、formType、fiscalYear 等业务字段。
     */
    public void updateDocumentMeta(String datasetId, String documentId, Map<String, Object> metaFields) {
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode meta = body.putObject("meta_fields");
        metaFields.forEach((k, v) -> meta.putPOJO(k, v));
        putJson("/api/v1/datasets/" + datasetId + "/documents/" + documentId, body);
    }

    /**
     * 触发 dataset 内一批 document 的解析（chunk 生成 + 向量化）。异步执行。
     */
    public void parseDocuments(String datasetId, List<String> documentIds) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode arr = body.putArray("document_ids");
        documentIds.forEach(arr::add);
        postJson("/api/v1/datasets/" + datasetId + "/chunks", body);
    }

    /**
     * 查询单个文档的 parse 状态。返回原始节点，关注 {@code run} 与 {@code progress} 字段。
     * <p>
     * RAGFlow 未在文档列表端点上开放稳定的 id 过滤参数，故采用分页拉取 + 本地匹配。
     */
    public JsonNode getDocument(String datasetId, String documentId) {
        int page = 1;
        int pageSize = 100;
        while (true) {
            JsonNode data = getJson("/api/v1/datasets/" + datasetId + "/documents?page=" + page + "&page_size=" + pageSize);
            if (data == null) {
                return null;
            }
            JsonNode docs = data.has("docs") ? data.get("docs") : data;
            if (docs == null || !docs.isArray() || docs.isEmpty()) {
                return null;
            }
            for (JsonNode doc : docs) {
                if (StringUtils.equals(doc.path("id").asText(), documentId)) {
                    return doc;
                }
            }
            if (docs.size() < pageSize) {
                return null;
            }
            page++;
            if (page > 20) {
                return null;
            }
        }
    }

    /**
     * 删除 dataset 下的一批文档。
     */
    public void deleteDocuments(String datasetId, List<String> documentIds) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode arr = body.putArray("ids");
        documentIds.forEach(arr::add);
        deleteJson("/api/v1/datasets/" + datasetId + "/documents", body);
    }

    // ---------------------------------------------------------------------
    // retrieval
    // ---------------------------------------------------------------------

    /**
     * 向 RAGFlow 检索。metaFilter 中的键值对会拼进 {@code meta} 字段做等值过滤。
     */
    public JsonNode retrieve(String datasetId, String question, int topK,
                             Map<String, String> metaFilter) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("question", question);
        ArrayNode datasets = body.putArray("dataset_ids");
        datasets.add(datasetId);
        body.put("top_k", topK);
        body.put("similarity_threshold", properties.getSimilarityThreshold());
        body.put("vector_similarity_weight", 1.0f - properties.getKeywordSimilarityWeight());
        body.put("keyword", true);
        if (metaFilter != null && !metaFilter.isEmpty()) {
            ObjectNode meta = body.putObject("meta");
            metaFilter.forEach(meta::put);
        }
        return postJson("/api/v1/retrieval", body);
    }

    // ---------------------------------------------------------------------
    // low level
    // ---------------------------------------------------------------------

    private JsonNode getJson(String path) {
        HttpRequest request = baseRequestBuilder(path).GET().build();
        return execute(request);
    }

    private JsonNode postJson(String path, ObjectNode body) {
        HttpRequest request;
        try {
            request = baseRequestBuilder(path)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (IOException e) {
            throw new RagflowClientException(-1, "serialize body failed: " + e.getMessage(), e);
        }
        return execute(request);
    }

    private JsonNode putJson(String path, ObjectNode body) {
        HttpRequest request;
        try {
            request = baseRequestBuilder(path)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (IOException e) {
            throw new RagflowClientException(-1, "serialize body failed: " + e.getMessage(), e);
        }
        return execute(request);
    }

    private void deleteJson(String path, ObjectNode body) {
        HttpRequest request;
        try {
            request = baseRequestBuilder(path)
                    .header("Content-Type", "application/json")
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (IOException e) {
            throw new RagflowClientException(-1, "serialize body failed: " + e.getMessage(), e);
        }
        execute(request);
    }

    private HttpRequest.Builder baseRequestBuilder(String path) {
        String url = StringUtils.removeEnd(properties.getBaseUrl(), "/") + path;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey());
        return builder;
    }

    private JsonNode execute(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return handleResponse(request, response);
        } catch (IOException e) {
            throw new RagflowClientException(-1, "HTTP IO error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagflowClientException(-1, "HTTP interrupted", e);
        }
    }

    private JsonNode handleResponse(HttpRequest request, HttpResponse<String> response) {
        int status = response.statusCode();
        String bodyText = response.body();
        if (status < 200 || status >= 300) {
            log.warn("ragflow {} {} -> HTTP {}: {}", request.method(), request.uri(), status, StringUtils.abbreviate(bodyText, 500));
            throw new RagflowClientException(status, "HTTP " + status + ": " + StringUtils.abbreviate(bodyText, 200));
        }
        if (StringUtils.isBlank(bodyText)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(bodyText);
            // RAGFlow 统一响应结构：{ code: 0, data: <...>, message: "..." }
            int code = root.path("code").asInt(0);
            if (code != 0) {
                String msg = root.path("message").asText("unknown");
                log.warn("ragflow {} {} -> code {} msg {}", request.method(), request.uri(), code, msg);
                throw new RagflowClientException(code, "RAGFlow error: code=" + code + " msg=" + msg);
            }
            return root.has("data") ? root.get("data") : root;
        } catch (IOException e) {
            throw new RagflowClientException(-1, "parse json failed: " + e.getMessage(), e);
        }
    }

    private byte[] buildMultipartBody(String boundary, String filename, byte[] content) throws IOException {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        String footer = "\r\n--" + boundary + "--\r\n";
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        byte[] footerBytes = footer.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[headerBytes.length + content.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, out, 0, headerBytes.length);
        System.arraycopy(content, 0, out, headerBytes.length, content.length);
        System.arraycopy(footerBytes, 0, out, headerBytes.length + content.length, footerBytes.length);
        return out;
    }

    private static String urlEncode(String v) {
        return java.net.URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    /**
     * 便于外部构造 metadata 映射时保序。
     */
    public static Map<String, Object> newMeta() {
        return new LinkedHashMap<>();
    }
}
