package io.invest.iagent.rag.repository;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

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
    /** chunk 标签（KV），持久化时展开为 chunk_tags 多行 */
    private Map<String, String> tags;
}
