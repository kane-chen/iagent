package io.invest.iagent.rag.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
public class Chunk {
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
    /** chunk 标签（KV），由文档级标签与 chunker 的 per-chunk 标签合并而来 */
    private Map<String, String> tags;
}
