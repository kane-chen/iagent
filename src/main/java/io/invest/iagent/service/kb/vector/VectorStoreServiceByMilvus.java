package io.invest.iagent.service.kb.vector;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.service.kb.model.KnowledgeBaseSearchFilter;
import io.invest.iagent.service.kb.category.FilingContentCategory;
import io.invest.iagent.service.kb.model.KnowledgeBaseChunkDTO;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class VectorStoreServiceByMilvus implements VectorStoreService {

    private final HttpClient httpClient;
    private final String endpoint;
    private final String token;
    private final String collection;
    private final String summaryCollection;

    private static final List<String> OUTPUT_FIELDS = List.of("chunk_id", "ticker", "document_id", "form_type",
            "fiscal_year", "fiscal_period", "filing_date", "chunk_type", "source_file_name", "section_title",
            "category", "text", "metadata_json");

    public VectorStoreServiceByMilvus(String endpoint, String token, String collection) {
        this(endpoint, token, collection, collection + "_summaries");
    }

    public VectorStoreServiceByMilvus(String endpoint, String token, String collection, String summaryCollection) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.endpoint = StringUtils.removeEnd(endpoint, "/");
        this.token = token;
        this.collection = collection;
        this.summaryCollection = summaryCollection;
    }

    @Override
    public void upsert(List<KnowledgeBaseChunkDTO> chunks, List<List<Float>> embeddings) {
        upsertToCollection(collection, chunks, embeddings, false);
    }

    private void upsertToCollection(String targetCollection, List<KnowledgeBaseChunkDTO> chunks, List<List<Float>> embeddings, boolean summary) {
        try{
            JSONArray data = new JSONArray();
            for(int i=0;i<chunks.size();i++){
                KnowledgeBaseChunkDTO chunk = chunks.get(i);
                JSONObject row = new JSONObject();
                row.put("chunk_id", chunk.getChunkId());
                row.put("knowledge_base_id", "filing_kb_" + chunk.getTicker());
                row.put("ticker", chunk.getTicker());
                row.put("document_id", chunk.getDocumentId());
                row.put("form_type", chunk.getFormType());
                row.put("fiscal_year", chunk.getFiscalYear());
                row.put("fiscal_period", chunk.getFiscalPeriod());
                row.put("filing_date", chunk.getFilingDate());
                row.put("chunk_type", chunk.getChunkType());
                row.put("source_file_name", chunk.getSourceFileName());
                row.put("section_title", chunk.getSectionTitle());
                row.put("category", categoryOf(chunk));
                row.put("text", StringUtils.left(summary ? summaryOf(chunk) : chunk.getText(), 16000));
                row.put("metadata_json", JSON.toJSONString(chunk.getMetadata()));
                row.put("embedding", embeddings.get(i));
//                row.put("vector", embeddings.get(i));
                data.add(row);
            }
            JSONObject body = new JSONObject();
            body.put("collectionName", targetCollection);
            body.put("data", data);
            post("/v2/vectordb/entities/upsert", body);
        }catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    @Override
    public void upsertSummaryCandidates(List<KnowledgeBaseChunkDTO> chunks, List<List<Float>> summaryEmbeddings) {
        upsertToCollection(summaryCollection, chunks, summaryEmbeddings, true);
    }

    @Override
    public List<KnowledgeBaseChunkDTO> searchSummaryCandidates(String query, List<Float> embedding, KnowledgeBaseSearchFilter filter) {
        return searchCollection(summaryCollection, embedding, filter);
    }

    @Override
    public List<KnowledgeBaseChunkDTO> search(String query, List<Float> embedding, KnowledgeBaseSearchFilter filter) {
        return searchCollection(collection, embedding, filter);
    }

    private List<KnowledgeBaseChunkDTO> searchCollection(String targetCollection, List<Float> embedding, KnowledgeBaseSearchFilter filter) {
        try{
            JSONObject body = new JSONObject();
            body.put("collectionName", targetCollection);
            body.put("data", List.of(embedding));
            body.put("limit", filter == null || filter.getTopK() <= 0 ? 5 : filter.getTopK());
            body.put("outputFields", OUTPUT_FIELDS);
            String filterExpression = filter(filter, null);
            if(StringUtils.isNotBlank(filterExpression)){
                body.put("filter", filterExpression);
            }
            JSONObject response = post("/v2/vectordb/entities/search", body);
            JSONArray data = response.getJSONArray("data");
            List<KnowledgeBaseChunkDTO> result = new ArrayList<>();
            if(data != null){
                for(int i=0;i<data.size();i++){
                    JSONObject row = data.getJSONObject(i);
                    result.add(fromMilvus(row));
                }
            }
            return result;
        }catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    @Override
    public int delete(String ticker, String documentId) {
        try{
            JSONObject body = new JSONObject();
            body.put("collectionName", collection);
            body.put("filter", filter(KnowledgeBaseSearchFilter.builder().ticker(ticker).build(), documentId));
            JSONObject response = post("/v2/vectordb/entities/delete", body);
            try{
                JSONObject summaryBody = new JSONObject();
                summaryBody.put("collectionName", summaryCollection);
                summaryBody.put("filter", filter(KnowledgeBaseSearchFilter.builder().ticker(ticker).build(), documentId));
                post("/v2/vectordb/entities/delete", summaryBody);
            }catch (Exception ignored){
                // Summary collection is optional; preserve detailed delete semantics.
            }
            return response.getIntValue("deleteCount");
        }catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<KnowledgeBaseChunkDTO> list(String ticker) {
        try{
            JSONObject body = new JSONObject();
            body.put("collectionName", collection);
            body.put("filter", filter(KnowledgeBaseSearchFilter.builder().ticker(ticker).build(), null));
            body.put("outputFields", OUTPUT_FIELDS);
            JSONObject response = post("/v2/vectordb/entities/query", body);
            JSONArray data = response.getJSONArray("data");
            List<KnowledgeBaseChunkDTO> result = new ArrayList<>();
            if(data != null){
                for(int i=0;i<data.size();i++){
                    result.add(fromMilvus(data.getJSONObject(i)));
                }
            }
            return result;
        }catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    private JSONObject post(String path, JSONObject body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(body)));
        if(StringUtils.isNotBlank(token)){
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if(response.statusCode() < 200 || response.statusCode() >= 300){
            throw new IOException("Milvus request failed: " + response.statusCode() + " " + response.body());
        }
        return JSON.parseObject(response.body());
    }

    private String filter(KnowledgeBaseSearchFilter filter, String documentId){
        List<String> filters = new ArrayList<>();
        if(filter != null && StringUtils.isNotBlank(filter.getTicker())){
            filters.add("ticker == " + quote(StringUtils.upperCase(filter.getTicker())));
        }
        if(filter != null && StringUtils.isNotBlank(filter.getFiscalYear())){
            filters.add("fiscal_year == " + filter.getFiscalYear());
        }
        if(filter != null && StringUtils.isNotBlank(filter.getFormType())){
            filters.add("form_type == " + quote(filter.getFormType()));
        }
        if(StringUtils.isNotBlank(documentId)){
            filters.add("document_id == " + quote(documentId));
        }
        if(filter != null && StringUtils.isNotBlank(filter.getCategory())){
            filters.add("category == " + quote(categoryOf(filter.getCategory())));
        }
        if(filter != null && filter.getChunkIds() != null && !filter.getChunkIds().isEmpty()){
            filters.add("chunk_id in [" + filter.getChunkIds().stream().map(this::quote).collect(java.util.stream.Collectors.joining(",")) + "]");
        }
        return String.join(" && ", filters);
    }

    private String quote(String value) {
        return "\"" + StringUtils.defaultString(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String summaryOf(KnowledgeBaseChunkDTO chunk) {
        Object summary = chunk.getMetadata() == null ? null : chunk.getMetadata().get("chunk_summary");
        return summary == null ? "" : String.valueOf(summary);
    }

    private String categoryOf(KnowledgeBaseChunkDTO chunk) {
        if (StringUtils.isNotBlank(chunk.getCategory())) {
            return categoryOf(chunk.getCategory());
        }
        Object category = chunk.getMetadata() == null ? null : chunk.getMetadata().get("content_category");
        return categoryOf(category == null ? null : String.valueOf(category));
    }

    private String categoryOf(String category) {
        return StringUtils.defaultIfBlank(FilingContentCategory.normalizeCode(category), FilingContentCategory.OTHER.code());
    }

    private String categoryOf(JSONObject entity, Map<String,Object> metadata) {
        Object metadataCategory = metadata == null ? null : metadata.get("content_category");
        return StringUtils.firstNonBlank(
                FilingContentCategory.normalizeCode(entity.getString("category")),
                FilingContentCategory.normalizeCode(metadataCategory == null ? null : String.valueOf(metadataCategory)),
                FilingContentCategory.OTHER.code());
    }

    private KnowledgeBaseChunkDTO fromMilvus(JSONObject row){
        JSONObject entity = row.containsKey("entity") ? row.getJSONObject("entity") : row;
        Map<String,Object> metadata = Map.of();
        if(StringUtils.isNotBlank(entity.getString("metadata_json"))){
            metadata = JSON.parseObject(entity.getString("metadata_json"));
        }
        return KnowledgeBaseChunkDTO.builder()
                .chunkId(entity.getString("chunk_id"))
                .score(row.getDouble("distance"))
                .text(entity.getString("text"))
                .ticker(entity.getString("ticker"))
                .documentId(entity.getString("document_id"))
                .formType(entity.getString("form_type"))
                .fiscalYear(entity.getInteger("fiscal_year"))
                .fiscalPeriod(entity.getString("fiscal_period"))
                .filingDate(entity.getString("filing_date"))
                .sourceFileName(entity.getString("source_file_name"))
                .sectionTitle(entity.getString("section_title"))
                .chunkType(entity.getString("chunk_type"))
                .category(categoryOf(entity, metadata))
                .citation(entity.getString("ticker") + " " + entity.getString("document_id") + " " + entity.getString("source_file_name"))
                .metadata(metadata)
                .build();
    }
}
