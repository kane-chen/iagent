package io.invest.iagent.rag.service;

import io.invest.iagent.rag.model.Chunk;
import io.invest.iagent.rag.repository.ChunkDO;
import io.invest.iagent.rag.repository.ChunkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Chunk 仓储服务：领域模型与数据对象转换
 */
@Slf4j
@Service
public class ChunkRepositoryService {

    @Autowired
    private ChunkRepository chunkRepository;


    public void save(List<Chunk> chunks) {
        List<ChunkDO> records = chunks.stream().map(this::toDO).toList();
        chunkRepository.batchSave(records);
        log.debug("Saved {} chunks to repository", records.size());
    }

    /**
     * 按知识库 + 业务文档 id 删除所有 chunk（标签随 FK 级联）。
     * 用于幂等重建：先删后写。
     */
    public void deleteByKnowledgeId(String knowledgeBaseId, String knowledgeId) {
        chunkRepository.deleteByKnowledgeId(knowledgeBaseId, knowledgeId);
        log.debug("Deleted chunks: knowledgeBaseId={}, knowledgeId={}", knowledgeBaseId, knowledgeId);
    }

    private ChunkDO toDO(Chunk chunk) {
        return ChunkDO.builder()
                .id(chunk.getId())
                .sourceId(chunk.getSourceId())
                .sourceType(chunk.getSourceType())
                .chunkId(chunk.getChunkId())
                .knowledgeId(chunk.getKnowledgeId())
                .knowledgeBaseId(chunk.getKnowledgeBaseId())
                .chunkType(chunk.getChunkType() != null ? chunk.getChunkType() : "text")
                .content(chunk.getContent())
                .contextHeader(chunk.getContextHeader())
                .dimension(chunk.getDimension())
                .embedding(chunk.getEmbedding())
                .parentChunkId(chunk.getParentChunkId())
                .isEnabled(chunk.getIsEnabled() != null ? chunk.getIsEnabled() : Boolean.TRUE)
                .tags(chunk.getTags())
                .build();
    }
}
