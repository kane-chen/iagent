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
}
