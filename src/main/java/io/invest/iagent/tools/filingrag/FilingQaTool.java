package io.invest.iagent.tools.filingrag;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.invest.iagent.service.filingrag.FilingRagService;
import io.invest.iagent.service.filingrag.QueryParser;
import io.invest.iagent.service.filingrag.QueryParser.ParsedPeriod;
import io.invest.iagent.service.filingrag.model.FilingAnswer;
import io.invest.iagent.service.filingrag.model.FilingBuildReport;
import io.invest.iagent.service.filingrag.model.FilingChunk;
import io.invest.iagent.service.filingrag.model.FilingQuery;
import org.apache.commons.lang3.StringUtils;

/**
 * Agent tool exposing filing Q&amp;A and index-building operations over the filing RAG backend.
 * <p>
 * Two @Tool methods:
 * <ul>
 *   <li>{@code filing_qa} — ask a question about a company's filings (retrieve + LLM answer with citations).</li>
 *   <li>{@code filing_qa_build} — build/rebuild the RAG index for a ticker's filings.</li>
 * </ul>
 */
public class FilingQaTool {

    private final FilingRagService service;

    public FilingQaTool(FilingRagService service) {
        this.service = service;
    }

    @Tool(name = "filing_qa", description = "查询财报原文并基于原文回答问题。适用于财报中具体业务描述、管理层讨论、数字来源、下滑原因、业务构成等问题。"
            + "会检索相关片段并由LLM合成带引用编号的中文回答。如尚未构建索引，先用filing_qa_build构建。")
    public String answer(
            @ToolParam(name = "question", description = "要回答的问题，例如'阿里巴巴国内零售业务2025Q1 EBITA下滑原因'") String question,
            @ToolParam(name = "ticker", description = "股票代码，例如BABA、00700、TCOM") String ticker,
            @ToolParam(name = "from_period", required = false, description = "起始报告期，例如2024Q1、2024、FY2024；可空") String fromPeriod,
            @ToolParam(name = "to_period", required = false, description = "结束报告期，例如2025Q1；可空") String toPeriod,
            @ToolParam(name = "form_type", required = false, description = "报告类型：FY=年报，Q1/Q2/Q3/Q4=季报，H1/H2=中报；可空") String formType,
            @ToolParam(name = "top_k", required = false, description = "返回的引用片段数量，默认5") Integer topK
    ) {
        try {
            String normTicker = StringUtils.upperCase(ticker);
            FilingQuery.FilingQueryBuilder qb = FilingQuery.builder()
                    .question(question)
                    .ticker(normTicker)
                    .topK(topK == null ? 5 : topK)
                    .formType(formType);
            applyPeriod(qb, fromPeriod, toPeriod);
            FilingQuery query = qb.build();
            FilingAnswer answer = service.answer(query);
            StringBuilder sb = new StringBuilder();
            sb.append(answer.getAnswer()).append("\n");
            if (!answer.getAnswer().contains("引用来源") && answer.getCitations() != null && !answer.getCitations().isEmpty()) {
                sb.append("\n## 引用来源\n");
                int i = 1;
                for (FilingChunk c : answer.getCitations()) {
                    sb.append("[C").append(i).append("] ").append(renderCitationLine(c)).append("\n");
                    i++;
                }
            }
            sb.append("\n(backend=").append(answer.getBackend())
                    .append(", model=").append(answer.getModel())
                    .append(", elapsed=").append(answer.getElapsedMs()).append("ms)");
            return sb.toString();
        } catch (Exception e) {
            return "filing_qa failed: " + e.getMessage();
        }
    }

    @Tool(name = "filing_qa_build", description = "构建或重建指定股票的财报RAG索引。第一次查询该股票前必须调用一次。force=true时强制重建。")
    public String buildIndex(
            @ToolParam(name = "ticker", description = "股票代码，例如BABA、00700") String ticker,
            @ToolParam(name = "force", required = false, description = "是否强制重建，默认false") Boolean force
    ) {
        try {
            FilingBuildReport report = service.buildIndex(ticker, Boolean.TRUE.equals(force));
            StringBuilder sb = new StringBuilder();
            sb.append("索引构建完成：ticker=").append(report.getTicker())
                    .append(", backend=").append(report.getBackend())
                    .append(", 处理文档数=").append(report.getDocumentsProcessed())
                    .append(", 索引chunk数=").append(report.getChunksIndexed())
                    .append(", 耗时=").append(report.getElapsedMs()).append("ms");
            if (report.getErrors() != null && !report.getErrors().isEmpty()) {
                sb.append("\n错误：\n");
                for (String err : report.getErrors()) sb.append(" - ").append(err).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "filing_qa_build failed: " + e.getMessage();
        }
    }

    private void applyPeriod(FilingQuery.FilingQueryBuilder qb, String fromPeriod, String toPeriod) {
        ParsedPeriod from = QueryParser.parsePeriod(fromPeriod);
        ParsedPeriod to = QueryParser.parsePeriod(toPeriod);
        if (from.fiscalYear() != null) {
            qb.fromFiscalYear(from.fiscalYear());
            if (from.fiscalPeriod() != null) qb.fiscalPeriod(from.fiscalPeriod());
        }
        if (to.fiscalYear() != null) {
            qb.toFiscalYear(to.fiscalYear());
            if (from.fiscalPeriod() == null && to.fiscalPeriod() != null) {
                qb.fiscalPeriod(to.fiscalPeriod());
            }
        }
    }

    private String renderCitationLine(FilingChunk c) {
        StringBuilder sb = new StringBuilder();
        if (c.getTicker() != null) sb.append(c.getTicker());
        if (c.getFormType() != null) sb.append(" ").append(c.getFormType());
        if (c.getFiscalYear() != null) sb.append(c.getFiscalYear());
        if (c.getFiscalPeriod() != null) sb.append(c.getFiscalPeriod());
        if (c.getSectionTitle() != null) sb.append(" ").append(c.getSectionTitle());
        if (c.getPageNumber() != null) sb.append(" p.").append(c.getPageNumber());
        if (c.getContent() != null) {
            String snippet = StringUtils.replaceChars(c.getContent(), '\n', ' ');
            snippet = StringUtils.abbreviate(snippet, 80);
            sb.append(": ").append(snippet);
        }
        return sb.toString();
    }
}
