package io.invest.iagent.financial.service;

import com.alibaba.fastjson2.JSON;
import io.invest.iagent.financial.model.CompanyDO;
import io.invest.iagent.financial.model.MetricCatalog;
import io.invest.iagent.financial.model.MetricDef;
import io.invest.iagent.financial.model.MetricValueDO;
import io.invest.iagent.financial.model.PeriodType;
import io.invest.iagent.financial.repository.FinancialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FinancialQueryService#composition} 单元测试：
 * 利润表构成隐藏 EBITDA/Adjusted EBITDA、空行剪枝、所得税后插入税率行（所得税/税前利润）。
 */
class FinancialQueryServiceCompositionTest {

    private static final String TICKER = "00700";

    private FinancialRepository repository;
    private FinancialQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(FinancialRepository.class);
        FinancialIngestService ingestService = mock(FinancialIngestService.class);
        when(ingestService.backfillIfNeeded(anyString())).thenReturn(false);
        when(repository.findCompany(anyString())).thenReturn(
                CompanyDO.builder().ticker(TICKER).name("腾讯").market("HK").currency("HKD").build());

        MetricCatalog catalog = new MetricCatalog(incomeDefs());

        service = new FinancialQueryService();
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "metricCatalog", catalog);
        ReflectionTestUtils.setField(service, "ingestService", ingestService);
    }

    /** 利润表构成：EBITDA 类指标隐藏（即使有值）、全空行剪枝、税率行紧随所得税。 */
    @Test
    void composition_incomeStatement_hidesEbitdaPrunesEmptyAndAddsTaxRate() {
        List<MetricValueDO> rows = new ArrayList<>();
        // Q1：税前 280、所得税 42（税率 15%）；Q2：税前 340、所得税 51（税率 15%）
        rows.add(row("2025Q1", "REVENUE", "1000"));
        rows.add(row("2025Q2", "REVENUE", "1200"));
        rows.add(row("2025Q1", "COST_OF_REVENUE", "600"));
        rows.add(row("2025Q2", "COST_OF_REVENUE", "700"));
        rows.add(row("2025Q1", "OPERATING_INCOME", "300"));
        rows.add(row("2025Q2", "OPERATING_INCOME", "360"));
        rows.add(row("2025Q1", "PRETAX_INCOME", "280"));
        rows.add(row("2025Q2", "PRETAX_INCOME", "340"));
        rows.add(row("2025Q1", "INCOME_TAX", "42"));
        rows.add(row("2025Q2", "INCOME_TAX", "51"));
        rows.add(row("2025Q1", "NET_INCOME", "238"));
        rows.add(row("2025Q2", "NET_INCOME", "289"));
        // EBITDA 有值，但构成表应隐藏
        rows.add(row("2025Q1", "EBITDA", "999"));
        rows.add(row("2025Q2", "EBITDA", "999"));
        when(repository.queryMetrics(anyString(), any(), any())).thenReturn(rows);

        FinancialQueryService.CompositionResult result = service.composition(TICKER, "净利润", 8);

        assertThat(result.hasData()).isTrue();
        assertThat(result.periods()).containsExactly("2025Q2", "2025Q1");
        List<FinancialQueryService.TreeNode> flat = flatten(result.tree());
        List<String> codes = flat.stream().map(FinancialQueryService.TreeNode::code).toList();

        // EBITDA / Adjusted EBITDA 隐藏；EBIT 与归母净利润全空 → 剪枝
        assertThat(codes).doesNotContain("EBITDA", "ADJUSTED_EBITDA", "EBIT", "NET_INCOME_PARENT");
        // 营业总成本自身无值，但子节点营业成本有值 → 作为分层父节点保留
        assertThat(codes).contains("REVENUE", "TOTAL_OPERATING_COST", "COST_OF_REVENUE",
                "OPERATING_INCOME", "PRETAX_INCOME", "INCOME_TAX", "NET_INCOME");

        // 税率行紧随所得税行（顶层根节点顺序）
        List<String> rootCodes = result.tree().stream().map(FinancialQueryService.TreeNode::code).toList();
        int taxIdx = rootCodes.indexOf("INCOME_TAX");
        assertThat(taxIdx).isGreaterThanOrEqualTo(0);
        FinancialQueryService.TreeNode taxRate = result.tree().get(taxIdx + 1);
        assertThat(taxRate.code()).isEqualTo("EFFECTIVE_TAX_RATE");
        assertThat(taxRate.name()).isEqualTo("税率");
        assertThat(taxRate.unit()).isEqualTo("percent");
        assertThat(taxRate.derived()).isTrue();
        assertThat(taxRate.children()).isEmpty();
        // 列顺序与 periods 对齐：Q2=51/340=15.0%，Q1=42/280=15.0%
        assertThat(taxRate.cells()).hasSize(2);
        assertThat(taxRate.cells().get(0).value()).isEqualByComparingTo("15.0");
        assertThat(taxRate.cells().get(1).value()).isEqualByComparingTo("15.0");
        assertThat(taxRate.cells()).allSatisfy(c -> assertThat(c.ratio()).isNull());

        // 目标指标行高亮
        assertThat(flat).anySatisfy(n -> {
            assertThat(n.code()).isEqualTo("NET_INCOME");
            assertThat(n.target()).isTrue();
        });
    }

    /** 所得税/税前利润缺失导致税率无法计算时，不插入税率行。 */
    @Test
    void composition_noTaxData_taxRateRowAbsent() {
        List<MetricValueDO> rows = new ArrayList<>();
        rows.add(row("2025Q1", "REVENUE", "1000"));
        rows.add(row("2025Q2", "REVENUE", "1200"));
        rows.add(row("2025Q1", "OPERATING_INCOME", "300"));
        rows.add(row("2025Q2", "OPERATING_INCOME", "360"));
        when(repository.queryMetrics(anyString(), any(), any())).thenReturn(rows);

        FinancialQueryService.CompositionResult result = service.composition(TICKER, "营业利润", 8);

        assertThat(result.hasData()).isTrue();
        List<String> codes = flatten(result.tree()).stream()
                .map(FinancialQueryService.TreeNode::code).toList();
        assertThat(codes).doesNotContain("EFFECTIVE_TAX_RATE", "INCOME_TAX", "PRETAX_INCOME");
        assertThat(codes).contains("REVENUE", "OPERATING_INCOME");
    }

    /** FCF（现金流量表）构成：RAG 提取的经调整资本开支 NON_GAAP_CAPEX 作为投资活动子节点展示（用真实指标目录）。 */
    @Test
    void composition_fcf_includesNonGaapCapexUnderInvesting() throws Exception {
        MetricCatalog realCatalog = new MetricCatalog(JSON.parseArray(
                JSON.toJSONString(loadYaml("financial/metric-catalog.yml").get("metrics")),
                MetricDef.class));
        FinancialRepository repo = mock(FinancialRepository.class);
        FinancialIngestService ingest = mock(FinancialIngestService.class);
        when(ingest.backfillIfNeeded(anyString())).thenReturn(false);
        when(repo.findCompany(anyString())).thenReturn(
                CompanyDO.builder().ticker(TICKER).market("HK").currency("CNY").build());
        FinancialQueryService svc = new FinancialQueryService();
        ReflectionTestUtils.setField(svc, "repository", repo);
        ReflectionTestUtils.setField(svc, "metricCatalog", realCatalog);
        ReflectionTestUtils.setField(svc, "ingestService", ingest);

        List<MetricValueDO> rows = new ArrayList<>();
        rows.add(row("2026Q1", "REVENUE", "900000"));
        rows.add(row("2025Q4", "REVENUE", "1700000"));
        rows.add(row("2026Q1", "OPERATING_CF", "101351"));
        rows.add(row("2025Q4", "OPERATING_CF", "300000"));
        // GAAP 资本开支仅密集报有值；Q1 稀疏报缺失
        rows.add(row("2025Q4", "CAPEX", "80000"));
        // RAG 提取的经调整资本开支（自由现金流实际采用的口径）
        rows.add(MetricValueDO.builder().ticker(TICKER).fiscalPeriod("2026Q1")
                .periodType(PeriodType.SINGLE_Q.name()).metricCode("NON_GAAP_CAPEX")
                .value(new BigDecimal("30000")).currency("CNY").source("RAG").confidence(80).build());
        when(repo.queryMetrics(anyString(), any(), any())).thenReturn(rows);

        FinancialQueryService.CompositionResult result = svc.composition(TICKER, "自由现金流", 8);
        assertThat(result.hasData()).isTrue();

        FinancialQueryService.TreeNode investing = result.tree().stream()
                .filter(n -> "INVESTING_CF".equals(n.code())).findFirst().orElseThrow();
        // 经调整资本开支与 GAAP 资本开支并列于投资活动现金流节点下
        assertThat(investing.children()).extracting(FinancialQueryService.TreeNode::code)
                .contains("CAPEX", "NON_GAAP_CAPEX");
        FinancialQueryService.TreeNode ng = investing.children().stream()
                .filter(n -> "NON_GAAP_CAPEX".equals(n.code())).findFirst().orElseThrow();
        assertThat(ng.name()).isEqualTo("经调整资本开支");
        // 列与 periods 对齐：最新列 2026Q1 = 30000，次列 2025Q4 无值
        assertThat(ng.cells().get(0).value()).isEqualByComparingTo("30000");
        assertThat(ng.cells().get(1).value()).isNull();
    }

    // ---------- helpers ----------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(String path) throws Exception {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new Yaml().load(in);
        }
    }

    private static List<FinancialQueryService.TreeNode> flatten(List<FinancialQueryService.TreeNode> nodes) {
        List<FinancialQueryService.TreeNode> out = new ArrayList<>();
        for (FinancialQueryService.TreeNode n : nodes) {
            out.add(n);
            out.addAll(flatten(n.children()));
        }
        return out;
    }

    private static MetricValueDO row(String period, String code, String value) {
        return MetricValueDO.builder().ticker(TICKER).fiscalPeriod(period)
                .periodType(PeriodType.SINGLE_Q.name()).metricCode(code)
                .value(new BigDecimal(value)).currency("HKD").source("FUTU_API").build();
    }

    /** 最小利润表目录（含隐藏的 EBITDA 类指标与无子值的剪枝对象）。 */
    private static List<MetricDef> incomeDefs() {
        List<MetricDef> defs = new ArrayList<>();
        defs.add(def("REVENUE", "营业总收入", null, 10, "FLOW"));
        defs.add(def("TOTAL_OPERATING_COST", "营业总成本", null, 20, "FLOW"));
        defs.add(def("COST_OF_REVENUE", "营业成本", "TOTAL_OPERATING_COST", 21, "FLOW"));
        defs.add(def("OPERATING_INCOME", "营业利润", null, 40, "FLOW"));
        defs.add(def("EBITDA", "EBITDA", null, 41, "FLOW"));
        defs.add(def("ADJUSTED_EBITDA", "Adjusted EBITDA", null, 42, "FLOW"));
        defs.add(def("EBIT", "息税前利润", null, 43, "FLOW"));
        defs.add(def("PRETAX_INCOME", "税前利润", null, 60, "FLOW"));
        defs.add(def("INCOME_TAX", "所得税", null, 70, "FLOW"));
        defs.add(def("NET_INCOME", "净利润", null, 80, "FLOW"));
        defs.add(def("NET_INCOME_PARENT", "归母净利润", "NET_INCOME", 81, "FLOW"));
        return defs;
    }

    private static MetricDef def(String code, String name, String parent, int order, String valueType) {
        MetricDef d = new MetricDef();
        d.setCode(code);
        d.setNameCn(name);
        d.setNameEn(name);
        d.setStatement("income");
        d.setParent(parent);
        d.setSortOrder(order);
        d.setValueType(valueType);
        d.setUnit("million");
        return d;
    }
}
