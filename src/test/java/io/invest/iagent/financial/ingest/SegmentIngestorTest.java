package io.invest.iagent.financial.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link SegmentIngestor} 期间标签规范化测试。
 */
class SegmentIngestorTest {

    @Test
    void canonicalPeriod_convertsEngineLabels() {
        // segment 引擎输出 2025FY 形态，规范为 FY2025
        assertEquals("FY2025", SegmentIngestor.canonicalPeriod("2025FY"));
        // 季度标签保持
        assertEquals("2025Q1", SegmentIngestor.canonicalPeriod("2025Q1"));
        // 中期 H1 保持（FiscalPeriod 可排序）
        assertEquals("2025H1", SegmentIngestor.canonicalPeriod("2025H1"));
        // 大小写/空白容错（引擎输出年份在前：2024FY）
        assertEquals("FY2024", SegmentIngestor.canonicalPeriod(" 2024fy "));
        // 无法识别
        assertNull(SegmentIngestor.canonicalPeriod("unknown"));
        assertNull(SegmentIngestor.canonicalPeriod(null));
    }

    /**
     * 财年口径标签 → 自然年口径（fyeMonth=3，BABA：财年截至 3 月）。
     * 财年 2027Q1 截至 2026-06 → 2026Q2；财年 Q4 截至 2027-03 → 2027Q1；FY 年份不变。
     */
    @Test
    void canonicalPeriod_fiscalShiftMarch_convertsToCalendarYear() {
        assertEquals("2026Q2", SegmentIngestor.canonicalPeriod("2027Q1", 3));
        assertEquals("2026Q3", SegmentIngestor.canonicalPeriod("2027Q2", 3));
        assertEquals("2026Q4", SegmentIngestor.canonicalPeriod("2027Q3", 3));
        assertEquals("2027Q1", SegmentIngestor.canonicalPeriod("2027Q4", 3));
        // 财年 H1（4-9 月）结束于 9 月 → 自然年 H2
        assertEquals("2026H2", SegmentIngestor.canonicalPeriod("2027H1", 3));
        // 年报 FY 标签年份即结束自然年，保持不变
        assertEquals("FY2026", SegmentIngestor.canonicalPeriod("2026FY", 3));
    }

    /** fyeMonth=6（MSFT：财年截至 6 月）：财年 2026Q2 截至 2025-12 → 2025Q4。 */
    @Test
    void canonicalPeriod_fiscalShiftJune_convertsToCalendarYear() {
        assertEquals("2025Q3", SegmentIngestor.canonicalPeriod("2026Q1", 6));
        assertEquals("2025Q4", SegmentIngestor.canonicalPeriod("2026Q2", 6));
        assertEquals("2026Q1", SegmentIngestor.canonicalPeriod("2026Q3", 6));
        assertEquals("2026Q2", SegmentIngestor.canonicalPeriod("2026Q4", 6));
        assertEquals("FY2025", SegmentIngestor.canonicalPeriod("2025FY", 6));
    }

    /** fyeMonth=1（NVDA：财年截至 1 月）：财年 2026Q1 截至 2025-04 → 2025Q2。 */
    @Test
    void canonicalPeriod_fiscalShiftJanuary_convertsToCalendarYear() {
        assertEquals("2025Q2", SegmentIngestor.canonicalPeriod("2026Q1", 1));
        assertEquals("2025Q1", SegmentIngestor.canonicalPeriod("2025Q4", 1));
        assertEquals("FY2025", SegmentIngestor.canonicalPeriod("2025FY", 1));
    }

    /** fyeMonth=12（自然年财年）：标签原样返回。 */
    @Test
    void canonicalPeriod_decemberFye_passthrough() {
        assertEquals("2025Q1", SegmentIngestor.canonicalPeriod("2025Q1", 12));
        assertEquals("2025Q4", SegmentIngestor.canonicalPeriod("2025Q4", 12));
        assertEquals("FY2025", SegmentIngestor.canonicalPeriod("2025FY", 12));
    }
}
