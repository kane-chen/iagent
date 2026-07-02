package io.invest.iagent.service.extraction.extractor;

import io.invest.iagent.service.extraction.model.CompanyConfig;
import io.invest.iagent.service.extraction.model.FinancialTable;
import io.invest.iagent.service.extraction.model.Segment;

import java.util.Map;

/**
 * HTML 分部表处理策略。
 *
 * <p>参照 PDF 侧的 {@code PdfLayoutHandler}：不同公司的 HTML 财报格式差异（分部标题 vs
 * "分部块"排布 vs 财报正文散列）由各自 handler 处理；上层 {@link HtmlReportOrchestrator}
 * 按策略优先级依次尝试，命中的 handler 独占该表、写入 sink，剩下的 handler 不再消费同一张表。</p>
 *
 * <p>共用能力（数字解析、单位归一、metric 去重写入）都在 {@link HtmlExtractionSupport} 里。</p>
 */
public interface HtmlLayoutHandler {

    /**
     * 优先级：数字越小越先被尝试。特化 handler 应 < 500；泛用兜底应 ≥ 500。
     * 相同优先级按注册顺序稳定排序。
     */
    int priority();

    /**
     * 判断 handler 是否愿意处理这张表 + config。可根据 {@code companyConfig} 里的
     * layout hint 字段（如 {@link CompanyConfig#getHtmlLayout()}）或表格标题/结构自识别。
     */
    boolean supports(FinancialTable table, CompanyConfig companyConfig);

    /**
     * 尝试从表格里抽取分部数据，写入 sink（按 segmentCode 索引）。
     *
     * @return 本次写入的 metric 条数。返回 0 表示"不吃这张表"，orchestrator 让下一个
     *         handler 继续尝试；> 0 则该表被独占消费。
     */
    int apply(FinancialTable table, CompanyConfig companyConfig, Map<String, Segment> sink);
}
