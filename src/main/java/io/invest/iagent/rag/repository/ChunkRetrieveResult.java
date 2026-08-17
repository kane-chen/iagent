package io.invest.iagent.rag.repository;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

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
    /** chunk 标签（KV），检索时由仓储回填 */
    private Map<String, String> tags;
}
