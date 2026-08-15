package io.invest.iagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}