package io.invest.iagent.rag.repository;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ChunkDO {
    private Long id;
    private String sourceId;
    private Integer sourceType;
    private String chunkId;
    private String knowledgeId;
    private String knowledgeBaseId;
    private String chunkType;
    private String content;
    private String contextHeader;
    private Integer dimension;
    private float[] embedding;
    private String parentChunkId;
    private Boolean isEnabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
