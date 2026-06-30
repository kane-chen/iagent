package io.invest.iagent.service.kb.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class KnowledgeBaseOperationResult {
    private boolean success;
    private String operation;
    private String ticker;
    private String documentId;
    private String knowledgeBaseId;
    private Integer chunkCount;
    private String message;
    private List<String> documentIds;
    private List<String> errors;
    private Map<String, Object> metadata;
}
