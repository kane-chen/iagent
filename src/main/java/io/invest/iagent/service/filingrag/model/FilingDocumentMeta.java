package io.invest.iagent.service.filingrag.model;

import lombok.Builder;
import lombok.Data;

/**
 * Metadata about a filing document, derived from meta.json in workspace/portfolio/&lt;TICKER&gt;/filings/&lt;docId&gt;/.
 */
@Data
@Builder
public class FilingDocumentMeta {
    private String ticker;
    private String documentId;
    private String formType;
    private Integer fiscalYear;
    private String fiscalPeriod;
    private String filingDate;
}
