package io.invest.iagent.rag.repository;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkRetrieveResult {
    private String id;
    private String content;
    private String sourceId;
    private String chunkId;
    private String knowledgeId;
    private String knowledgeBaseId;
    private String chunkType;
    private String contextHeader;
    private String parentChunkId;
    private double score;
    private String matchType;
}
