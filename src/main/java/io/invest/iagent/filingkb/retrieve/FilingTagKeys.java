package io.invest.iagent.filingkb.retrieve;

/**
 * filingkb 标签键常量。
 * <p>标签值随 chunk 写入 chunk_tags，检索时可用 {@link io.invest.iagent.rag.model.TagFilter} 过滤。
 */
public final class FilingTagKeys {

    private FilingTagKeys() {}

    /** 股票代码，大写（如 00700、AAPL） */
    public static final String TICKER = "ticker";
    /** 财报周期规范化值：YYYYQn / FYyyyy / YYYYHn（如 2026Q1、FY2025、2025H1） */
    public static final String FISCAL_PERIOD = "fiscal_period";
    /** 财年（4 位年份字符串） */
    public static final String FISCAL_YEAR = "fiscal_year";
    /** 表单类型（10-K、10-Q、20-F、年报、季报等） */
    public static final String FORM_TYPE = "form_type";
    /** 文档 id（workspace/portfolio/<ticker>/filings/<documentId>） */
    public static final String DOCUMENT_ID = "document_id";
    /** 源文件名（PDF/HTML） */
    public static final String SOURCE_FILE = "source_file";
    /** 段落标题面包屑（由通用 HeadingAwareChunker 自动挂接） */
    public static final String HEADING = "heading";

    /** filingkb 业务域标识，写入 PipelineRequest.domain 用于 handler 守卫 */
    public static final String DOMAIN = "filing";
}
