package io.invest.iagent.financial.ingest;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import io.invest.iagent.financial.config.FutuFieldMapping;
import io.invest.iagent.financial.model.MetricCatalog;
import io.invest.iagent.financial.model.MetricValueDO;
import io.invest.iagent.financial.model.PeriodType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FutuStatementIngestor} 纯解析逻辑测试：合成港股累计口径数据，
 * 验证期间规范化、累计差分单季、派生 FCF、资产负债表时点数不被差分。
 */
class FutuStatementIngestorTest {

    private FutuStatementIngestor ingestor;

    @BeforeEach
    void setUp() throws Exception {
        MetricCatalog catalog = new MetricCatalog(JSON.parseArray(
                JSON.toJSONString(loadYaml("financial/metric-catalog.yml").get("metrics")),
                io.invest.iagent.financial.model.MetricDef.class));
        FutuFieldMapping mapping = JSON.parseObject(
                JSON.toJSONString(loadYaml("financial/futu-field-mapping.yml")),
                FutuFieldMapping.class);

        ingestor = new FutuStatementIngestor();
        ReflectionTestUtils.setField(ingestor, "metricCatalog", catalog);
        ReflectionTestUtils.setField(ingestor, "fieldMapping", mapping);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(String path) throws Exception {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new Yaml().load(in);
        }
    }

    /** 构造港股累计口径合成数据（FY2025 四个报告期）。 */
    private JSONObject hkRoot() {
        // 累计值：收入 100/300/600/1000；经营现金流 25/80/180/300；
        // CapEx = 5071 + 5073：(10+5)/(20+10)/(40+20)/(80+40) = 15/30/60/120
        // 资产（时点数）：1000/1200/1400/1600
        JSONObject root = new JSONObject();
        root.put("code", "HK.00700");
        root.put("ticker", "00700");
        root.put("currency", "HKD");
        root.put("cumulative", true);

        JSONObject statements = new JSONObject();
        statements.put("income", stmt(
                report("2025Q1", "2025-03-31", 2025, item(5001, 100), item(5010, 40), item(5034, 20), item(5045, 15)),
                report("2025Q6", "2025-06-30", 2025, item(5001, 300), item(5010, 120), item(5034, 60), item(5045, 45)),
                report("2025Q9", "2025-09-30", 2025, item(5001, 600), item(5010, 240), item(5034, 120), item(5045, 90)),
                report("FY2025", "2025-12-31", 2025, item(5001, 1000), item(5010, 400), item(5034, 200), item(5045, 150))));
        statements.put("cashflow", stmt(
                report("2025Q1", "2025-03-31", 2025, item(5058, 25), item(5071, 10), item(5073, 5), item(5100, 500)),
                report("2025Q6", "2025-06-30", 2025, item(5058, 80), item(5071, 20), item(5073, 10), item(5100, 600)),
                report("2025Q9", "2025-09-30", 2025, item(5058, 180), item(5071, 40), item(5073, 20), item(5100, 700)),
                report("FY2025", "2025-12-31", 2025, item(5058, 300), item(5071, 80), item(5073, 40), item(5100, 800))));
        statements.put("balance", stmt(
                report("2025Q1", "2025-03-31", 2025, item(5001, 1000), item(5110, 500)),
                report("2025Q6", "2025-06-30", 2025, item(5001, 1200), item(5110, 600)),
                report("2025Q9", "2025-09-30", 2025, item(5001, 1400), item(5110, 700)),
                report("FY2025", "2025-12-31", 2025, item(5001, 1600), item(5110, 800))));
        root.put("statements", statements);
        root.put("errors", new JSONObject());
        return root;
    }

