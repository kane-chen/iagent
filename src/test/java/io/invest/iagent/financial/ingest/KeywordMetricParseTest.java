package io.invest.iagent.financial.ingest;

import io.invest.iagent.financial.config.KeywordMetricDef;
import io.invest.iagent.financial.ingest.KeywordMetricExtractor.StorageKey;
import io.invest.iagent.financial.model.MetricValueDO;
import io.invest.iagent.financial.model.PeriodType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关键字补充指标"数值口径映射与 JSON 解析"的纯单元测试（不起 Spring、不调用 LLM）。
 *
 * <p>财报片段可能只披露累计值（如 00700 半年报的股份回购为 H1 累计、年报兜底四季度时为全年值），
 * LLM 逐项标注 periodType，入库时按数值<strong>实际披露口径</strong>落库，而非套用目标期间标签。
 */
class KeywordMetricParseTest {

    private static StorageKey key(String period, PeriodType scope, int fyeMonth) {
        return KeywordMetricExtractor.resolveStorageKey(period, scope, fyeMonth);
    }

    private static void assertKey(StorageKey key, String fiscalPeriod, PeriodType periodType) {
        assertEquals(fiscalPeriod, key.fiscalPeriod(), "fiscalPeriod 映射不符");
        assertEquals(periodType, key.periodType(), "periodType 映射不符");
    }

    // ---------- resolveStorageKey：fyeMonth=12（自然年财年，如 00700） ----------

    /** 目标 2025Q4、年报片段只披露全年值 → 存 FY2025/FY（年报兜底四季度场景）。 */
    @Test
    void storageKey_q4Target_fullYearValue_storedAsFY() {
        assertKey(key("2025Q4", PeriodType.FY, 12), "FY2025", PeriodType.FY);
    }

    /** 目标 2025Q2、半年报片段披露 H1 累计 → 存 2025Q2/CUMULATIVE（与三大表累计行键控一致）。 */
    @Test
    void storageKey_q2Target_halfYearCumulative_storedAsCumulative() {
        assertKey(key("2025Q2", PeriodType.CUMULATIVE, 12), "2025Q2", PeriodType.CUMULATIVE);
        assertKey(key("2025Q3", PeriodType.CUMULATIVE, 12), "2025Q3", PeriodType.CUMULATIVE);
    }

    /** 累计到财年末季（Q4）即完整财年，按 FY 存而非 2025Q4/CUMULATIVE。 */
    @Test
    void storageKey_q4Target_cumulativeValue_storedAsFY() {
        assertKey(key("2025Q4", PeriodType.CUMULATIVE, 12), "FY2025", PeriodType.FY);
    }

    /** 单季值按目标季度原样存储。 */
    @Test
    void storageKey_quarterTarget_singleQuarterValue_storedAsSingleQ() {
        assertKey(key("2025Q3", PeriodType.SINGLE_Q, 12), "2025Q3", PeriodType.SINGLE_Q);
        assertKey(key("FY2025", PeriodType.FY, 12), "FY2025", PeriodType.FY);
    }

    // ---------- resolveStorageKey：fyeMonth=3（财年截至 3 月，如 BABA） ----------

    /** 财年末季为自然年 Q1：目标 2026Q1 的年报全年值 → FY2026/FY；FY 目标的单季值 → 2026Q1/SINGLE_Q。 */
    @Test
    void storageKey_fyeMarch_lastFiscalQuarterIsCalendarQ1() {
        assertKey(key("2026Q1", PeriodType.FY, 3), "FY2026", PeriodType.FY);
        assertKey(key("FY2026", PeriodType.SINGLE_Q, 3), "2026Q1", PeriodType.SINGLE_Q);
        assertKey(key("2026Q1", PeriodType.CUMULATIVE, 3), "FY2026", PeriodType.FY);
    }

    // ---------- candidateStoragePeriods：已提取跳过判断需覆盖口径别名 ----------

    @Test
    void candidatePeriods_coversScopeAliases() {
        // 财年末季目标：可能写入 FY 全年值
        assertEquals(List.of("2025Q4", "FY2025"),
                KeywordMetricExtractor.candidateStoragePeriods("2025Q4", 12));
        // 年中季度目标：只可能写本季度
        assertEquals(List.of("2025Q2"),
                KeywordMetricExtractor.candidateStoragePeriods("2025Q2", 12));
        // FY 目标：可能写入财年末季单季值
        assertEquals(List.of("FY2025", "2025Q4"),
                KeywordMetricExtractor.candidateStoragePeriods("FY2025", 12));
        // fyeMonth=3：财年末季为自然年 Q1
        assertEquals(List.of("2026Q1", "FY2026"),
                KeywordMetricExtractor.candidateStoragePeriods("2026Q1", 3));
    }

