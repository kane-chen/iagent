package io.invest.iagent.service.kb.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class KnowledgeBaseChunkDTO {
    private String chunkId;
    private Double score;
    private String text;
    private String ticker;
    private String documentId;
    private String formType;
    private Integer fiscalYear;
    private String fiscalPeriod;
    private String filingDate;
    private String sourceFileName;
    private String sectionTitle;
    private String chunkType;
    private String category;
    private String citation;
    private Map<String, Object> metadata;
}
