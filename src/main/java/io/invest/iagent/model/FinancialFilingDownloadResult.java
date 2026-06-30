package io.invest.iagent.model;

import io.invest.iagent.service.filing.model.DownloadedFiling;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class FinancialFilingDownloadResult {
    private boolean success;
    private String ticker;
    private List<String> formTypes;
    private List<Integer> fiscalYears;
    private boolean allYears;
    private int totalCount;
    private int downloadedCount;
    private int skippedCount;
    private int errorCount;
    private List<DownloadedFiling> downloadedFilings;
    private List<String> skipped;
    private List<String> errors;
    private String message;
    private String error;
}
