package io.invest.iagent.service.kb.vector;

import io.invest.iagent.service.kb.model.KnowledgeBaseSearchFilter;
import io.invest.iagent.service.kb.model.KnowledgeBaseChunkDTO;

import java.util.List;

public interface VectorStoreService {
    void upsert(List<KnowledgeBaseChunkDTO> chunks, List<List<Float>> embeddings);

    default List<KnowledgeBaseChunkDTO> search(String query, List<Float> embedding, String ticker, String fiscalYear, String formType, int topK) {
        return search(query, embedding, KnowledgeBaseSearchFilter.builder()
                .ticker(ticker)
                .fiscalYear(fiscalYear)
                .formType(formType)
                .topK(topK)
                .build());
    }

    List<KnowledgeBaseChunkDTO> search(String query, List<Float> embedding, KnowledgeBaseSearchFilter filter);

    default void upsertSummaryCandidates(List<KnowledgeBaseChunkDTO> chunks, List<List<Float>> summaryEmbeddings) {
    }

    default List<KnowledgeBaseChunkDTO> searchSummaryCandidates(String query, List<Float> embedding, KnowledgeBaseSearchFilter filter) {
        return List.of();
    }

    int delete(String ticker, String documentId);
    List<KnowledgeBaseChunkDTO> list(String ticker);
}
