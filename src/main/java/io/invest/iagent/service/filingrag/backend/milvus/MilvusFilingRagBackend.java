package io.invest.iagent.service.filingrag.backend.milvus;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.service.filingrag.backend.FilingRagBackend;
import io.invest.iagent.service.filingrag.embed.EmbeddingProvider;
import io.invest.iagent.service.filingrag.model.FilingChunk;
import io.invest.iagent.service.filingrag.model.FilingQuery;
import io.invest.iagent.service.filingrag.model.FilingQueryResult;
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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link FilingRagBackend} backed by Milvus via its v2 REST API ({@code /v2/vectordb/...}).
 * <p>
 * Schema (auto-created on first healthCheck or upsert):
 * <ul>
 *   <li>id (Int64, primary key, auto-id)</li>
 *   <li>chunk_id (VarChar)</li>
 *   <li>ticker / document_id / form_type / fiscal_period / filing_date / source_file_name / section_title (VarChar)</li>
 *   <li>fiscal_year (Int32), page_number (Int32)</li>
 *   <li>content (VarChar, max 65535)</li>
 *   <li>vector (FloatVector, dim from config, COSINE metric, AUTOINDEX)</li>
 *   <li>metadata_json (VarChar) - JSON-serialized metadata map</li>
 * </ul>
 */
@Slf4j
public class MilvusFilingRagBackend implements FilingRagBackend {

    private static final List<String> OUTPUT_FIELDS = List.of(
            "chunk_id", "ticker", "document_id", "form_type", "fiscal_year",
            "fiscal_period", "filing_date", "source_file_name", "section_title",
            "page_number", "content", "metadata_json");

    private final HttpClient httpClient;
    private final String endpoint;
    private final String token;
    private final String collection;
    private final int insertBatchSize;
    private final EmbeddingProvider embeddingProvider;
    private volatile boolean schemaEnsured = false;

