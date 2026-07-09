package io.invest.iagent.service.filingrag.backend.ragflow;

import com.fasterxml.jackson.databind.JsonNode;
import io.invest.iagent.service.filingrag.FilingRagConfig;
import io.invest.iagent.service.filingrag.backend.FilingRagBackend;
import io.invest.iagent.service.filingrag.embed.EmbeddingProvider;
import io.invest.iagent.service.filingrag.model.FilingChunk;
import io.invest.iagent.service.filingrag.model.FilingQuery;
import io.invest.iagent.service.filingrag.model.FilingQueryResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link FilingRagBackend} backed by RAGFlow (HTTP API).
 * <p>
 * One RAGFlow dataset per ticker (prefix + ticker). Each Java-produced chunk is uploaded
 * as a separate .txt document with {@code chunk_method=naive} and {@code chunk_token_num=100000},
 * so RAGFlow treats it as a single chunk — guaranteeing that RAGFlow chunks align exactly
 * with Java-side chunks. Meta fields carry chunk metadata for retrieval filtering.
 */
@Slf4j
public class RagflowFilingRagBackend implements FilingRagBackend {

    private final FilingRagConfig.Ragflow config;
    private final RagflowFilingClient client;
    private final EmbeddingProvider embeddingProvider;

    public RagflowFilingRagBackend(FilingRagConfig.Ragflow config, EmbeddingProvider embeddingProvider) {
        this.config = config;
        this.client = new RagflowFilingClient(config);
        this.embeddingProvider = embeddingProvider;
    }

    @Override
    public String name() { return "ragflow"; }

    @Override
    public void healthCheck() {
        // List datasets as a simple liveness probe
        client.findDatasetByName("__nonexistent_probe__");
    }

