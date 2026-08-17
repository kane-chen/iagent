package io.invest.iagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

// 任务 payload（携带恢复上下文）
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Document {
    private String knowledgeId;
    private String tenantId;
    private String knowledgeBaseId;
    private String filePath;
    private String url;
    private String fileUrl;
    private ChunkingConfig chunkingConfig;
    private boolean enableMultimodal;
    private boolean enableQuestionGeneration;
    private int questionCount;
    private String language;
    /** 文档级标签（KV），应用于该文档切分出的所有 chunk；可被 chunker 的 per-chunk 标签覆盖 */
    private Map<String, String> tags;
}