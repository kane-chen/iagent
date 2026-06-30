package io.invest.iagent.tools.filing;

import io.invest.iagent.model.FinancialIndexValueDTO;
import io.invest.iagent.model.FinancialTrendAnalysisResult;
import io.invest.iagent.service.filing.FinancialTrendAnalysisService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FinancialTrendAnalysisToolTest {

    @Test
    void analyzesLastEightQuartersAndYoy() {
        FinancialTrendAnalysisService service = new FinancialTrendAnalysisService(null);
        List<FinancialIndexValueDTO> values = new ArrayList<>();
        for(int year = 2022; year <= 2025; year++){
            for(int quarter = 1; quarter <= 4; quarter++){
                values.add(value("Revenue", year, "Q" + quarter, year * 100 + quarter));
                values.add(value("CostOfRevenue", year, "Q" + quarter, year * 50 + quarter));
            }
        }

        FinancialTrendAnalysisResult result = service.analyzeRows("TESTX", List.of("Revenue", "CostOfRevenue"), 8, values);

        assertTrue(result.isSuccess());
        assertEquals(16, result.getRows().size());
        assertTrue(result.getRows().stream().allMatch(row -> row.getFiscalYear() >= 2024));
        assertEquals(new BigDecimal("100"), result.getRows().stream()
                .filter(row -> "Revenue".equals(row.getMetric()) && row.getFiscalYear() == 2025 && "Q1".equals(row.getFiscalPeriod()))
                .findFirst().orElseThrow().getYoyChange());
        assertTrue(result.getRows().stream().allMatch(row -> row.getSource() != null));
    }

    @Test
    void marksMissingPriorYearQuarter() {
        FinancialTrendAnalysisService service = new FinancialTrendAnalysisService(null);
        FinancialTrendAnalysisResult result = service.analyzeRows("TESTX", List.of("Revenue"), 1,
                List.of(value("Revenue", 2025, "Q2", 2000)));

        assertEquals(1, result.getRows().size());
        assertNull(result.getRows().get(0).getYoyChangePercent());
        assertEquals("missing prior-year same-quarter value", result.getRows().get(0).getMissingReason());
        assertFalse(result.getWarnings().isEmpty());
    }

    private FinancialIndexValueDTO value(String metric, int fiscalYear, String fiscalPeriod, int value) {
        return FinancialIndexValueDTO.builder()
                .ticker("TESTX")
                .tableType("10-Q")
                .index(metric)
                .value(String.valueOf(value))
                .currency("USD")
                .units("USD")
                .fiscalYear(fiscalYear)
                .fiscalPeriod(fiscalPeriod)
                .startDate(fiscalYear + "-01-01")
                .endDate(fiscalYear + "-03-31")
                .source("TEST_SOURCE")
                .build();
    }
}
