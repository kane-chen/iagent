package io.invest.iagent.model;

import io.invest.iagent.service.kb.model.KnowledgeBaseChunkDTO;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class KnowledgeBaseRetrieveResult {
    private String query;
    private String ticker;
    private Integer topK;
    private String category;
    private String inferredCategory;
    private List<KnowledgeBaseChunkDTO> results;
    private String message;
    private Map<String, Object> metadata;
}
