package io.invest.iagent.tools.filing;

import com.alibaba.fastjson2.JSON;
import io.invest.iagent.model.FinancialIndexValueDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class FinancialMetricsQueryToolTest {

    private static FinancialMetricsQueryTool tool;
    private static Path workspace;

    @BeforeAll
    static void setUp() {
        String userAgent = "io/yiying5@gmail.com" ;
        workspace = Paths.get(System.getProperty("user.dir")).resolve("./test-filings");
        tool = new FinancialMetricsQueryTool(workspace, userAgent);
    }

    @Test
    void queryCache_AAPLRevenueAndCostAndOperatingIncome() {
        List<FinancialIndexValueDTO> result = tool.queryFinancialMetrics(
                "AAPL",
                "Revenue,CostOfRevenue,OperatingIncomeLoss",
                "2024", "2025",
                "FY,Q1,Q2,Q3,Q4",
                "cache");
        assertNotNull(result);
        assertFalse(result.isEmpty());
        System.out.println("=== Revenue/Cost/OpIncome ===");
        printResult(result);
        // Verify requested canonical metrics present
        assertTrue(result.stream().anyMatch(v -> "Revenue".equals(v.getIndex())));
        assertTrue(result.stream().anyMatch(v -> "CostOfRevenue".equals(v.getIndex())));
        assertTrue(result.stream().anyMatch(v -> "OperatingIncomeLoss".equals(v.getIndex())));
        // Verify fiscal years in range
        assertTrue(result.stream().allMatch(v -> v.getFiscalYear() == 2024 || v.getFiscalYear() == 2025));
        // Verify source, currency filled
        assertTrue(result.stream().allMatch(v -> v.getSource() != null));
        assertTrue(result.stream().allMatch(v -> v.getCurrency() != null));
    }

    @Test
    void queryCache_metricAliasesNormalizeCorrectly() {
        List<FinancialIndexValueDTO> result = tool.queryFinancialMetrics(
                "AAPL",
                "sales,cost,opex,operating_income_loss",
                "2025", null,
                "FY",
                "cache");
        assertNotNull(result);
        assertFalse(result.isEmpty());
        List<String> indexes = result.stream().map(FinancialIndexValueDTO::getIndex).collect(Collectors.toList());
        System.out.println("=== Alias test indexes: " + indexes + " ===");
        assertTrue(indexes.contains("Revenue"));
        assertTrue(indexes.contains("CostOfRevenue"));
        assertTrue(indexes.contains("OperatingExpenses"));
        assertTrue(indexes.contains("OperatingIncomeLoss"));
    }

    @Test
    void queryCache_yearRangeExpandsCorrectly(){
        List<FinancialIndexValueDTO> result = tool.queryFinancialMetrics(
                "AAPL",
                "Revenue",
                "2023", "2025",
                "FY,Q1,Q2,Q3,Q4",
                "cache");
        assertNotNull(result);
        assertFalse(result.isEmpty());
        // 2023-2025 all three years appear
        assertTrue(result.stream().anyMatch(v -> v.getFiscalYear() == 2023));
        assertTrue(result.stream().anyMatch(v -> v.getFiscalYear() == 2024));
        assertTrue(result.stream().anyMatch(v -> v.getFiscalYear() == 2025));
        // No year outside range
        assertTrue(result.stream().noneMatch(v -> v.getFiscalYear() < 2023 || v.getFiscalYear() > 2025));
    }

    @Test
    void invalidPeriod_throwsIllegalArgument(){
        assertThrows(IllegalArgumentException.class, () ->
            tool.queryFinancialMetrics("AAPL", "Revenue", null, null, "Q5", "cache"));
    }

    @Test
    void emptyMetrics_throwsIllegalArgument(){
        assertThrows(IllegalArgumentException.class, () ->
            tool.queryFinancialMetrics("AAPL", "", null, null, null, "cache"));
    }

    @Test
    void cacheOnly_doesNotThrowGivenExistingCache(){
        List<FinancialIndexValueDTO> result = tool.queryFinancialMetrics(
                "AAPL", "NetIncomeLoss", "2025", null, null, "cache");
        assertNotNull(result);
        // Should return some data without hitting network
        assertFalse(result.isEmpty());
    }

    @Test
    void localFallback_withTempWorkspace(@TempDir Path tempWorkspace) throws IOException {
        // Seed a minimal local SEC filing
        String ticker = "TESTX";
        Path filingDir = tempWorkspace.resolve("portfolio").resolve(ticker).resolve("filings").resolve("fil_test-00001");
        Files.createDirectories(filingDir);

        // minimal meta.json with inline XBRL primary doc
        String meta = """
                {
                  "document_id" : "fil_test-00001",
                  "ticker" : "TESTX",
                  "company_id" : "0000000001",
                  "form_type" : "10-Q",
                  "fiscal_year" : 2025,
                  "fiscal_period" : null,
                  "report_date" : "2025-03-31",
                  "filing_date" : "2025-06-10",
                  "has_xbrl" : true,
                  "primary_document" : "testx-20250531.htm",
                  "files": [{"name": "testx-20250531.htm"}]
                }
                """;
        Files.writeString(filingDir.resolve("meta.json"), meta);

        // inline XBRL HTML with Revenue fact
        String inlineXbrl = """
                <html>
                <body>
                <ix:header>
                  <references><reference xmlns:xbrli="http://www.xbrl.org/2003/instance"/></references>
                </ix:header>
                <ix:resources>
                  <xbrli:context id="c1">
                    <xbrli:entity><xbrli:identifier scheme="http">TESTX</xbrli:identifier></xbrli:entity>
                    <xbrli:period>
                      <xbrli:startDate>2025-01-01</xbrli:startDate>
                      <xbrli:endDate>2025-03-31</xbrli:endDate>
                    </xbrli:period>
                  </xbrli:context>
                  <xbrli:unit id="u1">
                    <xbrli:measure>iso4217:USD</xbrli:measure>
                  </xbrli:unit>
                </ix:resources>
                <ix:nonFraction name="us-gaap:RevenueFromContractWithCustomerExcludingAssessedTax" contextRef="c1" unitRef="u1" scale="6">123.456</ix:nonFraction>
                </body>
                </html>
                """;
        Files.writeString(filingDir.resolve("testx-20250531.htm"), inlineXbrl);

        // Add minimal sec cache dir with index_mapping
        Path secDir = tempWorkspace.resolve("sec");
        Files.createDirectories(secDir);

        // Use tool with temp workspace
        FinancialMetricsQueryTool localTool = new FinancialMetricsQueryTool(tempWorkspace, "io/test@test.com");
        List<FinancialIndexValueDTO> result = localTool.queryFinancialMetrics(
                "TESTX",
                "Revenue",
                "2025", "2025",
                "Q1",
                "local");
        assertNotNull(result);
        assertFalse(result.isEmpty(), "Should extract Revenue from local XBRL");
        FinancialIndexValueDTO revenue = result.stream()
                .filter(v -> "Revenue".equals(v.getIndex()))
                .findFirst().orElse(null);
        assertNotNull(revenue);
        assertEquals("123456000", revenue.getValue(), "should scale by 10^6");
        assertEquals("2025", String.valueOf(revenue.getFiscalYear()));
        assertEquals("Q1", revenue.getFiscalPeriod());
        assertTrue(revenue.getSource().startsWith("LOCAL_XBRL_LIMITED:"));
        assertNotNull(revenue.getCurrency());
        assertNotNull(revenue.getStartDate());
        assertNotNull(revenue.getEndDate());
        System.out.println("=== Local fallback result ===");
        printResult(result);
    }

    @Test
    void localXbrlInstance_withDeiFiscalFocus(@TempDir Path tempWorkspace) throws IOException {
        Path filingDir = tempWorkspace.resolve("portfolio").resolve("TESTX").resolve("filings").resolve("fil_test-00002");
        Files.createDirectories(filingDir);
        Files.writeString(filingDir.resolve("meta.json"), """
                {
                  "document_id" : "fil_test-00002",
                  "ticker" : "TESTX",
                  "form_type" : "20-F",
                  "fiscal_year" : 2024,
                  "fiscal_period" : null,
                  "report_date" : "2024-12-31",
                  "filing_date" : "2026-04-30",
                  "has_xbrl" : true,
                  "primary_document" : "FilingSummary.xml",
                  "files": [{"name": "FilingSummary.xml"},{"name": "testx-20251231_htm.xml"}]
                }
                """);
        Files.writeString(filingDir.resolve("FilingSummary.xml"), "<FilingSummary></FilingSummary>");
        Files.writeString(filingDir.resolve("testx-20251231_htm.xml"), """
                <xbrl>
                  <dei:DocumentFiscalYearFocus contextRef="dei">2025</dei:DocumentFiscalYearFocus>
                  <dei:DocumentFiscalPeriodFocus contextRef="dei">FY</dei:DocumentFiscalPeriodFocus>
                  <context id="dei"><entity><identifier>TESTX</identifier></entity><period><instant>2025-12-31</instant></period></context>
                  <context id="c1"><entity><identifier>TESTX</identifier></entity><period><startDate>2025-01-01</startDate><endDate>2025-12-31</endDate></period></context>
                  <unit id="u1"><measure>iso4217:USD</measure></unit>
                  <us-gaap:RevenueFromContractWithCustomerExcludingAssessedTax contextRef="c1" unitRef="u1">123456</us-gaap:RevenueFromContractWithCustomerExcludingAssessedTax>
                </xbrl>
                """);

        FinancialMetricsQueryTool localTool = new FinancialMetricsQueryTool(tempWorkspace, "io/test@test.com");
        List<FinancialIndexValueDTO> result = localTool.queryFinancialMetrics(
                "TESTX", "Revenue", "2025", "2025", "FY", "local");

        FinancialIndexValueDTO revenue = result.stream().filter(v -> "Revenue".equals(v.getIndex())).findFirst().orElse(null);
        assertNotNull(revenue);
        assertEquals("123456", revenue.getValue());
        assertEquals(2025, revenue.getFiscalYear());
        assertEquals("FY", revenue.getFiscalPeriod());
        assertTrue(revenue.getSource().startsWith("LOCAL_XBRL_LIMITED:"));
    }

    @Test
    void localXbrlInstance_profitLossMapsToNetIncome(@TempDir Path tempWorkspace) throws IOException {
        Path filingDir = tempWorkspace.resolve("portfolio").resolve("TESTX").resolve("filings").resolve("fil_test-00003");
        Files.createDirectories(filingDir);
        Files.writeString(filingDir.resolve("meta.json"), """
                {
                  "document_id" : "fil_test-00003",
                  "ticker" : "TESTX",
                  "form_type" : "20-F",
                  "fiscal_year" : 2025,
                  "fiscal_period" : null,
                  "report_date" : "2025-12-31",
                  "filing_date" : "2026-04-30",
                  "has_xbrl" : true,
                  "primary_document" : "FilingSummary.xml",
                  "files": [{"name": "testx-20251231_htm.xml"}]
                }
                """);
        Files.writeString(filingDir.resolve("testx-20251231_htm.xml"), """
                <xbrl>
                  <dei:DocumentFiscalYearFocus contextRef="dei">2025</dei:DocumentFiscalYearFocus>
                  <dei:DocumentFiscalPeriodFocus contextRef="dei">FY</dei:DocumentFiscalPeriodFocus>
                  <context id="dei"><entity><identifier>TESTX</identifier></entity><period><instant>2025-12-31</instant></period></context>
                  <context id="c1"><entity><identifier>TESTX</identifier></entity><period><startDate>2025-01-01</startDate><endDate>2025-12-31</endDate></period></context>
                  <unit id="u1"><measure>iso4217:USD</measure></unit>
                  <us-gaap:ProfitLoss contextRef="c1" unitRef="u1">789000</us-gaap:ProfitLoss>
                </xbrl>
                """);

        FinancialMetricsQueryTool localTool = new FinancialMetricsQueryTool(tempWorkspace, "io/test@test.com");
        List<FinancialIndexValueDTO> result = localTool.queryFinancialMetrics(
                "TESTX", "NetIncomeLoss", "2025", "2025", "FY", "local");

        FinancialIndexValueDTO netIncome = result.stream().filter(v -> "NetIncomeLoss".equals(v.getIndex())).findFirst().orElse(null);
        assertNotNull(netIncome);
        assertEquals("789000", netIncome.getValue());
        assertTrue(netIncome.getSource().startsWith("LOCAL_XBRL_LIMITED:"));
    }

    @Test
    void queryPddLocal6kHtmlQuarterlyValues() {
        List<FinancialIndexValueDTO> result = tool.queryFinancialMetrics(
                "PDD",
                "Revenue,CostOfRevenue,OperatingExpenses,OperatingIncomeLoss,NetIncomeLoss",
                "2025", "2025",
                "Q3",
                "local");
        assertNotNull(result);
        Map<String, FinancialIndexValueDTO> byMetric = result.stream()
                .collect(Collectors.toMap(FinancialIndexValueDTO::getIndex, v -> v, (a,b)->a));
        assertPddQ3Metric(byMetric, "Revenue", "108276512000");
        assertPddQ3Metric(byMetric, "CostOfRevenue", "46840159000");
        assertPddQ3Metric(byMetric, "OperatingExpenses", "36410429000");
        assertPddQ3Metric(byMetric, "OperatingIncomeLoss", "25025924000");
        assertPddQ3Metric(byMetric, "NetIncomeLoss", "29328184000");
    }

    @Test
    void queryAutoMergesPddQuarterlyLocalValues() {
        List<FinancialIndexValueDTO> result = tool.queryFinancialMetrics(
                "PDD", "Revenue", "2025", "2025", "Q3", "auto");
        assertNotNull(result);
        FinancialIndexValueDTO revenue = result.stream().filter(v -> "Revenue".equals(v.getIndex())).findFirst().orElse(null);
        assertNotNull(revenue);
        assertEquals("108276512000", revenue.getValue());
        assertTrue(revenue.getSource().startsWith("LOCAL_6K_HTML:"));
    }

    private void assertPddQ3Metric(Map<String, FinancialIndexValueDTO> byMetric, String metric, String expectedValue) {
        FinancialIndexValueDTO value = byMetric.get(metric);
        assertNotNull(value, "Missing metric " + metric);
        assertEquals(expectedValue, value.getValue(), metric);
        assertEquals("CNY", value.getCurrency());
        assertEquals("Q3", value.getFiscalPeriod());
        assertEquals(2025, value.getFiscalYear());
        assertEquals("2025-07-01", value.getStartDate());
        assertEquals("2025-09-30", value.getEndDate());
        assertTrue(value.getSource().startsWith("LOCAL_6K_HTML:"));
    }

    private void printResult(List<FinancialIndexValueDTO> result) {
        result.forEach(v -> System.out.println(JSON.toJSONString(v)));
    }
}