    // ---------- parseResponse：LLM JSON → 指标行 ----------

    /** 反射调用私有的 parseResponse（不依赖 Spring/数据库/LLM）。 */
    @SuppressWarnings("unchecked")
    private List<MetricValueDO> parse(String period, String json, int fyeMonth) throws Exception {
        KeywordMetricExtractor extractor = new KeywordMetricExtractor();
        KeywordMetricDef def = new KeywordMetricDef();
        def.setCode("SHARE_BUYBACK");
        def.setMinConfidence(60);
        Method m = KeywordMetricExtractor.class.getDeclaredMethod(
                "parseResponse", String.class, String.class, KeywordMetricDef.class,
                String.class, Path.class, int.class);
        m.setAccessible(true);
        return (List<MetricValueDO>) m.invoke(extractor,
                "00700", period, def, json, Path.of("00700_2026-04-09_ANNUAL.pdf"), fyeMonth);
    }

    private static MetricValueDO find(List<MetricValueDO> rows, String fiscalPeriod, PeriodType type) {
        return rows.stream()
                .filter(r -> fiscalPeriod.equals(r.getFiscalPeriod()) && type.name().equals(r.getPeriodType()))
                .findFirst().orElse(null);
    }

    /** 新约定 values 数组：单季 + 全年两个口径分别落库到 (2025Q4,SINGLE_Q) 与 (FY2025,FY)。 */
    @Test
    void parse_valuesArray_singleAndFullYear_bothStored() throws Exception {
        String json = """
                { "SHARE_BUYBACK": { "found": true, "values": [
                    {"value": 1234.5, "unit": "million", "periodType": "SINGLE_Q", "confidence": 90, "evidence": "three months ended"},
                    {"value": 8000.0, "unit": "million", "periodType": "FY", "confidence": 95, "evidence": "year ended"}
                ] } }
                """;
        List<MetricValueDO> rows = parse("2025Q4", json, 12);

        MetricValueDO single = find(rows, "2025Q4", PeriodType.SINGLE_Q);
        MetricValueDO fy = find(rows, "FY2025", PeriodType.FY);
        assertTrue(single != null && fy != null, "单季与全年两个口径都应入库: " + rows);
        assertEquals(0, new java.math.BigDecimal("1234.5").compareTo(single.getValue()));
        assertEquals(0, new java.math.BigDecimal("8000.0").compareTo(fy.getValue()));
        assertEquals("KEYWORD", single.getSource());
    }

    /** 旧版扁平形状（无 periodType）：按目标期间回退为 SINGLE_Q，兼容不落空。 */
    @Test
    void parse_flatShape_defaultsToSingleQuarter() throws Exception {
        String json = """
                { "SHARE_BUYBACK": {"found": true, "value": 5100.0, "unit": "million", "confidence": 88} }
                """;
        List<MetricValueDO> rows = parse("2025Q3", json, 12);

        assertEquals(1, rows.size());
        MetricValueDO row = rows.get(0);
        assertEquals("2025Q3", row.getFiscalPeriod());
        assertEquals(PeriodType.SINGLE_Q.name(), row.getPeriodType());
    }

    /** 扁平形状 + periodType=CUMULATIVE：半年报累计值落到 (2025Q2, CUMULATIVE)，千元单位换算百万。 */
    @Test
    void parse_flatShape_cumulativeTagged_storedAsCumulative() throws Exception {
        String json = """
                { "SHARE_BUYBACK": {"found": true, "value": 5100000, "unit": "thousand",
                                    "periodType": "CUMULATIVE", "confidence": 90} }
                """;
        List<MetricValueDO> rows = parse("2025Q2", json, 12);

        assertEquals(1, rows.size());
        MetricValueDO row = rows.get(0);
        assertEquals("2025Q2", row.getFiscalPeriod());
        assertEquals(PeriodType.CUMULATIVE.name(), row.getPeriodType());
        // 5,100,000 千 = 5,100 百万
        assertEquals(0, new java.math.BigDecimal("5100.000").compareTo(row.getValue()));
    }

    /** found=false 与低置信度均不产出指标行。 */
    @Test
    void parse_notFoundAndLowConfidence_produceNoRows() throws Exception {
        assertTrue(parse("2025Q4", "{ \"SHARE_BUYBACK\": {\"found\": false} }", 12).isEmpty());

        String lowConf = """
                { "SHARE_BUYBACK": { "found": true, "values": [
                    {"value": 1.0, "unit": "million", "periodType": "FY", "confidence": 30}
                ] } }
                """;
        assertTrue(parse("2025Q4", lowConf, 12).isEmpty(), "低于 minConfidence 的结果应丢弃");
    }
}
