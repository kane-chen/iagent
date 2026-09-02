package io.invest.iagent.financial.ingest;

import io.invest.iagent.financial.model.SegmentDO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // =========================================================
    //  多层分部树构建（resolveHierarchy）
    // =========================================================

    /** 构造测试分部并登记首次出现序号。 */
    private static void addSeg(Map<String, SegmentDO> map, Map<String, Integer> firstSeen, int[] seq,
                               String code, String parent, int level) {
        map.put(code, SegmentDO.builder()
                .ticker("T").segmentCode(code).segmentName(code)
                .parentCode(parent).level(level).build());
        firstSeen.put(code, seq[0]++);
    }

    private static List<String> codes(List<SegmentDO> ordered) {
        return ordered.stream().map(SegmentDO::getSegmentCode).toList();
    }

    /**
     * BABA 三层结构（TAOBAO_TMALL → CHINA_COMMERCE_RETAIL → CUSTOMER_MANAGEMENT 等）：
     * 先根遍历排序、树深归一 level、sortOrder 连续。
     */
    @Test
    void resolveHierarchy_threeLevels_preOrderAndDepth() {
        Map<String, SegmentDO> map = new LinkedHashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        int[] seq = {0};
        addSeg(map, seen, seq, "TAOBAO_TMALL", null, 1);
        addSeg(map, seen, seq, "CHINA_COMMERCE_RETAIL", "TAOBAO_TMALL", 2);
        addSeg(map, seen, seq, "CUSTOMER_MANAGEMENT", "CHINA_COMMERCE_RETAIL", 3);
        addSeg(map, seen, seq, "DIRECT_SALES", "CHINA_COMMERCE_RETAIL", 3);
        addSeg(map, seen, seq, "QUICK_COMMERCE", "CHINA_COMMERCE_RETAIL", 3);
        addSeg(map, seen, seq, "CHINA_COMMERCE_WHOLESALE", "TAOBAO_TMALL", 2);
        addSeg(map, seen, seq, "CLOUD_INTELLIGENCE", null, 1);

        List<SegmentDO> ordered = SegmentIngestor.resolveHierarchy(map, seen, new ArrayList<>());

        assertEquals(List.of("TAOBAO_TMALL", "CHINA_COMMERCE_RETAIL", "CUSTOMER_MANAGEMENT",
                "DIRECT_SALES", "QUICK_COMMERCE", "CHINA_COMMERCE_WHOLESALE", "CLOUD_INTELLIGENCE"),
                codes(ordered));
        // level 按树深归一
        assertEquals(1, ordered.get(0).getLevel());
        assertEquals(2, ordered.get(1).getLevel());
        assertEquals(3, ordered.get(2).getLevel());
        assertEquals(2, ordered.get(5).getLevel());
        assertEquals(1, ordered.get(6).getLevel());
        // sortOrder 为先根遍历序号
        for (int i = 0; i < ordered.size(); i++) {
            assertEquals(i, ordered.get(i).getSortOrder());
        }
    }

    /** 子分部记录先于父分部出现（跨文件合并乱序）：仍按父子关系先根排列。 */
    @Test
    void resolveHierarchy_childBeforeParent_stillPreOrder() {
        Map<String, SegmentDO> map = new LinkedHashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        int[] seq = {0};
        addSeg(map, seen, seq, "CUSTOMER_MANAGEMENT", "CHINA_COMMERCE_RETAIL", 3);
        addSeg(map, seen, seq, "DIRECT_SALES", "CHINA_COMMERCE_RETAIL", 3);
        addSeg(map, seen, seq, "CHINA_COMMERCE_RETAIL", "TAOBAO_TMALL", 2);
        addSeg(map, seen, seq, "TAOBAO_TMALL", null, 1);
        addSeg(map, seen, seq, "CLOUD_INTELLIGENCE", null, 1);

        List<SegmentDO> ordered = SegmentIngestor.resolveHierarchy(map, seen, new ArrayList<>());

        assertEquals(List.of("TAOBAO_TMALL", "CHINA_COMMERCE_RETAIL", "CUSTOMER_MANAGEMENT",
                "DIRECT_SALES", "CLOUD_INTELLIGENCE"), codes(ordered));
        assertEquals(3, ordered.get(2).getLevel());
    }

    /** 父分部无任何数据行（纯分组标题）：补占位节点并置于其子树之前。 */
    @Test
    void resolveHierarchy_missingParent_synthesizesPlaceholder() {
        Map<String, SegmentDO> map = new LinkedHashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        int[] seq = {0};
        addSeg(map, seen, seq, "CUSTOMER_MANAGEMENT", "CHINA_COMMERCE_RETAIL", 3);
        addSeg(map, seen, seq, "TAOBAO_TMALL", null, 1);
        List<String> warnings = new ArrayList<>();

        List<SegmentDO> ordered = SegmentIngestor.resolveHierarchy(map, seen, warnings);

        // 占位父节点已补全，名称暂用编码
        SegmentDO placeholder = map.get("CHINA_COMMERCE_RETAIL");
        assertEquals("CHINA_COMMERCE_RETAIL", placeholder.getSegmentName());
        assertNull(placeholder.getParentCode());
        // 先根序：占位父节点紧邻其子分部之前
        assertEquals(List.of("CHINA_COMMERCE_RETAIL", "CUSTOMER_MANAGEMENT", "TAOBAO_TMALL"),
                codes(ordered));
        assertEquals(1, ordered.get(0).getLevel());
        assertEquals(2, ordered.get(1).getLevel());
        assertEquals(1, warnings.size());
    }

    /** 父链循环引用（A↔B）：断开一条父边后成为合法树，不死循环、节点均可达。 */
    @Test
    void resolveHierarchy_cycle_brokenToTree() {
        Map<String, SegmentDO> map = new LinkedHashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        int[] seq = {0};
        addSeg(map, seen, seq, "SEG_A", "SEG_B", 2);
        addSeg(map, seen, seq, "SEG_B", "SEG_A", 1);
        List<String> warnings = new ArrayList<>();

        List<SegmentDO> ordered = SegmentIngestor.resolveHierarchy(map, seen, warnings);

        // 一条父边被断开：一个根（level 1）+ 一个子节点（level 2），全部可达
        assertEquals(2, ordered.size());
        assertEquals(1, ordered.get(0).getLevel());
        assertEquals(2, ordered.get(1).getLevel());
        assertEquals(1, warnings.size());
    }

    // =========================================================
    //  财报文件发现（discoverReportFiles：financial_reports 布局）
    // =========================================================

    /** 按 FinancialReportService 落盘布局造一个空文件。 */
    private static void touch(Path base, String market, String ticker, String fileName) throws Exception {
        Path dir = base.resolve(market).resolve(ticker);
        Files.createDirectories(dir);
        Files.createFile(dir.resolve(fileName));
    }

    @Test
    void discoverReportFiles_findsPdfAndHtm_sortedByName(@TempDir Path base) throws Exception {
        touch(base, "US", "BABA", "BABA_20220526_6-K.htm");
        touch(base, "US", "BABA", "BABA_20210513_6-K.htm");
        touch(base, "US", "BABA", "notes.txt");                 // 非财报正文，忽略
        touch(base, "HK", "00700", "00700_2025-05-14_QUARTERLY.pdf");
        touch(base, "CN", "600519", "600519_2025-04-30_QUARTERLY.pdf");

        List<Path> baba = SegmentIngestor.discoverReportFiles(base, "BABA");
        assertEquals(2, baba.size());
        // 按文件名（日期）升序
        assertEquals("BABA_20210513_6-K.htm", baba.get(0).getFileName().toString());
        assertEquals("BABA_20220526_6-K.htm", baba.get(1).getFileName().toString());

        List<Path> hk = SegmentIngestor.discoverReportFiles(base, "00700");
        assertEquals(1, hk.size());
        assertTrue(hk.get(0).getFileName().toString().endsWith(".pdf"));
    }

    @Test
    void discoverReportFiles_tickerNotDownloaded_empty(@TempDir Path base) throws Exception {
        touch(base, "US", "BABA", "BABA_20210513_6-K.htm");

        // 该 ticker 无下载目录
        assertTrue(SegmentIngestor.discoverReportFiles(base, "PDD").isEmpty());
        // 基目录不存在
        assertTrue(SegmentIngestor.discoverReportFiles(base.resolve("missing"), "BABA").isEmpty());
    }
}
