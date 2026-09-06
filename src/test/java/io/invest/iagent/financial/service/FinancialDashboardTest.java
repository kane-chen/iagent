package io.invest.iagent.financial.service;

import io.invest.iagent.financial.ingest.SegmentIngestor;
import io.invest.iagent.financial.model.CompanyDO;
import io.invest.iagent.financial.model.MetricCatalog;
import io.invest.iagent.financial.model.MetricDef;
import io.invest.iagent.financial.model.MetricValueDO;
import io.invest.iagent.financial.repository.FinancialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FinancialQueryService#dashboard(String)} 期间口径自适应测试。
 *
 * <p>仅披露半年报/年报的港股公司（如 09992 泡泡玛特）库中没有单季流量值：利润表/现金流量表
 * 只存 CUMULATIVE(H1) 与 FY，资产负债表（STOCK 时点值）半年报存 SINGLE_Q、年报存 FY。
 * 旧逻辑仪表盘硬编码 SINGLE_Q，导致净利润/经营利润/自由现金流卡片为空。这里用合成数据验证
 * 仪表盘能按公司实际披露口径回退到 CUMULATIVE，并照常展示资产负债表时点值。
 */
class FinancialDashboardTest {

    private FinancialQueryService service;
    private FinancialRepository repository;
    private final List<MetricValueDO> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new FinancialQueryService();
        repository = mock(FinancialRepository.class);
        FinancialIngestService ingestService = mock(FinancialIngestService.class);
        SegmentIngestor segmentIngestor = mock(SegmentIngestor.class);

        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "metricCatalog", buildCatalog());
        ReflectionTestUtils.setField(service, "ingestService", ingestService);
        ReflectionTestUtils.setField(service, "segmentIngestor", segmentIngestor);

        when(ingestService.backfillIfNeeded(anyString())).thenReturn(false);
        when(repository.findCompany(anyString())).thenReturn(
                CompanyDO.builder().ticker("09992").name("泡泡玛特").market("HKEX").currency("RMB").fyEndMonth(12).build());
        when(repository.queryMetrics(anyString(), any(), any())).thenAnswer(inv -> new ArrayList<>(rows));
        // 无分部数据：返回空目录且 ingest 不产出，避免触发本地 Python 提取
        when(repository.findSegments(anyString())).thenReturn(List.of());
        when(segmentIngestor.ingest(anyString()))
                .thenReturn(new SegmentIngestor.SegmentResult(false, 0, 0, List.of()));
    }

    /** 半年报/年报公司：流量只有 CUMULATIVE(H1) 与 FY，STOCK 半年报为 SINGLE_Q、年报为 FY。 */
    @Test
    void dashboard_semiannualReporter_fallsBackToCumulative() {
        // 利润表/现金流量表（FLOW）：无任何 SINGLE_Q 行
        rows.add(flow("NET_INCOME", "CUMULATIVE", "2025Q2", 5000));   // H1 2025
        rows.add(flow("NET_INCOME", "CUMULATIVE", "2026Q2", 6000));   // H1 2026（最新流量期）
        rows.add(flow("NET_INCOME", "FY", "FY2025", 12000));          // FY2025 年报
        rows.add(flow("OPERATING_INCOME", "CUMULATIVE", "2025Q2", 800));
        rows.add(flow("OPERATING_INCOME", "CUMULATIVE", "2026Q2", 1000));
        rows.add(flow("OPERATING_CF", "CUMULATIVE", "2025Q2", 3000));
        rows.add(flow("OPERATING_CF", "CUMULATIVE", "2026Q2", 4000));
        rows.add(flow("CAPEX", "CUMULATIVE", "2025Q2", 500));
        rows.add(flow("CAPEX", "CUMULATIVE", "2026Q2", 600));
        // 资产负债表（STOCK 时点值）：半年报 SINGLE_Q、年报 FY
        rows.add(stock("TOTAL_ASSETS", "SINGLE_Q", "2025Q2", 50000)); // 2025-06 时点
        rows.add(stock("TOTAL_ASSETS", "SINGLE_Q", "2026Q2", 60000)); // 2026-06 时点（最新）
        rows.add(stock("TOTAL_ASSETS", "FY", "FY2025", 55000));       // 2025-12 时点

        FinancialQueryService.DashboardResult result = service.dashboard("09992");

        assertTrue(result.hasData(), "半年报公司仪表盘应有数据");
        assertEquals("2026Q2", result.period(), "最新期间应为 H1 2026（2026Q2）");

        // 流量卡片取 CUMULATIVE(H1) 值，不再为空
        assertEquals(0, new BigDecimal("6000").compareTo(card(result, "net_income").value()),
                "净利润卡片应取 H1 2026 累计值 6000");
        assertEquals(0, new BigDecimal("1000").compareTo(card(result, "operating_income").value()),
                "经营利润卡片应有值");
        // 自由现金流 = H1 经营现金流 4000 − H1 资本开支 600 = 3400
        assertEquals(0, new BigDecimal("3400").compareTo(card(result, "free_cash_flow").value()),
                "自由现金流应按 H1 累计值派生为 3400");
        // 资产负债表卡片：2026-06 时点资产 60000（STOCK 不受流量口径过滤影响）
        FinancialQueryService.MetricCard bs = card(result, "balance_sheet");
        assertNotNull(bs.items());
        BigDecimal assets = bs.items().stream()
                .filter(p -> "TOTAL_ASSETS".equals(p.code())).findFirst()
                .map(FinancialQueryService.MetricPoint::value).orElse(null);
        assertEquals(0, new BigDecimal("60000").compareTo(assets), "资产负债表应展示 2026-06 时点资产");
    }

    /** 趋势图：半年报/年报公司默认 SINGLE_Q 时合并展示 H1(CUMULATIVE) 与 FY(年报) 数据点。 */
    @Test
    void trend_semiannualReporter_includesHalfYearAndAnnual() {
        seedSemiannualRows();

        FinancialQueryService.TrendResult trend = service.trend("09992", "NET_INCOME", 16, "SINGLE_Q");

        assertTrue(trend.hasData(), "半年报公司趋势应有数据");
        List<String> periods = trend.points().stream().map(FinancialQueryService.TrendPoint::period).toList();
        assertTrue(periods.contains("2026Q2"), "应含 H1 2026（2026Q2）: " + periods);
        assertTrue(periods.contains("FY2025"), "应含 2025 年报（FY2025）: " + periods);
        // 最新点为 H1 2026，值取累计 6000；同比对齐去年 H1（6000-5000)/5000=20%
        FinancialQueryService.TrendPoint latest = trend.points().get(trend.points().size() - 1);
        assertEquals("2026Q2", latest.period());
        assertEquals(0, new BigDecimal("6000").compareTo(latest.value()));
        assertEquals(0, new BigDecimal("20.0").compareTo(latest.yoy()), "H1 同比应对齐去年 H1");
    }

    /** 构成表：半年报/年报公司合并 H1 与 FY 列，利润表节点在两类期间上都有值。 */
    @Test
    void composition_semiannualReporter_includesHalfYearAndAnnual() {
        seedSemiannualRows();

        FinancialQueryService.CompositionResult comp = service.composition("09992", "NET_INCOME", 8);

        assertTrue(comp.hasData(), "半年报公司构成表应有数据");
        assertTrue(comp.periods().contains("2026Q2"), "构成列应含 H1 2026: " + comp.periods());
        assertTrue(comp.periods().contains("FY2025"), "构成列应含 2025 年报: " + comp.periods());
    }

    /** 趋势图：季度披露公司仍只取单季值，不混入 H1 累计/年报。 */
    @Test
    void trend_quarterlyReporter_prefersSingleQuarter() {
        // 同一期 2026Q2：SINGLE_Q 单季 7000，CUMULATIVE H1 累计 13000
        rows.add(flow("NET_INCOME", "SINGLE_Q", "2026Q2", 7000));
        rows.add(flow("NET_INCOME", "CUMULATIVE", "2026Q2", 13000));
        rows.add(flow("NET_INCOME", "SINGLE_Q", "2025Q2", 6500));
        rows.add(flow("NET_INCOME", "CUMULATIVE", "2025Q2", 12000));

        FinancialQueryService.TrendResult trend = service.trend("09992", "NET_INCOME", 16, "SINGLE_Q");
        assertTrue(trend.hasData());
        FinancialQueryService.TrendPoint latest = trend.points().get(trend.points().size() - 1);
        assertEquals("2026Q2", latest.period());
        assertEquals(0, new BigDecimal("7000").compareTo(latest.value()),
                "季度披露公司趋势应取单季值 7000，而非 H1 累计 13000");
    }

    /** 半年报/年报公司典型数据：流量只有 CUMULATIVE(H1) 与 FY，STOCK 中报 SINGLE_Q、年报 FY。 */
    private void seedSemiannualRows() {
        rows.add(flow("REVENUE", "CUMULATIVE", "2025Q2", 20000));
        rows.add(flow("REVENUE", "CUMULATIVE", "2026Q2", 24000));
        rows.add(flow("REVENUE", "FY", "FY2025", 50000));
        rows.add(flow("NET_INCOME", "CUMULATIVE", "2025Q2", 5000));
        rows.add(flow("NET_INCOME", "CUMULATIVE", "2026Q2", 6000));
        rows.add(flow("NET_INCOME", "FY", "FY2025", 12000));
        rows.add(flow("OPERATING_INCOME", "CUMULATIVE", "2025Q2", 800));
        rows.add(flow("OPERATING_INCOME", "CUMULATIVE", "2026Q2", 1000));
        rows.add(flow("OPERATING_CF", "CUMULATIVE", "2025Q2", 3000));
        rows.add(flow("OPERATING_CF", "CUMULATIVE", "2026Q2", 4000));
        rows.add(flow("CAPEX", "CUMULATIVE", "2025Q2", 500));
        rows.add(flow("CAPEX", "CUMULATIVE", "2026Q2", 600));
        rows.add(stock("TOTAL_ASSETS", "SINGLE_Q", "2025Q2", 50000));
        rows.add(stock("TOTAL_ASSETS", "SINGLE_Q", "2026Q2", 60000));
        rows.add(stock("TOTAL_ASSETS", "FY", "FY2025", 55000));
    }

    /** 季度披露公司：SINGLE_Q 与 CUMULATIVE 同期时优先 SINGLE_Q，卡片取单季值。 */
    @Test
    void dashboard_quarterlyReporter_prefersSingleQuarter() {
        // 同一期 2026Q2：SINGLE_Q 单季 7000，CUMULATIVE H1 累计 13000
        rows.add(flow("NET_INCOME", "SINGLE_Q", "2026Q2", 7000));
        rows.add(flow("NET_INCOME", "CUMULATIVE", "2026Q2", 13000));
        rows.add(flow("NET_INCOME", "SINGLE_Q", "2025Q2", 6500));
        rows.add(flow("NET_INCOME", "CUMULATIVE", "2025Q2", 12000));

        FinancialQueryService.DashboardResult result = service.dashboard("09992");

        assertTrue(result.hasData());
        assertEquals("2026Q2", result.period());
        assertEquals(0, new BigDecimal("7000").compareTo(card(result, "net_income").value()),
                "季度披露公司净利润卡片应取单季值 7000，而非 H1 累计 13000");
    }

    private static FinancialQueryService.MetricCard card(FinancialQueryService.DashboardResult r, String key) {
        return r.cards().stream().filter(c -> key.equals(c.key())).findFirst().orElse(null);
    }

    private static MetricValueDO flow(String code, String periodType, String period, double value) {
        return MetricValueDO.builder().ticker("09992").metricCode(code)
                .periodType(periodType).fiscalPeriod(period).value(BigDecimal.valueOf(value))
                .currency("RMB").unit("million").source("FUTU_API").build();
    }

    private static MetricValueDO stock(String code, String periodType, String period, double value) {
        return flow(code, periodType, period, value);
    }

    /** 构造覆盖仪表盘所需指标的最小目录。 */
    private static MetricCatalog buildCatalog() {
        List<MetricDef> defs = new ArrayList<>();
        defs.add(def("REVENUE", "income", "FLOW", 10));
        defs.add(def("OPERATING_INCOME", "income", "FLOW", 20));
        defs.add(def("NET_INCOME", "income", "FLOW", 30));
        defs.add(def("OPERATING_CF", "cashflow", "FLOW", 40));
        defs.add(def("CAPEX", "cashflow", "FLOW", 41));
        defs.add(def("FREE_CASH_FLOW", "cashflow", "FLOW", 42));
        defs.add(def("TOTAL_ASSETS", "balance", "STOCK", 50));
        defs.add(def("TOTAL_LIABILITIES", "balance", "STOCK", 51));
        defs.add(def("TOTAL_EQUITY", "balance", "STOCK", 52));
        return new MetricCatalog(defs);
    }

    private static MetricDef def(String code, String statement, String valueType, int sortOrder) {
        MetricDef d = new MetricDef();
        d.setCode(code);
        d.setNameCn(code);
        d.setStatement(statement);
        d.setValueType(valueType);
        d.setSortOrder(sortOrder);
        d.setUnit("million");
        return d;
    }
}
