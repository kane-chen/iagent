package io.invest.iagent.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class FinancialTrendRowDTO {
    private String ticker;
    private String metric;
    private Integer fiscalYear;
    private String fiscalPeriod;
    private String tableType;
    private BigDecimal value;
    private String currency;
    private String units;
    private String startDate;
    private String endDate;
    private String source;
    private BigDecimal yoyBaseValue;
    private BigDecimal yoyChange;
    private BigDecimal yoyChangePercent;
    private String yoySource;
    private String missingReason;
}
