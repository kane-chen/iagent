package io.invest.iagent.financial.ingest;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SegmentIngestor#filterReportsByPeriods} 财报文件期间过滤的纯单元测试（不起 Spring）。
 *
 * <p>重点：财年末季单季数据只随年报披露——fyeMonth=12 的公司（如 00700 腾讯）不发 Q4 季报，
 * 四季度数据在 FY 年报中。覆盖期间为该季度时，优先取专属季报；季报不存在时取 FY 年报兜底。
 */
class SegmentIngestorPeriodFilterTest {

    private static List<Path> files(String... names) {
        return java.util.Arrays.stream(names).map(Path::of).toList();
    }

    private static boolean contains(List<Path> kept, String fileName) {
        return kept.stream().anyMatch(p -> p.getFileName().toString().equals(fileName));
    }

    /** fyeMonth=12：2025Q4 无专属季报，FY2025 年报（2026-04 发布）应作为兜底保留。 */
    @Test
    void q4_withoutQuarterlyReport_fallsBackToAnnualReport() {
        List<Path> files = files(
                "00700_2025-11-13_QUARTERLY.pdf",   // 2025Q3
                "00700_2026-04-09_ANNUAL.pdf",      // FY2025（含 2025Q4 数据）
                "00700_2026-05-13_QUARTERLY.pdf");  // 2026Q1

        List<Path> kept = SegmentIngestor.filterReportsByPeriods(files, Set.of("2025Q4"), 12);

        assertTrue(contains(kept, "00700_2026-04-09_ANNUAL.pdf"),
                "2025Q4 无专属季报时应保留 FY2025 年报兜底");
        assertFalse(contains(kept, "00700_2025-11-13_QUARTERLY.pdf"),
                "2025Q3 季报与 2025Q4 无关，不应保留");
        assertFalse(contains(kept, "00700_2026-05-13_QUARTERLY.pdf"),
                "2026Q1 季报与 2025Q4 无关，不应保留");
    }

    /** fyeMonth=12：2025Q4 已有专属季报（2026 年 1-3 月发布）时，不再冗余拉入 FY2025 年报。 */
    @Test
    void q4_withQuarterlyReport_prefersQuarterlyOverAnnual() {
        List<Path> files = files(
                "00700_2026-03-15_QUARTERLY.pdf",   // 2025Q4 专属季报
                "00700_2026-04-09_ANNUAL.pdf");     // FY2025

        List<Path> kept = SegmentIngestor.filterReportsByPeriods(files, Set.of("2025Q4"), 12);

        assertTrue(contains(kept, "00700_2026-03-15_QUARTERLY.pdf"),
                "2025Q4 专属季报应保留");
        assertFalse(contains(kept, "00700_2026-04-09_ANNUAL.pdf"),
                "已有 2025Q4 专属季报时不应再冗余拉入 FY2025 年报");
    }

    /** fyeMonth=3（如 BABA）：财年末季为自然年 Q1，FY2026 年报（20-F，截至 2026-03）应兜底 2026Q1。 */
    @Test
    void fyeMarch_lastFiscalQuarterIsCalendarQ1() {
        List<Path> files = files(
                "BABA_2026-07-15_20-F.pdf",         // FY2026 年报（截至 2026-03，含财年末季 2026Q1）
                "BABA_2025-11-15_6-K.pdf");         // 2025Q3（按发布月反推）

        List<Path> kept = SegmentIngestor.filterReportsByPeriods(files, Set.of("2026Q1"), 3);

        assertTrue(contains(kept, "BABA_2026-07-15_20-F.pdf"),
                "fyeMonth=3 时 FY2026 年报含 2026Q1（财年末季）数据，应兜底保留");
        assertFalse(contains(kept, "BABA_2025-11-15_6-K.pdf"),
                "2025Q3 季报与 2026Q1 无关，不应保留");
    }

    /** 年报兜底仅限财年末季：覆盖期间为年中季度（如 2025Q3）时，FY2025 年报不应被保留。 */
    @Test
    void annualReport_notKeptForMidYearQuarter() {
        List<Path> files = files(
                "00700_2026-04-09_ANNUAL.pdf",      // FY2025
                "00700_2025-11-13_QUARTERLY.pdf");  // 2025Q3

        List<Path> kept = SegmentIngestor.filterReportsByPeriods(files, Set.of("2025Q3"), 12);

        assertTrue(contains(kept, "00700_2025-11-13_QUARTERLY.pdf"),
                "2025Q3 专属季报应保留");
        assertFalse(contains(kept, "00700_2026-04-09_ANNUAL.pdf"),
                "FY2025 年报不含 2025Q3 单季兜底口径，不应保留");
    }
}
