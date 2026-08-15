package io.invest.iagent.rag.service;

import io.invest.iagent.rag.model.Chunk;
import io.invest.iagent.rag.repository.ChunkDO;
import io.invest.iagent.rag.repository.ChunkRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Chunk 仓储服务：领域模型与数据对象转换
 */
@Slf4j
public class ChunkRepositoryService {

    private final ChunkRepository chunkRepository;

    public ChunkRepositoryService(ChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    public void save(List<Chunk> chunks) {
        List<ChunkDO> records = chunks.stream().map(this::toDO).toList();
        chunkRepository.batchSave(records);
        log.debug("Saved {} chunks to repository", records.size());
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
                .build();
    }
}
