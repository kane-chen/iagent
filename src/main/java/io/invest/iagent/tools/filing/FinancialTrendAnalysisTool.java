package io.invest.iagent.tools.filing;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.invest.iagent.model.FinancialTrendAnalysisResult;
import io.invest.iagent.service.filing.FinancialMetricsQueryService;
import io.invest.iagent.service.filing.FinancialTrendAnalysisService;

import java.nio.file.Path;

public class FinancialTrendAnalysisTool {

    private final FinancialTrendAnalysisService service;

    public FinancialTrendAnalysisTool(Path workspace, String userAgent) {
        this(new FinancialTrendAnalysisService(new FinancialMetricsQueryService(workspace, userAgent)));
    }

    public FinancialTrendAnalysisTool(FinancialTrendAnalysisService service) {
        this.service = service;
    }

    @Tool(name = "analyze_financial_trends",
            description = "查询公司最近N个季度的收入、成本、运营利润/亏损等指标，并计算同比变化；用于回答最近8个季度趋势类财报问题。")
    public FinancialTrendAnalysisResult analyzeFinancialTrends(
            @ToolParam(name = "ticker", description = "股票代码，例如 AAPL、PDD、LI") String ticker,
            @ToolParam(name = "metrics", required = false, description = "逗号分隔的财务指标，默认 Revenue,CostOfRevenue,OperatingIncomeLoss") String metrics,
            @ToolParam(name = "quarter_count", required = false, description = "最近季度数量，默认8") Integer quarterCount,
            @ToolParam(name = "end_fiscal_year", required = false, description = "结束财年，例如 2025；不传则使用当前年份") String endFiscalYear,
            @ToolParam(name = "source_preference", required = false, description = "auto, online, cache, local；默认 auto") String sourcePreference
    ) {
        try {
            return service.analyze(ticker, metrics, quarterCount, endFiscalYear, sourcePreference);
        } catch (Exception e) {
            return FinancialTrendAnalysisResult.builder()
                    .success(false)
                    .ticker(ticker)
                    .quarterCount(quarterCount)
                    .error(e.getClass().getSimpleName() + ": " + e.getMessage())
                    .build();
        }
    }
}
