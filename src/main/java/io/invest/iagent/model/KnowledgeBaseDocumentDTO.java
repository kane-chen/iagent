package io.invest.iagent.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class KnowledgeBaseDocumentDTO {
    private String ticker;
    private String documentId;
    private String formType;
    private Integer fiscalYear;
    private String fiscalPeriod;
    private String filingDate;
    private String sourceFingerprint;
    private Integer chunkCount;
    private String status;
}
