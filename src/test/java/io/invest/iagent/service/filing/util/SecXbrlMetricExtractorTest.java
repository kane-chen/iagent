package io.invest.iagent.service.filing.util;

import io.invest.iagent.model.FinanceQueryParam;
import io.invest.iagent.model.FinancialIndexValueDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SecXbrlMetricExtractorTest {

    @Test
    void extractLiFyXbrlFromLocalFilings() throws IOException {
        Path workspace = Paths.get(System.getProperty("user.dir")).resolve("./test-filings");
        SecXbrlMetricExtractor extractor = new SecXbrlMetricExtractor(workspace, new SecFilingDataUtil(workspace, "io/yiying5@gmail.com"));

        List<FinancialIndexValueDTO> result = extractor.extract("LI", FinanceQueryParam.builder()
                .ticker("LI")
                .indexCodes("Revenue,CostOfRevenue,OperatingIncomeLoss,NetIncomeLoss")
                .fiscalYears("2024,2025")
                .fiscalPeriods("FY")
                .build());

        assertNotNull(result);
        assertFalse(result.isEmpty(), "LI annual 20-F XBRL should be extracted from local filings");
        assertTrue(result.stream().allMatch(v -> v.getSource().startsWith("LOCAL_XBRL_LIMITED:")));
        assertTrue(result.stream().allMatch(v -> "FY".equals(v.getFiscalPeriod())));
        assertTrue(result.stream().allMatch(v -> "20-F".equals(v.getTableType())));
        assertTrue(result.stream().allMatch(v -> v.getCurrency() != null));
        assertTrue(result.stream().anyMatch(v -> "Revenue".equals(v.getIndex())));
        assertTrue(result.stream().anyMatch(v -> "CostOfRevenue".equals(v.getIndex())));
        assertTrue(result.stream().anyMatch(v -> "OperatingIncomeLoss".equals(v.getIndex())));
        assertTrue(result.stream().anyMatch(v -> "NetIncomeLoss".equals(v.getIndex())));
    }

    @Test
    void extractLiQuarterOnlyDoesNotReturnAnnualXbrl() throws IOException {
        Path workspace = Paths.get(System.getProperty("user.dir")).resolve("./test-filings");
        SecXbrlMetricExtractor extractor = new SecXbrlMetricExtractor(workspace, new SecFilingDataUtil(workspace, "io/yiying5@gmail.com"));

        List<FinancialIndexValueDTO> result = extractor.extract("LI", FinanceQueryParam.builder()
                .ticker("LI")
                .indexCodes("Revenue,CostOfRevenue,OperatingIncomeLoss,NetIncomeLoss")
                .fiscalYears("2024,2025")
                .fiscalPeriods("Q1,Q2,Q3,Q4")
                .build());

        assertNotNull(result);
        assertTrue(result.stream().noneMatch(v -> v.getSource().startsWith("LOCAL_XBRL_LIMITED:")),
                "LI annual 20-F XBRL should not satisfy quarter-only queries");
    }

    @Test
    void extractSkipsSupportFilesAndUsesInstanceFile(@TempDir Path workspace) throws IOException {
        Path filingDir = workspace.resolve("portfolio").resolve("TESTX").resolve("filings").resolve("fil_test_active");
        Files.createDirectories(filingDir);
        Files.writeString(filingDir.resolve("meta.json"), metaJson("fil_test_active", true, true, false,
                "FilingSummary.xml,test.xsd,test_cal.xml,test_def.xml,test_lab.xml,test_pre.xml,test_htm.xml,unrelated.pdf"));
        Files.writeString(filingDir.resolve("FilingSummary.xml"), "<FilingSummary></FilingSummary>");
        Files.writeString(filingDir.resolve("test.xsd"), "<schema></schema>");
        Files.writeString(filingDir.resolve("test_cal.xml"), "<calculationLink></calculationLink>");
        Files.writeString(filingDir.resolve("test_def.xml"), "<definitionLink></definitionLink>");
        Files.writeString(filingDir.resolve("test_lab.xml"), "<labelLink></labelLink>");
        Files.writeString(filingDir.resolve("test_pre.xml"), "<presentationLink></presentationLink>");
        Files.writeString(filingDir.resolve("unrelated.pdf"), "not a pdf, but should be ignored");
        Files.writeString(filingDir.resolve("test_htm.xml"), xbrlInstance("2025", "RevenueFromContractWithCustomerExcludingAssessedTax", "100"));

        SecXbrlMetricExtractor extractor = new SecXbrlMetricExtractor(workspace, new SecFilingDataUtil(workspace, "io/test@test.com"));
        List<FinancialIndexValueDTO> result = extractor.extract("TESTX", FinanceQueryParam.builder()
                .ticker("TESTX")
                .indexCodes("Revenue")
                .fiscalYears("2025")
                .fiscalPeriods("FY")
                .build());

        assertEquals(1, result.size());
        assertEquals("Revenue", result.get(0).getIndex());
        assertEquals("100", result.get(0).getValue());
        assertTrue(result.get(0).getSource().startsWith("LOCAL_XBRL_LIMITED:"));
    }

    @Test
    void extractSkipsDeletedAndIncompleteFilings(@TempDir Path workspace) throws IOException {
        createFiling(workspace, "fil_active", true, false, "100");
        createFiling(workspace, "fil_deleted", true, true, "999");
        createFiling(workspace, "fil_incomplete", false, false, "888");

        SecXbrlMetricExtractor extractor = new SecXbrlMetricExtractor(workspace, new SecFilingDataUtil(workspace, "io/test@test.com"));
        List<FinancialIndexValueDTO> result = extractor.extract("TESTX", FinanceQueryParam.builder()
                .ticker("TESTX")
                .indexCodes("Revenue")
                .fiscalYears("2025")
                .fiscalPeriods("FY")
                .build());

        assertEquals(1, result.size());
        assertEquals("100", result.get(0).getValue());
        assertEquals("LOCAL_XBRL_LIMITED:fil_active", result.get(0).getSource());
    }

    private void createFiling(Path workspace, String documentId, boolean ingestComplete, boolean deleted, String revenue) throws IOException {
        Path filingDir = workspace.resolve("portfolio").resolve("TESTX").resolve("filings").resolve(documentId);
        Files.createDirectories(filingDir);
        Files.writeString(filingDir.resolve("meta.json"), metaJson(documentId, true, ingestComplete, deleted, "test_htm.xml"));
        Files.writeString(filingDir.resolve("test_htm.xml"), xbrlInstance("2025", "RevenueFromContractWithCustomerExcludingAssessedTax", revenue));
    }

    private String metaJson(String documentId, boolean hasXbrl, boolean ingestComplete, boolean deleted, String filesCsv) {
        String files = List.of(filesCsv.split(",")).stream()
                .map(name -> "{\"name\": \"" + name + "\"}")
                .collect(Collectors.joining(","));
        return """
                {
                  "document_id" : "%s",
                  "ticker" : "TESTX",
                  "form_type" : "20-F",
                  "fiscal_year" : 2025,
                  "fiscal_period" : null,
                  "report_date" : "2025-12-31",
                  "filing_date" : "2026-04-30",
                  "ingest_complete" : %s,
                  "is_deleted" : %s,
                  "has_xbrl" : %s,
                  "primary_document" : "FilingSummary.xml",
                  "files" : [%s]
                }
                """.formatted(documentId, ingestComplete, deleted, hasXbrl, files);
    }

    private String xbrlInstance(String year, String concept, String value) {
        return """
                <xbrl>
                  <dei:DocumentFiscalYearFocus contextRef="dei">%s</dei:DocumentFiscalYearFocus>
                  <dei:DocumentFiscalPeriodFocus contextRef="dei">FY</dei:DocumentFiscalPeriodFocus>
                  <context id="dei"><entity><identifier>TESTX</identifier></entity><period><instant>%s-12-31</instant></period></context>
                  <context id="c1"><entity><identifier>TESTX</identifier></entity><period><startDate>%s-01-01</startDate><endDate>%s-12-31</endDate></period></context>
                  <unit id="u1"><measure>iso4217:USD</measure></unit>
                  <us-gaap:%s contextRef="c1" unitRef="u1">%s</us-gaap:%s>
                </xbrl>
                """.formatted(year, year, year, year, concept, value, concept);
    }
}
