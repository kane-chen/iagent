package io.invest.iagent.financial.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * fin_segment_value 一行：某公司某期间某分部某指标的值。
 */
@Data
@Builder
public class SegmentValueDO {

    private String ticker;
    private String fiscalPeriod;
    private String segmentCode;
    /** REVENUE / OPERATING_INCOME / ADJUSTED_EBITA ...（对齐 segment skill metric_dict） */
    private String metricCode;
    private BigDecimal value;
    private BigDecimal yoy;
    private String currency;
    @Builder.Default
    private String unit = "million";
    @Builder.Default
    private String source = "SEGMENT_PARSE";
    private Integer confidence;
    private String documentId;
}
