package io.invest.iagent.tools.filingkb;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.invest.iagent.filingkb.FilingKbBuildService;
import io.invest.iagent.filingkb.FilingKbQaService;
import io.invest.iagent.filingkb.model.FilingBuildReport;
import io.invest.iagent.filingkb.model.FilingChunk;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 财报知识库工具：在通用 RAG 之上提供财报文档建库与带业务增强的检索。
 * <ul>
 *   <li>{@code filing_kb_qa} — 检索财报片段（带引用），由 Agent 组织最终答案。</li>
 *   <li>{@code filing_kb_build} — 将 workspace/portfolio 下的财报文件处理后入库。</li>
 * </ul>
 */
public class FilingKbTool {

    private final FilingKbQaService qaService;
    private final FilingKbBuildService buildService;

    public FilingKbTool(FilingKbQaService qaService, FilingKbBuildService buildService) {
        this.qaService = qaService;
        this.buildService = buildService;
    }

    @Tool(name = "filing_kb_qa", description = "检索财报知识库以回答上市公司财报相关问题（收入、利润、现金流、同比环比、指引等）。"
            + "支持按股票代码和财报周期过滤，自动解析\"最新财报/去年同期/近N个季度\"等相对时间，"
            + "并对金融术语做同义词扩展。返回带引用编号的片段，供你组织最终答案并标注引用。"
            + "股票代码和周期为可选：不填时尝试从问题中解析。")
    public String qa(
            @ToolParam(name = "question", description = "关于财报的问题") String question,
            @ToolParam(name = "ticker", required = false, description = "股票代码（大写，如 00700、AAPL）；不填则从问题解析") String ticker,
            @ToolParam(name = "period", required = false, description = "财报周期，如 2026Q1、FY2025、2025H1；不填则按问题中的时间表达（含最新/去年同期）解析") String period,
            @ToolParam(name = "top_k", required = false, description = "返回片段数，默认 5") Integer topK
    ) {
        try {
            int k = topK == null || topK <= 0 ? 5 : topK;
            List<FilingChunk> chunks = qaService.ask(question, ticker, period, k);
            if (chunks == null || chunks.isEmpty()) {
                return "财报知识库中未检索到与问题相关的片段。请确认知识库已构建（filing_kb_build）或调整问题/股票代码/周期。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("从财报知识库检索到 ").append(chunks.size()).append(" 条片段：\n\n");
            for (FilingChunk c : chunks) {
                if (StringUtils.isNotBlank(c.getCitation())) {
                    sb.append(c.getCitation());
                }
                sb.append(" score=").append(String.format("%.3f", c.getScore())).append("\n");
                String content = c.getContent() == null ? "" : c.getContent().trim();
                content = content.replaceAll("\\s+", " ");
                sb.append(StringUtils.abbreviate(content, 600)).append("\n\n");
            }
            sb.append("请基于以上片段作答，并在关键事实后用 [Cn] 标注引用。");
            return sb.toString();
        } catch (Exception e) {
            return "filing_kb_qa failed: " + e.getMessage();
        }
    }

    @Tool(name = "filing_kb_build", description = "将 workspace/portfolio/<ticker>/filings/ 下的财报文件（PDF/HTML）切分、嵌入并写入财报知识库。"
            + "入库时自动挂接股票代码、财报周期、表单类型、段落标题等标签。重复构建会先删除该文档旧数据再写入（幂等）。")
    public String build(
            @ToolParam(name = "ticker", description = "股票代码（大写，如 00700、AAPL）") String ticker,
            @ToolParam(name = "force", required = false, description = "是否强制重建，默认 false") Boolean force
    ) {
        try {
            if (StringUtils.isBlank(ticker)) {
                return "filing_kb_build failed: ticker is required";
            }
            FilingBuildReport report = buildService.buildTicker(ticker, Boolean.TRUE.equals(force));
            StringBuilder sb = new StringBuilder();
            sb.append("财报知识库构建完成：ticker=").append(report.getTicker())
                    .append("，文档数=").append(report.getDocuments())
                    .append("，片段数=").append(report.getChunks());
            if (!report.getErrors().isEmpty()) {
                sb.append("，错误数=").append(report.getErrors().size()).append("\n错误明细：\n");
                for (String err : report.getErrors()) {
                    sb.append(" - ").append(err).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "filing_kb_build failed: " + e.getMessage();
        }
    }
}
