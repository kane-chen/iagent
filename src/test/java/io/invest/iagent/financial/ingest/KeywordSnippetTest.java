package io.invest.iagent.financial.ingest;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关键字补充指标"检索阶段"（纯关键字/规则，<b>不调用 LLM</b>）的片段摘取测试。
 *
 * <p>早期实现把命中行上下拼 4 行、超过 100 字就按逗号拆子句、只保留含关键字的子句，
 * 会切掉句首的报告期间状语（"During the quarter ended June 30, 2026,"）与句末的上年同期
 * 对比（"compared to ... in the same quarter of 2025"），LLM 拿到残句无法定值。这里用
 * 合成文本（仿真 BABA 6-K 硬换行 + 表格行）验证片段按语义边界摘取、语义完整。
 */
class KeywordSnippetTest {

    private static final List<String> CAPEX_KW =
            List.of("capital expenditure", "capital expenditures", "capex", "资本开支");
    private static final List<String> BUYBACK_KW =
            List.of("share repurchase", "share buyback", "repurchase of shares", "回购股份");

    /** 直接调用私有的 searchSnippets：预填充文本缓存以绕开文件读取/LLM/数据库。 */
    @SuppressWarnings("unchecked")
    private List<String> retrieve(String text, List<String> keywords) throws Exception {
        KeywordMetricExtractor extractor = new KeywordMetricExtractor();
        Path file = Path.of("dummy.htm");
        Map<Path, String> cache = new HashMap<>();
        cache.put(file, text);
        Method search = KeywordMetricExtractor.class.getDeclaredMethod(
                "searchSnippets", List.class, List.class, Map.class, int.class);
        search.setAccessible(true);
        List<Object> snippets = (List<Object>) search.invoke(extractor, List.of(file), keywords, cache, 10);
        assertFalse(snippets.isEmpty(), "应检索到命中片段");
        Method textAccessor = snippets.get(0).getClass().getDeclaredMethod("text");
        textAccessor.setAccessible(true);
        return snippets.stream().map(s -> {
            try {
                return (String) textAccessor.invoke(s);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).toList();
    }

    /** 散文长句跨多行：句首期间状语 + 当期金额 + 上年同期对比都要保留，并在本句句号处收尾（不带出下一句）。 */
    @Test
    void prose_keepsPeriodLeadinAndComparator_stripsNextSentence() throws Exception {
        String text = String.join("\n",
                "During the quarter ended June 30, 2026, capital expenditures were RMB67,678 million (US$9,975",
                "million), an increase of 75% compared to RMB38,676 million in the same quarter of 2025,",
                "reflecting our continued investments in AI infrastructure to meet strong and growing",
                "customer demand. The significant year-over-year increase was due to several reasons,",
                "including fluctuations in procurement cycles and higher pricing of chip components.");
        String top = retrieve(text, CAPEX_KW).get(0);

        assertTrue(top.startsWith("During the quarter ended June 30, 2026"),
                "句首报告期间状语必须保留: " + top);
        assertTrue(top.contains("RMB67,678"), "当期金额必须保留: " + top);
        assertTrue(top.contains("same quarter of 2025"), "上年同期对比必须保留: " + top);
        assertTrue(top.endsWith("customer demand."), "应在关键字所在句的句号处收尾: " + top);
        assertFalse(top.contains("significant year-over-year"),
                "下一句残片（The significant...）应被裁掉: " + top);
    }

    /** 上一句与目标句压在同一物理行（"facilities. In the year..."）：须锚定命中行关键字取目标句。 */
    @Test
    void prose_sameLineTwoSentences_anchorsOnHitKeyword() throws Exception {
        String text = String.join("\n",
                "Our capital expenditures have been incurred primarily in relation to (i) computer equipment",
                "and (ii) data centers; and (iii) land use rights and office facilities. In the year",
                "ended March 31, 2025 and 2026, our capital expenditures totaled RMB85,972 million",
                "and RMB126,063 million, respectively.");
        String top = retrieve(text, CAPEX_KW).stream()
                .filter(s -> s.contains("RMB126,063")).findFirst().orElse(null);
        assertNotNull(top, "应检索到含两年金额的目标句");

        assertTrue(top.startsWith("In the year ended March 31, 2025 and 2026"),
                "应剥掉同行上一句、从目标句首开始: " + top);
        assertTrue(top.contains("RMB85,972"), "上一年金额须保留: " + top);
        assertTrue(top.endsWith("respectively."), "应在目标句句号处收尾: " + top);
        assertFalse(top.contains("primarily in relation"), "上一句枚举内容不应残留: " + top);
    }

    /** 表格命中：标签行整行保留（标签 | 当期 | 上年同期），并上溯附上年份表头行。 */
    @Test
    void tableRow_keepsFullRowAndYearHeader() throws Exception {
        String text = String.join("\n",
                "| Three months ended June 30, | 2025 | 2026 |",
                "Share repurchase of common stock | 38,676 | 67,678");
        String top = retrieve(text, BUYBACK_KW).get(0);

        assertTrue(top.contains("Share repurchase of common stock"), "表格标签行须整行保留: " + top);
        assertTrue(top.contains("38,676") && top.contains("67,678"), "两列金额须保留: " + top);
        assertTrue(top.contains("2025") && top.contains("2026"), "年份表头须上溯附上: " + top);
    }
}
