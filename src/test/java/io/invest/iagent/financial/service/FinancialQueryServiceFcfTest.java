package io.invest.iagent.financial.service;

import io.invest.iagent.financial.model.CompanyDO;
import io.invest.iagent.financial.model.MetricCatalog;
import io.invest.iagent.financial.model.MetricDef;
import io.invest.iagent.financial.model.MetricValueDO;
import io.invest.iagent.financial.model.PeriodType;
import io.invest.iagent.financial.repository.FinancialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FinancialQueryService} 自由现金流派生测试：
 * FCF = 经营现金流 OCF − 资本开支，资本开支优先取 Non-GAAP 经调整口径（NON_GAAP_CAPEX），缺失回退 GAAP CAPEX。
 */
class FinancialQueryServiceFcfTest {

    private static final String TICKER = "00700";
    private static final String PERIOD = "2025Q1";

    private FinancialRepository repository;
    private FinancialQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(FinancialRepository.class);
        FinancialIngestService ingestService = mock(FinancialIngestService.class);
        when(ingestService.backfillIfNeeded(anyString())).thenReturn(false);
        when(repository.findCompany(anyString())).thenReturn(
                CompanyDO.builder().ticker(TICKER).name("腾讯").market("HK").currency("HKD").build());

        service = new FinancialQueryService();
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "metricCatalog", new MetricCatalog(defs()));
        ReflectionTestUtils.setField(service, "ingestService", ingestService);
    }

    /** Non-GAAP 资本开支存在时，FCF = OCF − NON_GAAP_CAPEX（即使 GAAP CAPEX 不同）。 */
    @Test
    void dashboard_fcf_usesNonGaapCapexWhenPresent() {
        List<MetricValueDO> rows = new ArrayList<>();
        rows.add(row("OPERATING_CF", "100"));
        rows.add(row("CAPEX", "60"));
        rows.add(row("NON_GAAP_CAPEX", "40"));
        when(repository.queryMetrics(anyString(), any(), any())).thenReturn(rows);

        BigDecimal fcf = fcfCardValue();

        // 100 - 40 = 60（而非 100 - 60 = 40）
        assertThat(fcf).isEqualByComparingTo("60");
    }

    /** Non-GAAP 资本开支缺失时，FCF 回退为 OCF − GAAP CAPEX。 */
    @Test
    void dashboard_fcf_fallsBackToGaapCapex() {
        List<MetricValueDO> rows = new ArrayList<>();
        rows.add(row("OPERATING_CF", "100"));
        rows.add(row("CAPEX", "60"));
        when(repository.queryMetrics(anyString(), any(), any())).thenReturn(rows);

        assertThat(fcfCardValue()).isEqualByComparingTo("40");
    }

    /** 库中已存有按 GAAP 口径派生的 FCF 行时，查询时按 Non-GAAP 口径重算覆盖。 */
    @Test
    void dashboard_fcf_overridesStoredGaapDerivedRow() {
        List<MetricValueDO> rows = new ArrayList<>();
        rows.add(row("OPERATING_CF", "100"));
        rows.add(row("CAPEX", "60"));
        rows.add(row("NON_GAAP_CAPEX", "40"));
        // 采集时按 GAAP 派生并存储的旧 FCF = 100 - 60 = 40
        rows.add(MetricValueDO.builder().ticker(TICKER).fiscalPeriod(PERIOD)
                .periodType(PeriodType.SINGLE_Q.name()).metricCode("FREE_CASH_FLOW")
                .value(new BigDecimal("40")).source("DERIVED").build());
        when(repository.queryMetrics(anyString(), any(), any())).thenReturn(rows);

        assertThat(fcfCardValue()).isEqualByComparingTo("60");
    }

    /** OCF 与两种资本开支都缺失时，FCF 卡片无值。 */
    @Test
    void dashboard_fcf_missingComponents_showsNull() {
        when(repository.queryMetrics(anyString(), any(), any()))
                .thenReturn(new ArrayList<>(List.of(row("NET_INCOME", "238"))));

        assertThat(fcfCardValue()).isNull();
    }

    // ---------- helpers ----------

    private BigDecimal fcfCardValue() {
        FinancialQueryService.DashboardResult result = service.dashboard(TICKER);
        assertThat(result.hasData()).isTrue();
        return result.cards().stream()
                .filter(c -> "free_cash_flow".equals(c.key()))
                .findFirst().orElseThrow()
                .value();
    }

    private static MetricValueDO row(String code, String value) {
        return MetricValueDO.builder().ticker(TICKER).fiscalPeriod(PERIOD)
                .periodType(PeriodType.SINGLE_Q.name()).metricCode(code)
                .value(new BigDecimal(value)).currency("HKD")
                .source("NON_GAAP_CAPEX".equals(code) ? "RAG" : "FUTU_API").build();
    }

    /** 最小指标目录（卡片与 FCF 派生所需编码）。 */
    private static List<MetricDef> defs() {
        List<MetricDef> defs = new ArrayList<>();
        defs.add(def("NET_INCOME", "净利润", "income", 80));
        defs.add(def("OPERATING_INCOME", "营业利润", "income", 40));
        defs.add(def("FREE_CASH_FLOW", "自由现金流", "cashflow", 450));
        defs.add(def("TOTAL_ASSETS", "资产总计", "balance", 100));
        defs.add(def("TOTAL_LIABILITIES", "负债总计", "balance", 200));
        defs.add(def("TOTAL_EQUITY", "股东权益合计", "balance", 300));
        return defs;
    }

    private static MetricDef def(String code, String name, String statement, int order) {
        MetricDef d = new MetricDef();
        d.setCode(code);
        d.setNameCn(name);
        d.setNameEn(name);
        d.setStatement(statement);
        d.setSortOrder(order);
        d.setValueType("FLOW");
        d.setUnit("million");
        return d;
    }
}
