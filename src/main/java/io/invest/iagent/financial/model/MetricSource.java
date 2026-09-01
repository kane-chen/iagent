package io.invest.iagent.financial.model;

/**
 * 指标数据来源。同一指标允许不同来源共存（如 FUTU_API 与 RAG），便于交叉核对。
 */
public enum MetricSource {
    /** futu OpenAPI 三大表结构化数据 */
    FUTU_API,
    /** 从财报文件经 RAG + LLM 结构化提取 */
    RAG,
    /** 人工补充 */
    MANU,
    /** 由其他指标计算派生（如 FCF = OCF - CapEx） */
    DERIVED,
    /** 分部数据确定性表格解析（segment-financial-report skill） */
    SEGMENT_PARSE
}