    @Override
    public void upsertDocument(String ticker, String documentId,
                               List<FilingChunk> chunks, List<List<Float>> embeddings) {
        String datasetName = datasetNameFor(ticker);
        String datasetId = client.ensureDataset(datasetName);
        // Delete existing chunks for this documentId
        deleteExistingDocumentsForDocId(datasetId, documentId);

        List<String> createdDocIds = new ArrayList<>();
        try {
            int idx = 0;
            for (FilingChunk c : chunks) {
                String filename = safeFilename(c.getChunkId() + ".txt");
                String content = StringUtils.isNotBlank(c.getSectionTitle())
                        ? "Section: " + c.getSectionTitle() + "\n" + c.getContent()
                        : StringUtils.defaultString(c.getContent());
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("ticker", StringUtils.upperCase(c.getTicker()));
                meta.put("documentId", c.getDocumentId());
                meta.put("formType", c.getFormType());
                meta.put("fiscalYear", c.getFiscalYear());
                meta.put("fiscalPeriod", c.getFiscalPeriod());
                meta.put("filingDate", c.getFilingDate());
                meta.put("sectionTitle", c.getSectionTitle());
                meta.put("chunkId", c.getChunkId());
                meta.put("sourceFileName", c.getSourceFileName());
                meta.put("pageNumber", c.getPageNumber());
                JsonNode doc = client.uploadTextDocument(datasetId, filename, content, meta);
                if (doc != null && doc.hasNonNull("id")) {
                    createdDocIds.add(doc.get("id").asText());
                }
                idx++;
                if (idx % 50 == 0) {
                    log.debug("RAGFlow uploaded {}/{} chunks for document {}", idx, chunks.size(), documentId);
                }
            }
            // Trigger async parse + embed
            if (!createdDocIds.isEmpty()) {
                client.parseDocuments(datasetId, createdDocIds);
                waitForParse(datasetId, createdDocIds);
            }
            log.info("RAGFlow upserted {} chunks for ticker={} document={} into dataset={}",
                    chunks.size(), ticker, documentId, datasetName);
        } catch (Exception e) {
            throw new RuntimeException("RAGFlow upsert failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int delete(String ticker, String documentId) {
        String datasetName = datasetNameFor(ticker);
        JsonNode existing = client.findDatasetByName(datasetName);
        if (existing == null) return 0;
        String datasetId = existing.get("id").asText();
        List<JsonNode> docs = client.listDocuments(datasetId);
        List<String> toDelete = new ArrayList<>();
        for (JsonNode d : docs) {
            String metaDocId = d.path("meta_fields").path("documentId").asText(null);
            if (StringUtils.equals(metaDocId, documentId)
                    || StringUtils.equals(d.get("id").asText(), documentId)) {
                toDelete.add(d.get("id").asText());
            }
        }
        if (!toDelete.isEmpty()) {
            client.deleteDocuments(datasetId, toDelete);
        }
        return toDelete.size();
    }

    @Override
    public FilingQueryResult search(FilingQuery query, List<Float> queryEmbedding) {
        long start = System.currentTimeMillis();
        String datasetName = datasetNameFor(query.getTicker());
        JsonNode ds = client.findDatasetByName(datasetName);
        if (ds == null) {
            return FilingQueryResult.builder()
                    .queryId(UUID.randomUUID().toString())
                    .question(query.getQuestion())
                    .ticker(query.getTicker())
                    .backend(name())
                    .chunks(new ArrayList<>())
                    .elapsedMs(System.currentTimeMillis() - start)
                    .build();
        }
        String datasetId = ds.get("id").asText();
        int recall = (query.getTopK() == null ? 5 : query.getTopK()) * 3;
        Map<String, String> metaFilter = new HashMap<>();
        metaFilter.put("ticker", StringUtils.upperCase(query.getTicker()));
        if (StringUtils.isNotBlank(query.getFiscalPeriod())) {
            metaFilter.put("fiscalPeriod", query.getFiscalPeriod());
        }
        JsonNode resp = client.retrieve(datasetId, query.getQuestion(), recall, metaFilter);
        JsonNode chunks = resp != null ? resp.get("chunks") : null;
        if (chunks == null) chunks = resp;
        List<FilingChunk> all = new ArrayList<>();
        if (chunks != null && chunks.isArray()) {
            for (JsonNode c : chunks) {
                FilingChunk fc = fromRagflowChunk(c);
                if (fc == null) continue;
                // Post-filter keyword
                if (StringUtils.isNotBlank(query.getKeyword()) && fc.getContent() != null) {
                    String kw = query.getKeyword().toLowerCase();
                    String hay = (StringUtils.defaultString(fc.getSectionTitle()) + " " + fc.getContent()).toLowerCase();
                    if (!hay.contains(kw)) continue;
                }
                if (query.getFromFiscalYear() != null && fc.getFiscalYear() != null
                        && fc.getFiscalYear() < query.getFromFiscalYear()) continue;
                if (query.getToFiscalYear() != null && fc.getFiscalYear() != null
                        && fc.getFiscalYear() > query.getToFiscalYear()) continue;
                if (StringUtils.isNotBlank(query.getFormType())
                        && !StringUtils.equalsIgnoreCase(fc.getFormType(), query.getFormType())) continue;
                all.add(fc);
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
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String datasetNameFor(String ticker) {
        return config.getDatasetPrefix() + StringUtils.upperCase(ticker);
    }

    private void deleteExistingDocumentsForDocId(String datasetId, String documentId) {
        List<JsonNode> docs = client.listDocuments(datasetId);
        List<String> toDelete = new ArrayList<>();
        for (JsonNode d : docs) {
            String metaDocId = d.path("meta_fields").path("documentId").asText(null);
            if (StringUtils.equals(metaDocId, documentId)) {
                toDelete.add(d.get("id").asText());
            }
        }
        if (!toDelete.isEmpty()) {
            client.deleteDocuments(datasetId, toDelete);
        }
    }

    private void waitForParse(String datasetId, List<String> docIds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + config.getParsePollTimeoutSeconds() * 1000L;
        long interval = Math.max(1, config.getParsePollIntervalSeconds()) * 1000L;
        while (System.currentTimeMillis() < deadline) {
            boolean allDone = true;
            for (String docId : docIds) {
                JsonNode doc = client.getDocument(datasetId, docId);
                if (doc == null) continue;
                int run = doc.path("run").asInt(0);
                // run=3 means completed in RAGFlow
                if (run != 3) { allDone = false; break; }
            }
            if (allDone) return;
            Thread.sleep(interval);
        }
        log.warn("RAGFlow parse did not complete within {}s for dataset={}", config.getParsePollTimeoutSeconds(), datasetId);
    }

    private FilingChunk fromRagflowChunk(JsonNode c) {
        try {
            JsonNode meta = c.path("document").path("meta_fields");
            if (meta.isMissingNode() || meta.isNull()) meta = c.path("meta_fields");
            String content = c.path("content").asText(null);
            if (content == null) content = c.path("content_with_weight").asText(null);
            Double score = c.path("similarity").asDouble(Double.NaN);
            if (score.isNaN()) {
                score = c.path("vector_similarity").asDouble(0.0) * 0.7
                        + c.path("term_similarity").asDouble(0.0) * 0.3;
            }
            Integer fiscalYear = null;
            JsonNode fyNode = meta.path("fiscalYear");
            if (!fyNode.isMissingNode() && !fyNode.isNull()) {
                try { fiscalYear = fyNode.asInt(); } catch (Exception ignored) {}
            }
            Integer pageNumber = null;
            JsonNode pnNode = meta.path("pageNumber");
            if (!pnNode.isMissingNode() && !pnNode.isNull()) {
                try { pageNumber = pnNode.asInt(); } catch (Exception ignored) {}
            }
            // Strip the "Section: ...\n" prefix we added on upload
            String cleanedContent = content;
            if (content != null && content.startsWith("Section: ")) {
                int nl = content.indexOf('\n');
                if (nl > 0) cleanedContent = content.substring(nl + 1);
            }
            return FilingChunk.builder()
                    .chunkId(meta.path("chunkId").asText(null))
                    .ticker(meta.path("ticker").asText(null))
                    .documentId(meta.path("documentId").asText(null))
                    .formType(meta.path("formType").asText(null))
                    .fiscalPeriod(meta.path("fiscalPeriod").asText(null))
                    .filingDate(meta.path("filingDate").asText(null))
                    .sourceFileName(meta.path("sourceFileName").asText(null))
                    .sectionTitle(meta.path("sectionTitle").asText(null))
                    .fiscalYear(fiscalYear)
                    .pageNumber(pageNumber)
                    .content(cleanedContent)
                    .score(score)
                    .metadata(new HashMap<>())
                    .build();
        } catch (Exception e) {
            log.debug("Failed to parse RAGFlow chunk: {}", e.getMessage());
            return null;
        }
    }

    private String safeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
