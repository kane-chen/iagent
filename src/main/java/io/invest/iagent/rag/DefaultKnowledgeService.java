package io.invest.iagent.rag;

import io.invest.iagent.rag.config.RagProperties;
import io.invest.iagent.rag.model.*;
import io.invest.iagent.rag.service.ChunkingService;
import io.invest.iagent.rag.service.ChunkRepositoryService;
import io.invest.iagent.rag.service.EmbeddingService;
import io.invest.iagent.rag.service.RetrievingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 服务默认实现：编排文档摄入和检索流程
 */
@Slf4j
@Service
public class DefaultKnowledgeService implements KnowledgeService {

    @Autowired
    private ChunkingService chunkingService;
    @Autowired
    private EmbeddingService embeddingService;
    @Autowired
    private ChunkRepositoryService chunkRepositoryService;
    @Autowired
    private RetrievingService retrievingService;
    @Autowired
    private RagProperties ragProperties;


    @Override
    public int save(Document doc, ChunkingConfig chunkingConfig) {
        log.info("Saving document: knowledgeBaseId={}, filePath={}", doc.getKnowledgeBaseId(), doc.getFilePath());
        if (chunkingConfig != null) {
            doc.setChunkingConfig(chunkingConfig);
        }
        List<Chunk> chunks = chunkingService.chunk(doc);
        log.info("Chunked into {} pieces", chunks.size());
        embeddingService.embedding(chunks);
        chunkRepositoryService.save(chunks);
        log.info("Document saved successfully");
        return chunks.size();
    }

    @Override
    public void deleteByKnowledgeId(String knowledgeBaseId, String knowledgeId) {
        chunkRepositoryService.deleteByKnowledgeId(knowledgeBaseId, knowledgeId);
    }

    @Override
    public List<RetrieveResultItem> retrieve(RetrieveRequest request) {
        return retrievingService.retrieve(request) ;
    }
}
