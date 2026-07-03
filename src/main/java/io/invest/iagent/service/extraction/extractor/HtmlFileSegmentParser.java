package io.invest.iagent.service.extraction.extractor;

import io.invest.iagent.service.extraction.model.CompanyConfig;
import io.invest.iagent.service.extraction.model.FinancialTable;
import io.invest.iagent.service.extraction.model.Segment;
import io.invest.iagent.service.extraction.parser.FileSegmentParser;
import io.invest.iagent.service.extraction.parser.HtmlReportParser;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * HTML 财报解析器：组合 {@link HtmlReportParser}（Jsoup → {@link FinancialTable}）
 * 和 {@link HtmlReportOrchestrator}（策略分派 → {@link Segment}）。
 */
@Slf4j
public class HtmlFileSegmentParser implements FileSegmentParser {

    private final HtmlReportParser htmlParser = new HtmlReportParser();
    private volatile HtmlReportOrchestrator orchestrator;

    public HtmlFileSegmentParser() {
    }

    /**
     * 配置发生变化时（公司切换 / CompanyConfig 更新）由上层调用，
     * 注入当前 config 对应的 orchestrator。
     */
    public void setOrchestrator(HtmlReportOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public List<Segment> parse(File file, CompanyConfig config) throws IOException {
        List<FinancialTable> tables = htmlParser.parse(file);
        log.info("Parsed HTML file {} into {} financial tables", file.getName(), tables.size());
        HtmlReportOrchestrator orch = this.orchestrator;
        List<Segment> segments = orch.extractFromTables(tables, config);
        log.info("Extracted {} segments with financial data from {}", segments.size(), file.getName());
        return segments;
    }

    /**
     * 从 HTML 内容字符串解析（HTML 特有入口，方便 {@code extractFromHtmlContent} 复用）。
     */
    public List<Segment> parseHtml(String htmlContent, CompanyConfig config) {
        List<FinancialTable> tables = htmlParser.parseHtml(htmlContent);
        return orchestrator.extractFromTables(tables, config);
    }

    @Override
    public boolean supports(File file) {
        if (file == null) return false;
        String name = file.getName().toLowerCase();
        return name.endsWith(".html") || name.endsWith(".htm");
    }
}
