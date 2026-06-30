package io.invest.iagent.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class FinancialTrendAnalysisResult {
    private boolean success;
    private String ticker;
    private List<String> metrics;
    private Integer quarterCount;
    private List<FinancialTrendRowDTO> rows;
    private List<String> warnings;
    private String error;
    private Map<String, Object> metadata;
}
