package io.invest.iagent.service.extraction.extractor;

import io.invest.iagent.service.extraction.model.CompanyConfig;
import io.invest.iagent.service.extraction.model.FinancialTable;
import io.invest.iagent.service.extraction.model.Segment;
import io.invest.iagent.service.extraction.recognizer.SegmentRecognizer;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTML 抽取的分派入口。参照 {@code PdfReportParser.parseSegments} 的
 * "table × handler" 双层循环 —— 每张表按 handler 优先级依次尝试，
 * 命中的 handler 独占该表（返回 &gt; 0 hits），其余 handler 不再看它。
 *
 * <p>兜底 handler ({@link GenericHtmlLayoutHandler}) 永远最后一位；只要它注册在案，
 * 现有 BABA/PDD/MSFT/GOOG 等按行列抽取的公司行为完全不变。特化 handler 走前面的槽位，
 * 各家的差异被隔离到独立类里。</p>
 */
@Slf4j
public final class HtmlReportOrchestrator {

    private final List<HtmlLayoutHandler> handlers;

    public HtmlReportOrchestrator(List<HtmlLayoutHandler> handlers) {
        List<HtmlLayoutHandler> sorted = new ArrayList<>(handlers);
        sorted.sort(Comparator.comparingInt(HtmlLayoutHandler::priority));
        this.handlers = sorted;
    }

    /**
     * 便捷构造：把 support/recognizer/dataExtractor 灌给标准 handler 组合。
     * 目前的组合：<b>SegmentContributionHandler → GenericHtmlLayoutHandler</b>。
     */
    public static HtmlReportOrchestrator standard(HtmlExtractionSupport support,
                                                  SegmentRecognizer recognizer,
                                                  DataExtractor dataExtractor) {
        return new HtmlReportOrchestrator(List.of(
                new SegmentContributionHandler(support, recognizer),
                new GenericHtmlLayoutHandler(dataExtractor)));
    }

    /**
     * 遍历多张表 + 多个 handler 生成 Segment 列表。
     * 保持返回顺序稳定（按 segmentCode 首次出现顺序，用 LinkedHashMap 保证）。
     */
    public List<Segment> extractFromTables(List<FinancialTable> tables,
                                            CompanyConfig cfg) {
        Map<String, Segment> sink = new LinkedHashMap<>();
        if (tables == null || tables.isEmpty()) {
            return new ArrayList<>();
        }
        for (FinancialTable table : tables) {
            for (HtmlLayoutHandler handler : handlers) {
                if (!handler.supports(table, cfg)) continue;
                int hits = handler.apply(table, cfg, sink);
                if (hits > 0) {
                    log.debug("Handler {} consumed table {} → +{} metrics",
                            handler.getClass().getSimpleName(),
                            table.getTableId(), hits);
                    break;
                }
            }
        }
        return new ArrayList<>(sink.values());
    }
}
