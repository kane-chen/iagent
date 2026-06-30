package io.invest.iagent.model;

import lombok.Builder;
import lombok.Data;


@Data
@Builder
public class FinancialIndexValueDTO {

    private String ticker;
    /**
     * 表单类型 (10-K, 10-Q...)
     */
    private String tableType ;
    private String index;
    private String value;

    /**
     *  "currency": "USD","CNY"
     */
    private String currency;

    /**
     *  "units": "US$ in millions",
     */
    private String units;

    /**
     * 可为空，可用于快速定位的 frame 标识 CY2021Q2I
     */
    private String period ;
    /**
     * 申报日期 2022-03-11
     */
    private String date;

    /**
     * 财年
     */
    private Integer fiscalYear ;
    /**
     * 财期 (FY, Q1, Q2...)
     */
    private String fiscalPeriod;

    // 可为空，2020-01-01
    private String startDate;
    // 可为空，2020-09-30
    private String endDate;

    private String source;

}
