package io.invest.iagent.service.kb.vector;

import io.invest.iagent.service.kb.model.KnowledgeBaseSearchFilter;
import io.invest.iagent.service.kb.category.FilingContentCategory;
import io.invest.iagent.service.kb.model.KnowledgeBaseChunkDTO;
import org.apache.commons.lang3.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class VectorStoreServiceInMemory implements VectorStoreService {

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    @Override
    public synchronized void upsert(List<KnowledgeBaseChunkDTO> chunks, List<List<Float>> embeddings) {
        for(int i=0;i<chunks.size();i++){
            Entry existing = entries.get(chunks.get(i).getChunkId());
            entries.put(chunks.get(i).getChunkId(), new Entry(chunks.get(i), embeddings.get(i), existing == null ? null : existing.summaryEmbedding));
        }
    }

    @Override
    public synchronized void upsertSummaryCandidates(List<KnowledgeBaseChunkDTO> chunks, List<List<Float>> summaryEmbeddings) {
        for(int i=0;i<chunks.size();i++){
            KnowledgeBaseChunkDTO chunk = chunks.get(i);
            Entry existing = entries.get(chunk.getChunkId());
            List<Float> detailEmbedding = existing == null ? List.of() : existing.detailEmbedding;
            entries.put(chunk.getChunkId(), new Entry(existing == null ? chunk : existing.chunk, detailEmbedding, summaryEmbeddings.get(i)));
        }
    }

    @Override
    public synchronized List<KnowledgeBaseChunkDTO> search(String query, List<Float> embedding, KnowledgeBaseSearchFilter filter) {
        return searchByEmbedding(embedding, filter, false);
    }

    @Override
    public synchronized List<KnowledgeBaseChunkDTO> searchSummaryCandidates(String query, List<Float> embedding, KnowledgeBaseSearchFilter filter) {
        return searchByEmbedding(embedding, filter, true);
    }

    private List<KnowledgeBaseChunkDTO> searchByEmbedding(List<Float> embedding, KnowledgeBaseSearchFilter filter, boolean summary) {
        int topK = filter == null ? 5 : filter.getTopK();
        return entries.values().stream()
                .filter(e -> !summary || e.summaryEmbedding != null)
                .filter(e -> matchesFilter(e.chunk, filter))
                .map(e -> withScore(e.chunk, cosine(embedding, summary ? e.summaryEmbedding : e.detailEmbedding)))
                .sorted(Comparator.comparing(KnowledgeBaseChunkDTO::getScore, Comparator.nullsLast(Double::compareTo)).reversed())
                .limit(Math.max(topK, 1))
                .toList();
    }

    @Override
    public synchronized int delete(String ticker, String documentId) {
        List<String> keys = entries.entrySet().stream()
                .filter(e -> StringUtils.isBlank(ticker) || StringUtils.equalsIgnoreCase(ticker, e.getValue().chunk.getTicker()))
                .filter(e -> StringUtils.isBlank(documentId) || StringUtils.equals(documentId, e.getValue().chunk.getDocumentId()))
                .map(Map.Entry::getKey)
                .toList();
        keys.forEach(entries::remove);
        return keys.size();
    }

    @Override
    public synchronized List<KnowledgeBaseChunkDTO> list(String ticker) {
        return entries.values().stream()
                .map(e -> e.chunk)
                .filter(c -> StringUtils.isBlank(ticker) || StringUtils.equalsIgnoreCase(ticker, c.getTicker()))
                .toList();
    }

    private KnowledgeBaseChunkDTO withScore(KnowledgeBaseChunkDTO chunk, double score){
        return KnowledgeBaseChunkDTO.builder()
                .chunkId(chunk.getChunkId())
                .score(score)
                .text(chunk.getText())
                .ticker(chunk.getTicker())
                .documentId(chunk.getDocumentId())
                .formType(chunk.getFormType())
                .fiscalYear(chunk.getFiscalYear())
                .fiscalPeriod(chunk.getFiscalPeriod())
                .filingDate(chunk.getFilingDate())
                .sourceFileName(chunk.getSourceFileName())
                .sectionTitle(chunk.getSectionTitle())
                .chunkType(chunk.getChunkType())
                .category(chunk.getCategory())
                .citation(chunk.getCitation())
                .metadata(chunk.getMetadata())
                .build();
    }

    private boolean matchesFilter(KnowledgeBaseChunkDTO chunk, KnowledgeBaseSearchFilter filter) {
        return filter == null
                || (StringUtils.isBlank(filter.getTicker()) || StringUtils.equalsIgnoreCase(filter.getTicker(), chunk.getTicker()))
                && (StringUtils.isBlank(filter.getFormType()) || StringUtils.equalsIgnoreCase(filter.getFormType(), chunk.getFormType()))
                && (StringUtils.isBlank(filter.getFiscalYear()) || StringUtils.equals(filter.getFiscalYear(), String.valueOf(chunk.getFiscalYear())))
                && (StringUtils.isBlank(filter.getCategory()) || StringUtils.equals(FilingContentCategory.normalizeCode(filter.getCategory()), categoryOf(chunk)))
                && (filter.getChunkIds() == null || filter.getChunkIds().isEmpty() || filter.getChunkIds().contains(chunk.getChunkId()));
    }

    private String categoryOf(KnowledgeBaseChunkDTO chunk) {
        if (StringUtils.isNotBlank(chunk.getCategory())) {
            return StringUtils.defaultIfBlank(FilingContentCategory.normalizeCode(chunk.getCategory()), FilingContentCategory.OTHER.code());
        }
        Object category = chunk.getMetadata() == null ? null : chunk.getMetadata().get("content_category");
        return StringUtils.defaultIfBlank(FilingContentCategory.normalizeCode(category == null ? null : String.valueOf(category)), FilingContentCategory.OTHER.code());
    }

    private double cosine(List<Float> left, List<Float> right){
        if(Objects.isNull(left) || Objects.isNull(right) || left.size() != right.size()){
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for(int i=0;i<left.size();i++){
            dot += left.get(i) * right.get(i);
            leftNorm += left.get(i) * left.get(i);
            rightNorm += right.get(i) * right.get(i);
        }
        if(leftNorm == 0 || rightNorm == 0){
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private record Entry(KnowledgeBaseChunkDTO chunk, List<Float> detailEmbedding, List<Float> summaryEmbedding) {}
}