    @Test
    void hkCumulative_isDifferencedToSingleQuarters() {
        FutuStatementIngestor.IngestResult r = ingestor.processRoot(hkRoot());

        assertEquals("00700", r.company().getTicker());
        assertEquals("HK", r.company().getMarket());
        assertEquals(12, r.company().getFyEndMonth(), "年报截止 12 月应推断出 fyEndMonth=12");

        // 期间标签：Q6/H1 -> 2025Q2，Q9/9M -> 2025Q3，FY -> FY2025
        List<String> periods = r.values().stream().map(MetricValueDO::getFiscalPeriod).distinct().toList();
        assertTrue(periods.contains("2025Q2"), "Q6 应规范化为 2025Q2，实际: " + periods);
        assertTrue(periods.contains("2025Q3"), "Q9 应规范化为 2025Q3，实际: " + periods);
        assertTrue(periods.contains("FY2025"));

        // 单季收入差分：100 / 200 / 300 / 400
        assertValue(r, "REVENUE", "2025Q1", PeriodType.SINGLE_Q, "100");
        assertValue(r, "REVENUE", "2025Q2", PeriodType.SINGLE_Q, "200");
        assertValue(r, "REVENUE", "2025Q3", PeriodType.SINGLE_Q, "300");
        assertValue(r, "REVENUE", "2025Q4", PeriodType.SINGLE_Q, "400");

        // 累计行保留：H1 收入累计 300
        assertValue(r, "REVENUE", "2025Q2", PeriodType.CUMULATIVE, "300");
        assertValue(r, "REVENUE", "FY2025", PeriodType.FY, "1000");

        // 单季经营现金流差分：25 / 55 / 100 / 120
        assertValue(r, "OPERATING_CF", "2025Q2", PeriodType.SINGLE_Q, "55");
        assertValue(r, "OPERATING_CF", "2025Q4", PeriodType.SINGLE_Q, "120");

        // CapEx 多字段求和后差分：Q1=15, Q2=30-15=15, Q3=60-30=30, Q4=120-60=60
        assertValue(r, "CAPEX", "2025Q1", PeriodType.SINGLE_Q, "15");
        assertValue(r, "CAPEX", "2025Q2", PeriodType.SINGLE_Q, "15");
        assertValue(r, "CAPEX", "2025Q3", PeriodType.SINGLE_Q, "30");
        assertValue(r, "CAPEX", "2025Q4", PeriodType.SINGLE_Q, "60");

        // 派生 FCF = OCF - CapEx（单季）：Q1=10, Q4=60
        assertValue(r, "FREE_CASH_FLOW", "2025Q1", PeriodType.SINGLE_Q, "10");
        assertValue(r, "FREE_CASH_FLOW", "2025Q4", PeriodType.SINGLE_Q, "60");

        // 资产负债表为时点数，不参与差分：Q3 单季行即快照 1400
        assertValue(r, "TOTAL_ASSETS", "2025Q3", PeriodType.SINGLE_Q, "1400");
        assertValue(r, "TOTAL_ASSETS", "FY2025", PeriodType.FY, "1600");
        assertNull(find(r, "TOTAL_ASSETS", "2025Q3", PeriodType.CUMULATIVE),
                "资产负债表不应产生 CUMULATIVE 行");
    }

    @Test
    void usSingleQuarter_keptAsIs() {
        JSONObject root = new JSONObject();
        root.put("code", "US.AAPL");
        root.put("ticker", "AAPL");
        root.put("currency", "USD");
        root.put("cumulative", false);
        JSONObject statements = new JSONObject();
        // 美股单季：Q1 截止 12 月（财年偏移），FY 截止 9 月
        statements.put("income", stmt(
                report("2025Q1", "2024-12-31", 2025, item(8002, 100), item(8037, 20)),
                report("FY2025", "2025-09-30", 2025, item(8002, 400), item(8037, 80))));
        statements.put("balance", stmt(
                report("FY2025", "2025-09-30", 2025, item(8001, 2000))));
        statements.put("cashflow", stmt(
                report("2025Q1", "2024-12-31", 2025, item(8015, 50), item(8046, 10))));
        root.put("statements", statements);
        root.put("errors", new JSONObject());

        FutuStatementIngestor.IngestResult r = ingestor.processRoot(root);

        assertEquals("US", r.company().getMarket());
        assertEquals(9, r.company().getFyEndMonth(), "年报截止 9 月应推断 fyEndMonth=9");
        // 12 月季度在 FYE=9 下属于财年 Q1
        assertValue(r, "REVENUE", "2025Q1", PeriodType.SINGLE_Q, "100");
        assertValue(r, "REVENUE", "FY2025", PeriodType.FY, "400");
        assertValue(r, "FREE_CASH_FLOW", "2025Q1", PeriodType.SINGLE_Q, "40");
    }

    // =========================================================
    //  辅助
    // =========================================================

    private static JSONObject stmt(JSONObject... reports) {
        JSONObject stmt = new JSONObject();
        JSONArray arr = new JSONArray();
        arr.addAll(List.of(reports));
        stmt.put("reports", arr);
        return stmt;
    }

    private static JSONObject report(String period, String end, int fy, JSONObject... items) {
        JSONObject r = new JSONObject();
        r.put("period", period);
        r.put("periodEnd", end);
        r.put("fiscalYear", fy);
        JSONArray arr = new JSONArray();
        arr.addAll(List.of(items));
        r.put("items", arr);
        return r;
    }

    private static JSONObject item(int fieldId, double value) {
        JSONObject i = new JSONObject();
        i.put("fieldId", fieldId);
        i.put("value", value);
        return i;
    }

    private void assertValue(FutuStatementIngestor.IngestResult r, String metric, String period,
                             PeriodType type, String expected) {
        MetricValueDO v = find(r, metric, period, type);
        assertNotNull(v, () -> "缺少指标行: " + metric + " " + period + " " + type);
        assertEquals(0, new BigDecimal(expected).compareTo(v.getValue()),
                () -> metric + " " + period + " " + type + " 期望 " + expected + " 实际 " + v.getValue());
    }

    private static MetricValueDO find(FutuStatementIngestor.IngestResult r, String metric,
                                      String period, PeriodType type) {
        for (MetricValueDO v : r.values()) {
            if (v.getMetricCode().equals(metric) && v.getFiscalPeriod().equals(period)
                    && type.name().equals(v.getPeriodType())) {
                return v;
            }
        }
        return null;
    }
}