    public MilvusFilingRagBackend(String endpoint, String token, String collection,
                                  int insertBatchSize, EmbeddingProvider embeddingProvider) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.endpoint = StringUtils.removeEnd(endpoint, "/");
        this.token = token;
        this.collection = collection;
        this.insertBatchSize = insertBatchSize;
        this.embeddingProvider = embeddingProvider;
    }

    @Override
    public String name() {
        return "milvus";
    }

    @Override
    public void healthCheck() {
        try {
            ensureCollection();
            // simple query to verify availability
            JSONObject body = new JSONObject();
            body.put("collectionName", collection);
            body.put("limit", 1);
            post("/v2/vectordb/entities/query", body);
        } catch (Exception e) {
            throw new RuntimeException("Milvus health check failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void upsertDocument(String ticker, String documentId,
                               List<FilingChunk> chunks, List<List<Float>> embeddings) {
        ensureCollection();
        try {
            // Delete existing chunks for this documentId for idempotent rebuilds
            delete(ticker, documentId);

            int total = chunks.size();
            for (int i = 0; i < total; i += insertBatchSize) {
                int end = Math.min(i + insertBatchSize, total);
                JSONArray data = new JSONArray();
                for (int j = i; j < end; j++) {
                    FilingChunk c = chunks.get(j);
                    JSONObject row = new JSONObject();
                    row.put("chunk_id", c.getChunkId());
                    row.put("ticker", StringUtils.upperCase(c.getTicker()));
                    row.put("document_id", c.getDocumentId());
                    row.put("form_type", c.getFormType());
                    row.put("fiscal_year", c.getFiscalYear());
                    row.put("fiscal_period", c.getFiscalPeriod());
                    row.put("filing_date", c.getFilingDate());
                    row.put("source_file_name", c.getSourceFileName());
                    row.put("section_title", c.getSectionTitle());
                    row.put("page_number", c.getPageNumber());
                    row.put("content", StringUtils.left(c.getContent(), 60000));
                    row.put("vector", embeddings.get(j));
                    row.put("metadata_json", JSON.toJSONString(c.getMetadata() == null ? Map.of() : c.getMetadata()));
                    data.add(row);
                }
                JSONObject body = new JSONObject();
                body.put("collectionName", collection);
                body.put("data", data);
                post("/v2/vectordb/entities/insert", body);
                log.debug("Milvus inserted batch {}/{} for document {}", end, total, documentId);
            }
            log.info("Milvus upserted {} chunks for ticker={} document={}", total, ticker, documentId);
        } catch (Exception e) {
            throw new RuntimeException("Milvus upsert failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int delete(String ticker, String documentId) {
        ensureCollection();
        try {
            JSONObject body = new JSONObject();
            body.put("collectionName", collection);
            body.put("filter", buildDeleteFilter(ticker, documentId));
            JSONObject resp = post("/v2/vectordb/entities/delete", body);
            return resp.getIntValue("deleteCount", 0);
        } catch (Exception e) {
            throw new RuntimeException("Milvus delete failed: " + e.getMessage(), e);
        }
    }

    @Override
    public FilingQueryResult search(FilingQuery query, List<Float> queryEmbedding) {
        ensureCollection();
        long start = System.currentTimeMillis();
        try {
            // Recall with multiplier to allow post-filtering
            int recall = (query.getTopK() == null ? 5 : query.getTopK()) * 3;
            JSONObject body = new JSONObject();
            body.put("collectionName", collection);
            body.put("data", List.of(queryEmbedding));
            body.put("limit", recall);
            body.put("outputFields", OUTPUT_FIELDS);
            String filter = buildSearchFilter(query);
            if (StringUtils.isNotBlank(filter)) {
                body.put("filter", filter);
            }
            JSONObject resp = post("/v2/vectordb/entities/search", body);
            JSONArray data = resp.getJSONArray("data");
            List<FilingChunk> all = new ArrayList<>();
            if (data != null) {
                for (int i = 0; i < data.size(); i++) {
                    JSONObject row = data.getJSONObject(i);
                    FilingChunk c = fromMilvus(row);
                    // Post-filter by keyword
                    if (StringUtils.isNotBlank(query.getKeyword()) && c.getContent() != null) {
                        String kw = query.getKeyword().toLowerCase();
                        String hay = (c.getSectionTitle() + " " + c.getContent()).toLowerCase();
                        if (!hay.contains(kw)) continue;
                    }
                    // Post-filter fiscal year range
                    if (query.getFromFiscalYear() != null && c.getFiscalYear() != null
                            && c.getFiscalYear() < query.getFromFiscalYear()) continue;
                    if (query.getToFiscalYear() != null && c.getFiscalYear() != null
                            && c.getFiscalYear() > query.getToFiscalYear()) continue;
                    all.add(c);
                }
            }
            int topK = query.getTopK() == null ? 5 : query.getTopK();
            double threshold = query.getSimilarityThreshold() == null ? 0.0 : query.getSimilarityThreshold();
            List<FilingChunk> result = all.stream()
                    .filter(c -> c.getScore() == null || c.getScore() >= threshold)
                    .limit(topK)
                    .collect(Collectors.toList());
            return FilingQueryResult.builder()
                    .queryId(UUID.randomUUID().toString())
                    .question(query.getQuestion())
                    .ticker(query.getTicker())
                    .backend(name())
                    .elapsedMs(System.currentTimeMillis() - start)
                    .chunks(result)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Milvus search failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Collection schema management
    // ------------------------------------------------------------------

    private void ensureCollection() {
        if (schemaEnsured) return;
        synchronized (this) {
            if (schemaEnsured) return;
            try {
                if (collectionExists()) {
                    schemaEnsured = true;
                    return;
                }
                createCollection();
                createIndex();
                loadCollection();
                schemaEnsured = true;
                log.info("Milvus created collection '{}' with dim={}", collection, embeddingProvider.dimension());
            } catch (Exception e) {
                throw new RuntimeException("Failed to ensure Milvus collection: " + e.getMessage(), e);
            }
        }
    }

    private boolean collectionExists() throws IOException, InterruptedException {
        JSONObject body = new JSONObject();
        body.put("collectionName", collection);
        try {
            JSONObject resp = post("/v2/vectordb/collections/describe", body);
            return resp != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void createCollection() throws IOException, InterruptedException {
        int dim = embeddingProvider.dimension();
        JSONObject schema = new JSONObject();
        schema.put("autoId", true);
        schema.put("enabledDynamicField", false);
        JSONArray fields = new JSONArray();
        fields.add(field("id", "Int64", true, false, null, null, null, false));
        fields.add(field("chunk_id", "VarChar", false, false, 64, null, null, false));
        fields.add(varcharField("ticker", 16));
        fields.add(varcharField("document_id", 128));
        fields.add(varcharField("form_type", 16));
        fields.add(field("fiscal_year", "Int32", false, false, null, null, null, false));
        fields.add(varcharField("fiscal_period", 16));
        fields.add(varcharField("filing_date", 32));
        fields.add(varcharField("source_file_name", 256));
        fields.add(varcharField("section_title", 512));
        fields.add(field("page_number", "Int32", false, false, null, null, null, false));
        fields.add(varcharField("content", 65535));
        fields.add(vectorField("vector", dim));
        fields.add(varcharField("metadata_json", 8192));
        schema.put("fields", fields);
        JSONObject body = new JSONObject();
        body.put("collectionName", collection);
        body.put("schema", schema);
        // enable AUTOINDEX via indexParams on creation or separate call
        JSONObject indexParam = new JSONObject();
        indexParam.put("metricType", "COSINE");
        indexParam.put("indexType", "AUTOINDEX");
        body.put("indexParams", List.of(indexParam));
        // Hard-code dim: some Milvus versions require field_type_params with dim on the vector field
        post("/v2/vectordb/collections/create", body);
    }

    private JSONObject field(String name, String dataType, boolean isPrimary, boolean autoId,
                             Integer maxLength, String elementType, Integer dim, boolean nullable) {
        JSONObject f = new JSONObject();
        f.put("fieldName", name);
        f.put("dataType", dataType);
        if (isPrimary) f.put("isPrimary", true);
        if (autoId) f.put("autoId", true);
        if (maxLength != null) {
            JSONObject tp = new JSONObject();
            tp.put("max_length", maxLength);
            f.put("elementTypeParams", tp);
        }
        if (dim != null) {
            JSONObject tp = new JSONObject();
            tp.put("dim", dim);
            f.put("elementTypeParams", tp);
        }
        return f;
    }

    private JSONObject varcharField(String name, int maxLen) {
        return field(name, "VarChar", false, false, maxLen, null, null, false);
    }

    private JSONObject vectorField(String name, int dim) {
        return field(name, "FloatVector", false, false, null, null, dim, false);
    }

    private void createIndex() throws IOException, InterruptedException {
        JSONObject extra = new JSONObject();
        extra.put("index_type", "AUTOINDEX");
        extra.put("metric_type", "COSINE");
        JSONObject body = new JSONObject();
        body.put("collectionName", collection);
        body.put("indexParams", List.of(new JSONObject()
                .fluentPut("fieldName", "vector")
                .fluentPut("indexName", "vector_idx")
                .fluentPut("metricType", "COSINE")
                .fluentPut("indexType", "AUTOINDEX")
                .fluentPut("params", extra)));
        try {
            post("/v2/vectordb/indexes/create", body);
        } catch (RuntimeException e) {
            log.warn("Index creation returned non-fatal error (index may already exist): {}", e.getMessage());
        }
    }

    private void loadCollection() throws IOException, InterruptedException {
        JSONObject body = new JSONObject();
        body.put("collectionName", collection);
        try {
            post("/v2/vectordb/collections/load", body);
        } catch (RuntimeException e) {
            log.warn("Collection load returned non-fatal error: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // HTTP & query helpers
    // ------------------------------------------------------------------

    private JSONObject post(String path, JSONObject body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(body), StandardCharsets.UTF_8));
        if (StringUtils.isNotBlank(token)) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Milvus " + path + " HTTP " + response.statusCode() + ": " +
                    StringUtils.abbreviate(response.body(), 500));
        }
        return JSON.parseObject(response.body());
    }

    private String buildDeleteFilter(String ticker, String documentId) {
        List<String> parts = new ArrayList<>();
        if (StringUtils.isNotBlank(ticker)) {
            parts.add("ticker == " + quote(StringUtils.upperCase(ticker)));
        }
        if (StringUtils.isNotBlank(documentId)) {
            parts.add("document_id == " + quote(documentId));
        }
        return String.join(" && ", parts);
    }

    private String buildSearchFilter(FilingQuery q) {
        List<String> parts = new ArrayList<>();
        if (StringUtils.isNotBlank(q.getTicker())) {
            parts.add("ticker == " + quote(StringUtils.upperCase(q.getTicker())));
        }
        if (StringUtils.isNotBlank(q.getFormType())) {
            parts.add("form_type == " + quote(q.getFormType()));
        }
        if (StringUtils.isNotBlank(q.getFiscalPeriod())) {
            parts.add("fiscal_period == " + quote(q.getFiscalPeriod()));
        }
        if (q.getFromFiscalYear() != null && q.getToFiscalYear() != null
                && q.getFromFiscalYear().equals(q.getToFiscalYear())) {
            parts.add("fiscal_year == " + q.getFromFiscalYear());
        } else {
            if (q.getFromFiscalYear() != null) {
                parts.add("fiscal_year >= " + q.getFromFiscalYear());
            }
            if (q.getToFiscalYear() != null) {
                parts.add("fiscal_year <= " + q.getToFiscalYear());
            }
        }
        return String.join(" && ", parts);
    }

    private String quote(String v) {
        return "\"" + StringUtils.defaultString(v).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private FilingChunk fromMilvus(JSONObject row) {
        JSONObject entity = row.containsKey("entity") ? row.getJSONObject("entity") : row;
        Map<String, Object> metadata = Map.of();
        String metaJson = entity.getString("metadata_json");
        if (StringUtils.isNotBlank(metaJson)) {
            try {
                metadata = JSON.parseObject(metaJson);
            } catch (Exception ignored) {}
        }
        // Milvus COSINE distance is returned as "distance"; higher = more similar (cosine similarity in [0,1] range)
        Double score = row.getDouble("distance");
        if (score == null) score = row.getDouble("score");
        return FilingChunk.builder()
                .chunkId(entity.getString("chunk_id"))
                .ticker(entity.getString("ticker"))
                .documentId(entity.getString("document_id"))
                .formType(entity.getString("form_type"))
                .fiscalYear(entity.getInteger("fiscal_year"))
                .fiscalPeriod(entity.getString("fiscal_period"))
                .filingDate(entity.getString("filing_date"))
                .sourceFileName(entity.getString("source_file_name"))
                .sectionTitle(entity.getString("section_title"))
                .pageNumber(entity.getInteger("page_number"))
                .content(entity.getString("content"))
                .score(score)
                .metadata(metadata)
                .build();
    }
}
