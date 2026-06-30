package io.invest.iagent.service.extraction.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.ToString;

/**
 * 分部财务指标值
 */
@Data
public class SegmentMetric {

    private String metricCode;
    private String metricName;
    private Double value;
    private Double yoyGrowth;
    private Integer confidenceScore;
    private String sourceType; // TABLE_EXTRACT, TEXT_EXTRACT, FORMULA_CALC
    private String sourceLocation;
    private String currency;
    private String unit;
    private String period; // 财报周期，如：2025Q1, 2024Q1

    @ToString.Exclude
    @JsonIgnore
    private Segment segment;

    public SegmentMetric() {
        this.confidenceScore = 80; // 默认置信度
    }

    public SegmentMetric(String metricCode, String metricName, Double value) {
        this();
        this.metricCode = metricCode;
        this.metricName = metricName;
        this.value = value;
    }
}
