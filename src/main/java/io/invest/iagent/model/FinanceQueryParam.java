package io.invest.iagent.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FinanceQueryParam {

    private String ticker;
    private String formTypes ;
    private String reportTypes ;
    private String indexCodes ;
    private String fiscalYears ;
    private String fiscalPeriods ;
}
