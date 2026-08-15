package io.invest.iagent.rag;

import io.invest.iagent.rag.config.RagConfig;
import io.invest.iagent.rag.model.*;
import io.invest.iagent.rag.retrieve.dto.*;
import io.invest.iagent.rag.retrieve.executor.RagPipelineExecutor;
import io.invest.iagent.rag.retrieve.model.EventBus;
import io.invest.iagent.rag.retrieve.model.SearchResult;
import io.invest.iagent.rag.service.ChunkingService;
import io.invest.iagent.rag.service.ChunkRepositoryService;
import io.invest.iagent.rag.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;

/**
 * RAG 服务默认实现：编排文档摄入和检索流程
 */
@Slf4j
public class DefaultRagService implements RagService {

    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final ChunkRepositoryService chunkRepositoryService;
    private final RagPipelineExecutor ragPipelineExecutor;
    private final RagConfig ragConfig;

    public DefaultRagService(ChunkingService chunkingService,
                              EmbeddingService embeddingService,
                              ChunkRepositoryService chunkRepositoryService,
                              RagPipelineExecutor ragPipelineExecutor,
                              RagConfig ragConfig) {
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.chunkRepositoryService = chunkRepositoryService;
        this.ragPipelineExecutor = ragPipelineExecutor;
        this.ragConfig = ragConfig;
    }

    @Override
    public void save(Document doc, ChunkingConfig chunkingConfig) {
        log.info("Saving document: knowledgeBaseId={}, filePath={}", doc.getKnowledgeBaseId(), doc.getFilePath());
        if (chunkingConfig != null) {
            doc.setChunkingConfig(chunkingConfig);
        }
        List<Chunk> chunks = chunkingService.chunk(doc);
        log.info("Chunked into {} pieces", chunks.size());
        embeddingService.embedding(chunks);
        chunkRepositoryService.save(chunks);
        log.info("Document saved successfully");
    }

    @Override
    public List<RetrieveResultItem> retrieve(RetrieveRequest request) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        try {
            PipelineRequest pipelineRequest = PipelineRequest.from(request, ragConfig);
            PipelineState state = new PipelineState();
            PipelineContext context = new PipelineContext(new EventBus(), null, null, traceId);
            ChatManage chatManage = new ChatManage(pipelineRequest, state, context);

            ragPipelineExecutor.execute(context, chatManage);

            List<SearchResult> results = !state.mergeResult.isEmpty()
                    ? state.mergeResult : state.searchResult;
            return results.stream()
                    .map(SearchResult::toRetrieveResultItem)
                    .toList();
        } catch (Exception e) {
            log.error("Retrieve failed: {}", e.getMessage(), e);
            throw new RuntimeException("RAG retrieve failed", e);
        }
    }
}
