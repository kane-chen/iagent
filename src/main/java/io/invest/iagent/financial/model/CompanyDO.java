package io.invest.iagent.financial.model;

import lombok.Builder;
import lombok.Data;

/**
 * fin_company 一行：公司元数据。
 */
@Data
@Builder
public class CompanyDO {

    private String ticker;
    /** HK / US / CN */
    private String market;
    private String name;
    /** HKD / USD / RMB */
    private String currency;
    /** 财年结束月份 1-12 */
    private int fyEndMonth;
}
