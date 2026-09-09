package io.invest.iagent.financial.ingest;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import io.invest.iagent.financial.config.prop.FutuFieldMapping;
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
        // 累计值：收入 100/300/600/1000；经营现金流 25/80/180/300（现金流量表字段 5001，与利润表 5001=收入 同 id 不同报表）；
        // 投资活动净额 5069 带符号：-20/-60/-120/-200；
        // CapEx = 5071 + 5073，真实列报为负数（流出）：(-10-5)/(-20-10)/(-40-20)/(-80-40)，入库取绝对值 15/30/60/120
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
                report("2025Q1", "2025-03-31", 2025, item(5001, 25), item(5069, -20), item(5071, -10), item(5073, -5), item(5100, 500)),
                report("2025Q6", "2025-06-30", 2025, item(5001, 80), item(5069, -60), item(5071, -20), item(5073, -10), item(5100, 600)),
                report("2025Q9", "2025-09-30", 2025, item(5001, 180), item(5069, -120), item(5071, -40), item(5073, -20), item(5100, 700)),
                report("FY2025", "2025-12-31", 2025, item(5001, 300), item(5069, -200), item(5071, -80), item(5073, -40), item(5100, 800))));
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

        // 单季经营现金流差分（取现金流量表字段 5001）：25 / 55 / 100 / 120
        assertValue(r, "OPERATING_CF", "2025Q1", PeriodType.SINGLE_Q, "25");
        assertValue(r, "OPERATING_CF", "2025Q2", PeriodType.SINGLE_Q, "55");
        assertValue(r, "OPERATING_CF", "2025Q4", PeriodType.SINGLE_Q, "120");

        // 投资活动净额（5069，带符号）差分：Q3 单季 = -120 - (-60) = -60
        assertValue(r, "INVESTING_CF", "2025Q3", PeriodType.SINGLE_Q, "-60");

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
    void usFiscalYearShift_labelledByCalendarYear() {
        JSONObject root = new JSONObject();
        root.put("code", "US.AAPL");
        root.put("ticker", "AAPL");
        root.put("currency", "USD");
        root.put("cumulative", false);
        JSONObject statements = new JSONObject();
        // 美股单季：财年 Q1 截止 12 月（FYE=9），年报截止 9 月
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
        // 期间标签统一自然年：截至 2024-12 的季度标 2024Q4（而非财年口径 2025Q1）
        assertValue(r, "REVENUE", "2024Q4", PeriodType.SINGLE_Q, "100");
        assertValue(r, "REVENUE", "FY2025", PeriodType.FY, "400");
        assertValue(r, "FREE_CASH_FLOW", "2024Q4", PeriodType.SINGLE_Q, "40");
        List<String> periods = r.values().stream().map(MetricValueDO::getFiscalPeriod).distinct().toList();
        assertTrue(!periods.contains("2025Q1"), "不应再出现财年偏移标签 2025Q1，实际: " + periods);
    }

    /**
     * BABA 场景（FYE=3）：财年与自然年不一致——截至 2026-06 的季度是财年 2027Q1，
     * 统一按自然年标注为 2026Q2；年报截至 2026-03 标 FY2026。
     */
    @Test
    void usBaba_fiscalYearMarch_labelledByCalendarYear() {
        JSONObject root = new JSONObject();
        root.put("code", "US.BABA");
        root.put("ticker", "BABA");
        root.put("currency", "CNY");
        root.put("cumulative", false);
        JSONObject statements = new JSONObject();
        statements.put("income", stmt(
                report("2027/Q1", "2026-06-29", 2027, 1, item(8002, 2600)),
                report("2026/Q4", "2026-03-30", 2026, 4, item(8002, 2400)),
                report("2026/Q3", "2025-12-30", 2026, 3, item(8002, 3000)),
                report("2026/Q2", "2025-09-29", 2026, 2, item(8002, 2800)),
                report("2026/Q1", "2025-06-29", 2026, 1, item(8002, 2500)),
                report("2026/FY", "2026-03-30", 2026, 7, item(8002, 10000))));
        root.put("statements", statements);
        root.put("errors", new JSONObject());

        FutuStatementIngestor.IngestResult r = ingestor.processRoot(root);

        assertEquals(3, r.company().getFyEndMonth(), "年报截止 3 月应推断 fyEndMonth=3");
        // 财年 2027Q1（截至 2026-06）→ 自然年 2026Q2，其余季度按结束日归位
        assertValue(r, "REVENUE", "2026Q2", PeriodType.SINGLE_Q, "2600");
        assertValue(r, "REVENUE", "2026Q1", PeriodType.SINGLE_Q, "2400");
        assertValue(r, "REVENUE", "2025Q4", PeriodType.SINGLE_Q, "3000");
        assertValue(r, "REVENUE", "2025Q3", PeriodType.SINGLE_Q, "2800");
        assertValue(r, "REVENUE", "2025Q2", PeriodType.SINGLE_Q, "2500");
        // 年报结束月落在 2026 自然年 → FY2026
        assertValue(r, "REVENUE", "FY2026", PeriodType.FY, "10000");
        List<String> periods = r.values().stream().map(MetricValueDO::getFiscalPeriod).distinct().toList();
        assertTrue(!periods.contains("2027Q1"), "不应出现财年偏移标签 2027Q1，实际: " + periods);
    }

    /**
     * 港股 3 月财年（如 09988 阿里港股，cumulative=true、FYE=3）：累计链跨自然年——
     * 财年 Q1 结束于 6 月（2025Q2），年报结束于次年 3 月（FY2026 → slot 2026Q1）。
     * 差分应沿自然季度时间序跨自然年连续：2025Q2 直取，2025Q3/2025Q4/2026Q1 依次差分。
     */
    @Test
    void hkMarchFye_cumulativeDifferencedAcrossCalendarYears() {
        JSONObject root = new JSONObject();
        root.put("code", "HK.09988");
        root.put("ticker", "09988");
        root.put("currency", "CNY");
        root.put("cumulative", true);
        JSONObject statements = new JSONObject();
        // 累计收入：财年 Q1(3个月)=100；H1=300；9M=600；FY=1000；下一财年 Q1=130
        statements.put("income", stmt(
                report("2026Q1", "2025-06-30", 2026, 1, item(5001, 100)),
                report("2026Q6", "2025-09-30", 2026, 5, item(5001, 300)),
                report("2026Q9", "2025-12-31", 2026, 6, item(5001, 600)),
                report("FY2026", "2026-03-31", 2026, 7, item(5001, 1000)),
                report("2027Q1", "2026-06-30", 2027, 1, item(5001, 130))));
        root.put("statements", statements);
        root.put("errors", new JSONObject());

        FutuStatementIngestor.IngestResult r = ingestor.processRoot(root);

        assertEquals(3, r.company().getFyEndMonth(), "年报截止 3 月应推断 fyEndMonth=3");

        // 单季差分：财年 Q1=100（直取）；Q2=300-100=200；Q3=600-300=300；Q4=1000-600=400；新财年 Q1=130（直取）
        assertValue(r, "REVENUE", "2025Q2", PeriodType.SINGLE_Q, "100");
        assertValue(r, "REVENUE", "2025Q3", PeriodType.SINGLE_Q, "200");
        assertValue(r, "REVENUE", "2025Q4", PeriodType.SINGLE_Q, "300");
        assertValue(r, "REVENUE", "2026Q1", PeriodType.SINGLE_Q, "400");
        assertValue(r, "REVENUE", "2026Q2", PeriodType.SINGLE_Q, "130");

        // 累计/年报原行保留（自然年标签）
        assertValue(r, "REVENUE", "2025Q3", PeriodType.CUMULATIVE, "300");
        assertValue(r, "REVENUE", "FY2026", PeriodType.FY, "1000");
    }

    /**
     * 回归：港股 Q1 单季报（ftype=1）为稀疏字段集——经营现金流净额在字段 5001
     * （旧映射 5058 在该报告中缺失，会导致 OCF 取空），且不含 5071/5073 资本开支柱。
     */
    @Test
    void hkSparseQ1Report_ocfExtractedFromField5001() {
        JSONObject root = new JSONObject();
        root.put("code", "HK.00700");
        root.put("ticker", "00700");
        root.put("currency", "CNY");
        root.put("cumulative", true);
        JSONObject statements = new JSONObject();
        // 真实 futu Q1 单季报结构：仅 5001(OCF)/5069(投资净额)/5086(筹资净额)/5100(期末现金) 等头条字段
        statements.put("cashflow", stmt(
                report("2026Q1", "2026-03-31", 2026, 1,
                        item(5001, 101351), item(5069, -10560), item(5086, -12117), item(5100, 217770))));
        root.put("statements", statements);
        root.put("errors", new JSONObject());

        FutuStatementIngestor.IngestResult r = ingestor.processRoot(root);

        // OCF 必须取到 5001 的值（修复前映射 5058 缺失 → 单季 OCF 为 null）
        assertValue(r, "OPERATING_CF", "2026Q1", PeriodType.SINGLE_Q, "101351");
        // 投资净额取 5069（旧映射 5076 在稀疏报中同样缺失），符号保留
        assertValue(r, "INVESTING_CF", "2026Q1", PeriodType.SINGLE_Q, "-10560");
        // 稀疏报无资本开支字段 → 单季 CAPEX/FCF 留空
        MetricValueDO capex = find(r, "CAPEX", "2026Q1", PeriodType.SINGLE_Q);
        assertTrue(capex == null || capex.getValue() == null, "稀疏 Q1 报不应产生 CAPEX 值");
        MetricValueDO fcf = find(r, "FREE_CASH_FLOW", "2026Q1", PeriodType.SINGLE_Q);
        assertTrue(fcf == null || fcf.getValue() == null, "CAPEX 缺失时不应派生 FCF");
    }

    /**
     * 仅披露半年报/年报的港股公司（如 09992 泡泡玛特，FYE=12）：每期只有 H1(ftype=5, 自然Q2累计)
     * 与 FY(ftype=7)，没有 Q1 单季锚、也没有 Q3/9M 累计锚。
     * 修复前累计链跨财年不断开，会把「新年 H1 − 上年 FY」差分成大额负数单季（如 2026Q2 = 17173−37120 < 0），
     * 且把「FY − H1 = H2 六个月」误当 Q4 单季。修复后：不产出任何 SINGLE_Q（本就无季度数据），
     * 只保留 CUMULATIVE(H1) 与 FY 原行。
     */
    @Test
    void hkSemiannualOnly_noNegativeSingleQuarter() {
        JSONObject root = new JSONObject();
        root.put("code", "HK.09992");
        root.put("ticker", "09992");
        root.put("currency", "CNY");
        root.put("cumulative", true);
        JSONObject statements = new JSONObject();
        // 累计收入（百万）：H1 / FY；H1 为上半年累计，FY 为全年
        statements.put("income", stmt(
                report("2024/H1", "2024-06-30", 2024, 5, item(5001, 4557.8)),
                report("FY2024", "2024-12-31", 2024, 7, item(5001, 13037.7)),
                report("2025/H1", "2025-06-30", 2025, 5, item(5001, 13876.3)),
                report("FY2025", "2025-12-31", 2025, 7, item(5001, 37120.1)),
                report("2026/H1", "2026-06-30", 2026, 5, item(5001, 17172.9))));
        root.put("statements", statements);
        root.put("errors", new JSONObject());

        FutuStatementIngestor.IngestResult r = ingestor.processRoot(root);

        // 不得产出任何单季行（无 Q1 重启锚；H1→FY 跨 H2 两个月；FY→次年 H1 跨财年）
        for (MetricValueDO v : r.values()) {
            if ("REVENUE".equals(v.getMetricCode()) && PeriodType.SINGLE_Q.name().equals(v.getPeriodType())) {
                org.junit.jupiter.api.Assertions.fail(
                        "半年报公司不应差分出单季收入: " + v.getFiscalPeriod() + " = " + v.getValue());
            }
        }
        // 累计 H1 与年报 FY 原行保留
        assertValue(r, "REVENUE", "2025Q2", PeriodType.CUMULATIVE, "13876.3");
        assertValue(r, "REVENUE", "2026Q2", PeriodType.CUMULATIVE, "17172.9");
        assertValue(r, "REVENUE", "FY2025", PeriodType.FY, "37120.1");
        assertValue(r, "REVENUE", "FY2024", PeriodType.FY, "13037.7");
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
        return report(period, end, fy, null, items);
    }

    private static JSONObject report(String period, String end, int fy, Integer ftype, JSONObject... items) {
        JSONObject r = new JSONObject();
        r.put("period", period);
        r.put("periodEnd", end);
        r.put("fiscalYear", fy);
        if (ftype != null) {
            r.put("ftype", ftype);
        }
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
