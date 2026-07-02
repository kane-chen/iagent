package io.invest.iagent.tools.filing;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.invest.iagent.service.filing.FinancialMetricsQueryService;
import io.invest.iagent.model.FinancialIndexValueDTO;

import java.nio.file.Path;
import java.util.List;

/**
 * 弃用：改用skill futu-financial-report
 * @author chan
 */
@Deprecated
public class FinancialMetricsQueryTool {

    private final FinancialMetricsQueryService service;

    public FinancialMetricsQueryTool(Path workspace, String userAgent){
        this.service = new FinancialMetricsQueryService(workspace,userAgent) ;
    }

    public FinancialMetricsQueryTool(FinancialMetricsQueryService service){
        this.service = service;
    }

    @Tool(name = "query_financial_metrics",
            description = "查询指定公司在指定财年区间内的财务指标，支持年度和季度数据，优先使用SEC XBRL companyfacts，也可使用本地缓存/已下载财报。")
    public List<FinancialIndexValueDTO> queryFinancialMetrics(
            @ToolParam(name = "ticker", description = "股票代码，例如 AAPL、PDD、LI") String ticker,
            @ToolParam(name = "metrics", description = "逗号分隔的财务指标，例如 Revenue,CostOfRevenue,OperatingExpenses,OperatingIncomeLoss") String metrics,
            @ToolParam(name = "start_fiscal_year", required = false, description = "起始财年，例如 2022") String startFiscalYear,
            @ToolParam(name = "end_fiscal_year", required = false, description = "结束财年，例如 2025") String endFiscalYear,
            @ToolParam(name = "fiscal_periods", required = false, description = "财期，逗号分隔：FY,Q1,Q2,Q3,Q4；不传则同时查年度和季度") String fiscalPeriods,
            @ToolParam(name = "source_preference", required = false, description = "auto, online, cache, local；默认 auto") String sourcePreference
    ) {
        try{
            return service.queryFinancialMetrics(ticker, metrics, startFiscalYear, endFiscalYear, fiscalPeriods, sourcePreference);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
