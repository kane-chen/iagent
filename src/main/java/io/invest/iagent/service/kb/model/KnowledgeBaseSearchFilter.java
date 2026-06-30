package io.invest.iagent.service.kb.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class KnowledgeBaseSearchFilter {
    String ticker;
    String fiscalYear;
    String formType;
    String category;
    List<String> chunkIds;
    int topK;
}
