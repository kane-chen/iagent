package io.invest.iagent.tools.filing;

import io.invest.iagent.model.TickerMarket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FinancialFilingDownloadToolTest {

    @TempDir
    Path workspace;

    @Test
    void downloadFiling_acceptsIntFiscalYearOverload() {
        FinancialFilingDownloadTool tool = new FinancialFilingDownloadTool(workspace);

        String result = tool.downloadFiling("", 2024, null);

        assertNotNull(result);
        assertTrue(result.startsWith("Error:"));
    }

    @Test
    void parseFiscalYears_supportsAllSingleAndMultipleYears() throws Exception {
        FinancialFilingDownloadTool tool = new FinancialFilingDownloadTool(workspace);
        Method method = FinancialFilingDownloadTool.class.getDeclaredMethod("parseFiscalYears", String.class);
        method.setAccessible(true);

        Object all = method.invoke(tool, (String) null);
        assertTrue((Boolean) invokeRecordAccessor(all, "allYears"));

        Object single = method.invoke(tool, "2024");
        assertFalse((Boolean) invokeRecordAccessor(single, "allYears"));
        assertEquals(Set.of(2024), invokeRecordAccessor(single, "years"));

        Object multiple = method.invoke(tool, "2024, 2025,2024");
        assertEquals(Set.of(2024, 2025), invokeRecordAccessor(multiple, "years"));
    }

    @Test
    void parseFilingTypes_supportsAliasesByMarket() throws Exception {
        FinancialFilingDownloadTool tool = new FinancialFilingDownloadTool(workspace);
        Method method = FinancialFilingDownloadTool.class.getDeclaredMethod("parseFilingTypes", String.class, TickerMarket.class);
        method.setAccessible(true);

        Object usAnnual = method.invoke(tool, "annual", TickerMarket.US);
        assertEquals(Set.of("10-K", "20-F"), invokeRecordAccessor(usAnnual, "types"));

        Object cnTypes = method.invoke(tool, "annual,semi-annual,quarterly", TickerMarket.CN_A);
        assertEquals(Set.of("FY", "H1", "Q1"), invokeRecordAccessor(cnTypes, "types"));

        Object hkInterim = method.invoke(tool, "interim", TickerMarket.HK);
        assertEquals(Set.of("H1"), invokeRecordAccessor(hkInterim, "types"));
    }

    @Test
    void calculateDirectoryFingerprint_ignoresMetaJson() throws Exception {
        FinancialFilingDownloadTool tool = new FinancialFilingDownloadTool(workspace);
        Path dir = workspace.resolve("fingerprint");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("report.html"), "report-body");
        Files.writeString(dir.resolve("meta.json"), "v1");

        Method method = FinancialFilingDownloadTool.class.getDeclaredMethod("calculateDirectoryFingerprint", Path.class);
        method.setAccessible(true);
        String first = (String) method.invoke(tool, dir);

        Files.writeString(dir.resolve("meta.json"), "v2");
        String second = (String) method.invoke(tool, dir);

        assertEquals(first, second);
    }

    @Test
    void hkDownloadReturnsManualLinksWithoutNetwork() {
        FinancialFilingDownloadTool tool = new FinancialFilingDownloadTool(workspace);

        String result = tool.downloadFiling("00700", "2024", "annual");

        assertTrue(result.contains("HK filing download requires manual operation"));
        assertTrue(result.contains("00700"));
        assertTrue(result.contains("2024"));
    }

    @Test
    void shouldDownloadFile_skipsXexExhibits() throws Exception {
        FinancialFilingDownloadTool tool = new FinancialFilingDownloadTool(workspace);
        Method method = FinancialFilingDownloadTool.class.getDeclaredMethod(
                "shouldDownloadFile", String.class, String.class, String.class, String.class);
        method.setAccessible(true);

        assertFalse((Boolean) method.invoke(tool,
                "li-20241231xex11d2.htm", "li-20241231x20f.htm", "20-F", "text/html"));
        assertTrue((Boolean) method.invoke(tool,
                "li-20241231x20f.htm", "li-20241231x20f.htm", "20-F", "text/html"));
        assertTrue((Boolean) method.invoke(tool,
                "li-20241231.xsd", "li-20241231x20f.htm", "20-F", "application/xml"));
    }

    @Test
    void findAllMatchingUsFilings_matchesRequestedFiscalYearOnly() throws Exception {
        FinancialFilingDownloadTool tool = new FinancialFilingDownloadTool(workspace);
        Method method = FinancialFilingDownloadTool.class.getDeclaredMethod(
                "findAllMatchingUsFilings", com.fasterxml.jackson.databind.JsonNode.class,
                Set.class, Set.class, boolean.class);
        method.setAccessible(true);

        com.fasterxml.jackson.databind.JsonNode submissions = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {
                  "filings": {
                    "recent": {
                      "form": ["20-F", "20-F"],
                      "accessionNumber": ["0001410578-25-000678", "0001104659-24-046495"],
                      "primaryDocument": ["li-20241231x20f.htm", "li-20231231x20f.htm"],
                      "reportDate": ["2024-12-31", "2023-12-31"],
                      "filingDate": ["2025-04-10", "2024-04-12"]
                    }
                  }
                }
                """);

        @SuppressWarnings("unchecked")
        java.util.List<com.fasterxml.jackson.databind.JsonNode> results =
                (java.util.List<com.fasterxml.jackson.databind.JsonNode>) method.invoke(
                        tool, submissions, Set.of("20-F"), Set.of(2024), false);

        assertEquals(1, results.size());
        assertEquals("0001410578-25-000678", results.get(0).get("accessionNumber").asText());
        assertEquals(2024, results.get(0).get("fiscalYear").asInt());
    }

    @Test
    void findAllMatchingUsFilings_infersAnnualFiscalYearWithoutReportDate() throws Exception {
        FinancialFilingDownloadTool tool = new FinancialFilingDownloadTool(workspace);
        Method method = FinancialFilingDownloadTool.class.getDeclaredMethod(
                "findAllMatchingUsFilings", com.fasterxml.jackson.databind.JsonNode.class,
                Set.class, Set.class, boolean.class);
        method.setAccessible(true);

        com.fasterxml.jackson.databind.JsonNode submissions = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {
                  "filings": {
                    "recent": {
                      "form": ["20-F", "20-F"],
                      "accessionNumber": ["no-report-date-2024", "no-report-date-2023"],
                      "primaryDocument": ["li-20241231x20f.htm", "li-20231231x20f.htm"],
                      "reportDate": ["", ""],
                      "filingDate": ["2025-04-10", "2024-04-12"]
                    }
                  }
                }
                """);

        @SuppressWarnings("unchecked")
        java.util.List<com.fasterxml.jackson.databind.JsonNode> results =
                (java.util.List<com.fasterxml.jackson.databind.JsonNode>) method.invoke(
                        tool, submissions, Set.of("20-F"), Set.of(2024), false);

        assertEquals(1, results.size());
        assertEquals("no-report-date-2024", results.get(0).get("accessionNumber").asText());
        assertEquals(2024, results.get(0).get("fiscalYear").asInt());
    }

    private Object invokeRecordAccessor(Object record, String accessor) throws Exception {
        Method method = record.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return method.invoke(record);
    }


}
