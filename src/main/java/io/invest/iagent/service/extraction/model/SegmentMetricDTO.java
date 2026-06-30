package io.invest.iagent.service.extraction.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SegmentMetricDTO {

    private String segmentCode;       // 分部编码，如 INTERNATIONAL_COMMERCE
    private String segmentName;       // 分部名称，如 国际商业
    private Integer level;            // 层级（1,2,3...）
    private String parentSegmentCode; // 上级分部编码（用于分组，level 1 的此字段为空或等于segmentCode）
    private String metricCode;        // 指标编码，如 REVENUE, ADJUSTED_EBITA
    private String metricName;        // 指标名称，如 收入
    private Double value;             // 指标数值
    private Double yoyGrowth;         // 同比增长率(%)
    private Integer confidenceScore;
    private String sourceType; // TABLE_EXTRACT, TEXT_EXTRACT, FORMULA_CALC
    private String sourceLocation;
    private String currency;
    private String unit;
    private String period;            // 财报周期，如 2024Q1

}
