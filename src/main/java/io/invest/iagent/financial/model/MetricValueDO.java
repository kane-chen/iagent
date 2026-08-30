package io.invest.iagent.financial.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * fin_metric_value 一行：某公司某期间某指标（某来源）的值。
 */
@Data
@Builder
public class MetricValueDO {

    private String ticker;
    /** 规范化期间：2026Q2 / FY2025 */
    private String fiscalPeriod;
    /** SINGLE_Q / CUMULATIVE / FY */
    private String periodType;
    /** 标准指标编码 */
    private String metricCode;
    /** 统一百万单位财报币种；null 表示不披露 */
    private BigDecimal value;
    private BigDecimal yoy;
    private BigDecimal qoq;
    private String currency;
    @Builder.Default
    private String unit = "million";
    /** FUTU_API / RAG / DERIVED */
    private String source;
    /** RAG 提取置信度 0-100 */
    private Integer confidence;
    /** 溯源：财报目录 documentId */
    private String documentId;
    /** 溯源：RAG chunkId */
    private String chunkId;
}
