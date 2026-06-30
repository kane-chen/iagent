package io.invest.iagent.service.filing.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class DownloadedFiling {
    private String documentId;
    private String internalDocumentId;
    private String formType;
    private int fiscalYear;
    private String reportDate;
    private String filingDate;
    private String sourceFingerprint;
    private boolean hasXbrl;
    private List<DownloadedFile> files;
}
