package io.invest.iagent.rag.retrieve.plugins;

import io.invest.iagent.rag.config.RagConfig;
import io.invest.iagent.rag.repository.ChunkRepository;
import io.invest.iagent.rag.repository.ChunkRetrieveParams;
import io.invest.iagent.rag.repository.ChunkRetrieveResult;
import io.invest.iagent.rag.retrieve.dto.ChatManage;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.dto.PluginErrorOrNone;
import io.invest.iagent.rag.retrieve.dto.PluginException;
import io.invest.iagent.rag.retrieve.enums.EventType;
import io.invest.iagent.rag.retrieve.model.SearchResult;
import io.invest.iagent.rag.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * CHUNK_SEARCH_PARALLEL：并行执行 BM25 + 向量混合检索
 */
@Slf4j
public class SearchParallelPlugin implements Plugin {

    private final EmbeddingService embeddingService;
    private final ChunkRepository chunkRepository;
    private final ExecutorService executor;
    private final RagConfig config;

    public SearchParallelPlugin(EmbeddingService embeddingService, ChunkRepository chunkRepository,
                                 ExecutorService executor, RagConfig config) {
        this.embeddingService = embeddingService;
        this.chunkRepository = chunkRepository;
        this.executor = executor;
        this.config = config;
    }

    @Override
    public List<EventType> activationEvents() {
        return Collections.singletonList(EventType.CHUNK_SEARCH_PARALLEL);
    }

    @Override
    public PluginErrorOrNone onEvent(PipelineContext ctx, EventType eventType, ChatManage cm,
                                     Supplier<PluginErrorOrNone> next) throws PluginException {
        if (!cm.needsRetrieval()) return next.get();

        String query = cm.getState().getRewriteQuery();
        if (query == null || query.isBlank()) {
            query = cm.getQuery();
        }

        RagConfig.Search searchConfig = config.getSearch();
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
                    () -> chunkRepository.keywordSearch(params), executor);
            CompletableFuture<List<ChunkRetrieveResult>> vectorFuture = CompletableFuture.supplyAsync(
                    () -> chunkRepository.vectorSearch(params), executor);

            CompletableFuture.allOf(keywordFuture, vectorFuture)
                    .get(30, TimeUnit.SECONDS);

            List<ChunkRetrieveResult> keywordResults = keywordFuture.get();
            List<ChunkRetrieveResult> vectorResults = vectorFuture.get();

            List<ChunkRetrieveResult> fused = chunkRepository.rrfFuse(keywordResults, vectorResults, params);

            List<SearchResult> results = fused.stream()
                    .map(this::toSearchResult)
                    .collect(Collectors.toList());

            cm.getState().setSearchResult(results);

            if (results.isEmpty()) {
                throw PluginException.ERR_SEARCH_NOTHING;
            }
            log.debug("Search returned {} results (keyword={}, vector={})",
                    results.size(), keywordResults.size(), vectorResults.size());
        } catch (PluginException e) {
            throw e;
        } catch (Exception e) {
            log.error("Search failed: {}", e.getMessage(), e);
            throw new PluginException("search_failed", e.getMessage(), e);
        }

        return next.get();
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
