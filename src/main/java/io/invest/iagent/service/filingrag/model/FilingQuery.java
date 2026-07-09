package io.invest.iagent.service.filingrag.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class FilingQuery {
    private String question;
    private String ticker;
    private String fiscalPeriod;
    private String formType;
    private String keyword;
    private Integer fromFiscalYear;
    private Integer toFiscalYear;
    private Integer topK;
    private Double similarityThreshold;
    private LocalDate fromDate;
    private LocalDate toDate;
}
