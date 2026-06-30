package io.invest.iagent.tools.filing;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.invest.iagent.model.TickerMarket;
import io.invest.iagent.service.filing.FinancialFilingDownloadService;

import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Set;

/**
 * 统一的财报下载工具。
 * Agent 工具层只保留注解入口和兼容方法，核心实现委托给 service 包。
 */
public class FinancialFilingDownloadTool {

    private final FinancialFilingDownloadService service;

    public FinancialFilingDownloadTool(Path baseDir) {
        this.service = new FinancialFilingDownloadService(baseDir);
    }

    public FinancialFilingDownloadTool(Path baseDir, String secUserAgent) {
        this.service = new FinancialFilingDownloadService(baseDir, secUserAgent);
    }

    public FinancialFilingDownloadTool(FinancialFilingDownloadService service) {
        this.service = service;
    }

    @Tool(name = "download_filing",
          description = "智能下载财报文件。自动识别市场（美股/A股/港股），从对应数据源下载财报。" +
                       "美股 ticker 如 AAPL，A股代码如 600519，港股代码如 00700。")
    public String downloadFiling(
            @ToolParam(name = "ticker", description = "股票代码：美股(AAPL)、A股(600519)、港股(00700)") String ticker,
            @ToolParam(name = "fiscal_year", required = false,
                       description = "财年，多个用逗号分隔，比如 2024,2025。为空代表所有年份") String fiscalYear,
            @ToolParam(name = "filing_type", required = false,
                       description = "财报类型：美股(10-K/10-Q/20-F)、A股(annual/semi-annual/quarterly)、港股(annual/interim)，多个用逗号分隔。为空代表所有文件") String filingType,
            @ToolParam(name = "overwrite", required = false,
                       description = "是否覆盖已有的相同文件，默认 false") Boolean overwrite
    ) {
        return service.downloadFiling(ticker, fiscalYear, filingType, Boolean.TRUE.equals(overwrite)).getMessage();
    }

    public String downloadFiling(String ticker, String fiscalYear, String filingType) {
        return service.downloadFiling(ticker, fiscalYear, filingType).getMessage();
    }

    public String downloadFiling(String ticker, int fiscalYear, String filingType) {
        return service.downloadFiling(ticker, fiscalYear, filingType).getMessage();
    }

    public String downloadFiling(String ticker, int fiscalYear, String filingType, boolean overwrite) {
        return service.downloadFiling(ticker, fiscalYear, filingType, overwrite).getMessage();
    }

    @SuppressWarnings("unused")
    private FinancialFilingDownloadService.YearParseResult parseFiscalYears(String fiscalYear) {
        return service.parseFiscalYears(fiscalYear);
    }

    @SuppressWarnings("unused")
    private FinancialFilingDownloadService.TypeParseResult parseFilingTypes(String filingType, TickerMarket market) {
        return service.parseFilingTypes(filingType, market);
    }

    @SuppressWarnings("unused")
    private String calculateDirectoryFingerprint(Path dir) throws IOException, NoSuchAlgorithmException {
        return service.calculateDirectoryFingerprint(dir);
    }

    @SuppressWarnings("unused")
    private boolean shouldDownloadFile(String fileName, String primaryDocument, String formType, String secDocumentType) {
        return service.shouldDownloadFile(fileName, primaryDocument, formType, secDocumentType);
    }

    @SuppressWarnings("unused")
    private List<JsonNode> findAllMatchingUsFilings(JsonNode submissions, Set<String> formTypes,
                                                    Set<Integer> fiscalYears, boolean allYears) {
        return service.findAllMatchingUsFilings(submissions, formTypes, fiscalYears, allYears);
    }
}
