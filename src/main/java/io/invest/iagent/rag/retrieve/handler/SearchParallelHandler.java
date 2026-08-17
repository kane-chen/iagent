package io.invest.iagent.rag.retrieve.handler;

import io.invest.iagent.rag.config.RagProperties;
import io.invest.iagent.rag.repository.ChunkRepository;
import io.invest.iagent.rag.repository.ChunkRetrieveParams;
import io.invest.iagent.rag.repository.ChunkRetrieveResult;
import io.invest.iagent.rag.retrieve.dto.ChatManage;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.enums.EventType;
import io.invest.iagent.rag.retrieve.dto.SearchResult;
import io.invest.iagent.rag.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * CHUNK_SEARCH_PARALLEL：并行执行 BM25 + 向量混合检索
 */
@Service
@Slf4j
public class SearchParallelHandler implements Handler {

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private ExecutorService ragExecutor;

    @Autowired
    private RagProperties config;

    @Override
    public List<EventType> activationEvents() {
        return Collections.singletonList(EventType.CHUNK_SEARCH_PARALLEL);
    }

    @Override
    public void onEvent(PipelineContext ctx, EventType eventType, ChatManage cm) {
        if (!cm.needsRetrieval()){
            return ;
        }

        String query = cm.getState().getRewriteQuery();
        if (query == null || query.isBlank()) {
            query = cm.getQuery();
        }

        RagProperties.Search searchConfig = config.getSearch();
        float[] queryEmbedding = null;
        try {
            queryEmbedding = embeddingService.embedding(query);
        } catch (Exception e) {
            log.warn("Embedding failed, will use keyword search only: {}", e.getMessage());
        }

        ChunkRetrieveParams params = ChunkRetrieveParams.builder()
                .query(query)
                .queryEmbedding(queryEmbedding)
                .knowledgeBaseIds(cm.getRequest().knowledgeBaseIds)
                .topK(searchConfig.getVectorTopK())
                .vectorThreshold(cm.getRequest().vectorThreshold > 0
                        ? cm.getRequest().vectorThreshold : searchConfig.getVectorThreshold())
                .keywordThreshold(cm.getRequest().keywordThreshold > 0
                        ? cm.getRequest().keywordThreshold : searchConfig.getKeywordThreshold())
                .rrfK(searchConfig.getRrfK())
                .rrfVectorWeight(searchConfig.getRrfVectorWeight())
                .rrfKeywordWeight(searchConfig.getRrfKeywordWeight())
                .build();

        try {
            // 并行执行关键词和向量检索
            CompletableFuture<List<ChunkRetrieveResult>> keywordFuture = CompletableFuture.supplyAsync(
                    () -> chunkRepository.keywordSearch(params), ragExecutor);
            CompletableFuture<List<ChunkRetrieveResult>> vectorFuture = CompletableFuture.supplyAsync(
                    () -> chunkRepository.vectorSearch(params), ragExecutor);

            CompletableFuture.allOf(keywordFuture, vectorFuture)
                    .get(30, TimeUnit.SECONDS);

            List<ChunkRetrieveResult> keywordResults = keywordFuture.get();
            List<ChunkRetrieveResult> vectorResults = vectorFuture.get();

            List<ChunkRetrieveResult> fused = chunkRepository.rrfFuse(keywordResults, vectorResults, params);

            List<SearchResult> results = fused.stream()
                    .map(this::toSearchResult)
                    .collect(Collectors.toList());

            cm.getState().setSearchResult(results);

            log.debug("Search returned {} results (keyword={}, vector={})",
                    results.size(), keywordResults.size(), vectorResults.size());
        } catch (Exception e) {
            log.error("Search failed: {}", e.getMessage(), e);
            throw new RuntimeException("search_failed", e);
        }

    }

    private SearchResult toSearchResult(ChunkRetrieveResult r) {
        SearchResult sr = new SearchResult();
        sr.id = r.getChunkId();
        sr.knowledgeId = r.getKnowledgeId();
        sr.knowledgeBaseId = r.getKnowledgeBaseId();
        sr.chunkType = "text";
        sr.score = r.getScore();
        sr.content = r.getContent();
        sr.metadata.put("match_type", r.getMatchType());
        sr.metadata.put("source_id", r.getSourceId());
        return sr;
    }
}
