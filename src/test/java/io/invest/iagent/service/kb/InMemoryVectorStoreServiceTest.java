package io.invest.iagent.service.kb;

import io.invest.iagent.service.kb.model.KnowledgeBaseSearchFilter;
import io.invest.iagent.service.kb.vector.VectorStoreServiceInMemory;
import io.invest.iagent.service.kb.model.KnowledgeBaseChunkDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryVectorStoreServiceTest {

    @Test
    void filtersByNormalizedCategory() {
        VectorStoreServiceInMemory store = new VectorStoreServiceInMemory();
        KnowledgeBaseChunkDTO financialOperations = chunk("c1", "financial_operations", null);
        KnowledgeBaseChunkDTO operatingRisks = chunk("c2", "operating_risks", null);
        store.upsert(List.of(financialOperations, operatingRisks), List.of(List.of(1.0f, 0.0f), List.of(0.0f, 1.0f)));

        List<KnowledgeBaseChunkDTO> results = store.search("profit expense", List.of(1.0f, 0.0f), KnowledgeBaseSearchFilter.builder()
                .category("financial-operations")
                .topK(5)
                .build());

        assertEquals(1, results.size());
        assertEquals("c1", results.get(0).getChunkId());
    }

    @Test
    void summaryCandidateSearchUsesSummaryEmbedding() {
        VectorStoreServiceInMemory store = new VectorStoreServiceInMemory();
        KnowledgeBaseChunkDTO first = chunk("c1", "financial_operations", null);
        KnowledgeBaseChunkDTO second = chunk("c2", "financial_operations", null);
        store.upsert(List.of(first, second), List.of(List.of(0.0f, 1.0f), List.of(0.0f, 1.0f)));
        store.upsertSummaryCandidates(List.of(first, second), List.of(List.of(0.0f, 1.0f), List.of(1.0f, 0.0f)));

        List<KnowledgeBaseChunkDTO> results = store.searchSummaryCandidates("summary", List.of(1.0f, 0.0f), KnowledgeBaseSearchFilter.builder()
                .topK(2)
                .build());

        assertEquals(2, results.size());
        assertEquals("c2", results.get(0).getChunkId());
    }

    @Test
    void detailedSearchCanBeRestrictedToChunkIds() {
        VectorStoreServiceInMemory store = new VectorStoreServiceInMemory();
        KnowledgeBaseChunkDTO first = chunk("c1", "financial_operations", null);
        KnowledgeBaseChunkDTO second = chunk("c2", "financial_operations", null);
        store.upsert(List.of(first, second), List.of(List.of(1.0f, 0.0f), List.of(0.0f, 1.0f)));

        List<KnowledgeBaseChunkDTO> results = store.search("detail", List.of(1.0f, 0.0f), KnowledgeBaseSearchFilter.builder()
                .chunkIds(List.of("c2"))
                .topK(5)
                .build());

        assertEquals(1, results.size());
        assertEquals("c2", results.get(0).getChunkId());
    }

    @Test
    void filtersByMetadataCategoryWhenTopLevelCategoryMissing() {
        VectorStoreServiceInMemory store = new VectorStoreServiceInMemory();
        KnowledgeBaseChunkDTO chunk = chunk("c1", null, "business_operations");
        store.upsert(List.of(chunk), List.of(List.of(1.0f, 0.0f)));

        List<KnowledgeBaseChunkDTO> results = store.search("business", List.of(1.0f, 0.0f), KnowledgeBaseSearchFilter.builder()
                .category("BUSINESS_OPERATIONS")
                .topK(5)
                .build());

        assertEquals(1, results.size());
        assertEquals("c1", results.get(0).getChunkId());
    }

    private KnowledgeBaseChunkDTO chunk(String id, String category, String metadataCategory) {
        return KnowledgeBaseChunkDTO.builder()
                .chunkId(id)
                .text(id)
                .ticker("TESTX")
                .documentId("doc1")
                .formType("20-F")
                .fiscalYear(2025)
                .category(category)
                .metadata(metadataCategory == null ? Map.of() : Map.of("content_category", metadataCategory))
                .build();
    }
}
