package io.invest.iagent.service.filingrag.model;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder(toBuilder = true)
public class FilingChunk {
    private String chunkId;
    private String ticker;
    private String documentId;
    private String formType;
    private String fiscalPeriod;
    private String filingDate;
    private String sourceFileName;
    private String sectionTitle;
    private String content;
    private Integer fiscalYear;
    private Integer pageNumber;
    private Double score;
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
