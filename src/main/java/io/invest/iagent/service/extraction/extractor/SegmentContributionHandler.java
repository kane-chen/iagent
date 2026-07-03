package io.invest.iagent.service.extraction.extractor;

import io.invest.iagent.service.extraction.model.CompanyConfig;
import io.invest.iagent.service.extraction.model.FinancialTable;
import io.invest.iagent.service.extraction.model.Segment;
import io.invest.iagent.service.extraction.model.TableCell;
import io.invest.iagent.service.extraction.model.TableRow;
import io.invest.iagent.service.extraction.recognizer.SegmentRecognizer;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BEKE 独占的 "分部块矩阵"（Segment Contribution Blocks）layout handler。
 *
 * <p>贝壳 6-K/Q4 press release 的核心分部表结构不同于 BABA/PDD/MSFT 那种"每分部一行"，
 * 而是把每个分部拆成 <b>3 行一块</b>：</p>
 * <pre>
 *   Existing home transaction services          (label only, no numbers)      ← 分部标题
 *     Net revenues                    5,439,563  777,847  ...                 ← REVENUE
 *     Commission and compensation    (3,240,687)(463,412) ...                 ← COST
 *     Contribution                    2,198,876  314,435  ...                 ← OPERATING_INCOME
 *   New home transaction services                (下一块)
 *     ...
 * </pre>
 *
 * <p>识别策略：</p>
 * <ol>
 *   <li>沿表格自上而下扫描，遇到 label 精确命中 config 中某个 L1 segment 且**这一行没有数值** →
 *       视为分部标题，开启一个"分部块上下文"</li>
 *   <li>之后连续 N 行按 metricMappingRules 归类：{@code Net revenues → REVENUE}、
 *       {@code Commission and compensation / Material costs / ... → COST}、
 *       {@code Contribution → OPERATING_INCOME}（都从 config.metricMappingRules 读，
 *       没有 hard-code）</li>
 *   <li>遇到下一个 segment 标题时切换上下文；遇到"总收入/合计"这类 non-block 行时收尾</li>
 * </ol>
 *
 * <p>只有 {@code companyConfig.htmlLayout == "SEGMENT_CONTRIBUTION_BLOCKS"} 才会命中，
 * 其他公司走不到这里，无回归风险。</p>
 */
@Slf4j
public final class SegmentContributionHandler implements HtmlLayoutHandler {

    /** config.htmlLayout 的取值 —— 用字符串而非 enum，让 config JSON 反序列化零改动。 */
    public static final String LAYOUT_ID = "SEGMENT_CONTRIBUTION_BLOCKS";

    private final HtmlExtractionSupport support;
    private final SegmentRecognizer recognizer;

    public SegmentContributionHandler(HtmlExtractionSupport support,
                                      SegmentRecognizer recognizer) {
        this.support = support;
        this.recognizer = recognizer;
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean supports(FinancialTable table, CompanyConfig companyConfig) {
        if (companyConfig == null) return false;
        if (!LAYOUT_ID.equalsIgnoreCase(companyConfig.getHtmlLayout())) return false;
        if (!support.hasAnyDataRow(table)) return false;
        // 至少能在表里找到 config 中定义的一个 L1 segment 标题行（label 命中且当行无数值）
        return findFirstSegmentTitleRow(table, companyConfig) >= 0;
    }

    @Override
    public int apply(FinancialTable table, CompanyConfig companyConfig,
                     Map<String, Segment> sink) {
        List<TableRow> rows = table.getRows();
        List<String> periodSequence = buildPeriodSequence(table);
        if (periodSequence.isEmpty()) {
            return 0;
        }

        // 单位显式来自 config.defaultUnit（BEKE 报正文里到处是 "$XXX million" 的叙述，
        // HtmlReportParser.inferCurrencyAndUnit 会误判成 million，实际分部表里的数字是 in thousands）
        String unitOverride = companyConfig != null ? companyConfig.getDefaultUnit() : null;

        int hits = 0;
        Segment currentSeg = null;
        for (int i = 0; i < rows.size(); i++) {
            TableRow row = rows.get(i);
            String label = row.getLabel() == null ? "" : row.getLabel().trim();
            if (label.isEmpty()) continue;

            // 是否命中 L1 segment 标题？
            String matchedCode = matchLevel1SegmentCode(label, companyConfig);
            if (matchedCode != null && !hasAnyNumeric(row)) {
                currentSeg = support.getOrCreateSegment(sink, companyConfig, matchedCode);
                continue;
            }

            // 处于某个 segment 块内 → 按 metricMappingRules 归类 label 到指标
            if (currentSeg == null) continue;

            String metricCode = matchMetricCode(label, companyConfig);
            if (metricCode == null) {
                // 不是关心的指标行；如果是"total"/"其他"这种关键结束标志，可以关掉上下文，
                // 但保守起见继续走 —— 反正下一个 segment 标题会覆盖 currentSeg。
                continue;
            }

            List<Double> numeric = extractNumericValuesInOrder(row);
            int matchCount = Math.min(numeric.size(), periodSequence.size());
            for (int idx = 0; idx < matchCount; idx++) {
                String period = periodSequence.get(idx);
                Double value = numeric.get(idx);
                if (period == null || period.isEmpty() || value == null) continue;
                // 累计周期跳过，只留季度数据（与 DataExtractor 语义一致）
                if (period.endsWith("QTD9") || period.endsWith("QTD6")
                        || period.endsWith("H")) continue;
                support.addMetric(currentSeg, metricCode, period, value, table, unitOverride);
                hits++;
            }
        }

        if (hits > 0) {
            log.info("SegmentContributionHandler consumed table {} → +{} metrics",
                    table.getTableId(), hits);
        }
        return hits;
    }

    // ---- 内部辅助 -------------------------------------------------------------

    private int findFirstSegmentTitleRow(FinancialTable table, CompanyConfig cfg) {
        List<TableRow> rows = table.getRows();
        for (int i = 0; i < rows.size(); i++) {
            TableRow row = rows.get(i);
            String label = row.getLabel() == null ? "" : row.getLabel().trim();
            if (label.isEmpty()) continue;
            if (hasAnyNumeric(row)) continue;
            if (matchLevel1SegmentCode(label, cfg) != null) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 找 label 命中的 L1 segment code（大小写不敏感的 alias/segmentName 匹配）。
     */
    private String matchLevel1SegmentCode(String label, CompanyConfig cfg) {
        if (cfg == null || cfg.getSegments() == null) return null;
        for (CompanyConfig.SegmentConfig sc : cfg.getSegments()) {
            if (sc.getLevel() != 1) continue;
            if (!recognizer.match(label, sc.getSegmentCode())) continue;
            // 复用 SegmentRecognizer 的 alias/segmentName contains 逻辑
            return sc.getSegmentCode();
        }
        return null;
    }

    /**
     * 找 label 命中的标准指标 code（按 metricMappingRules.rawMetricNames contains 匹配）。
     * 与 config 里 MetricMappingRule 的定义完全对齐 —— rawMetricNames 里放哪些别名就认哪些。
     */
    private String matchMetricCode(String label, CompanyConfig cfg) {
        if (cfg == null || cfg.getMetricMappingRules() == null) return null;
        String lower = label.toLowerCase();
        for (CompanyConfig.MetricMappingRule rule : cfg.getMetricMappingRules()) {
            List<String> rawNames = rule.getRawMetricNames();
            if (rawNames == null) continue;
            for (String raw : rawNames) {
                if (raw == null || raw.isBlank()) continue;
                if (lower.contains(raw.toLowerCase())) {
                    return rule.getStandardMetricCode();
                }
            }
        }
        return null;
    }

    private static boolean hasAnyNumeric(TableRow row) {
        if (row.getCells() == null) return false;
        for (TableCell c : row.getCells()) {
            if (c != null && c.isNumeric()) return true;
        }
        return false;
    }

    private static List<Double> extractNumericValuesInOrder(TableRow row) {
        List<Double> out = new ArrayList<>();
        List<TableCell> cells = row.getCells();
        if (cells == null) return out;
        for (int i = 0; i < cells.size(); i++) {
            TableCell cell = cells.get(i);
            if (cell == null || !cell.isNumeric()) continue;
            // 跳过百分比（cell 文本自带 %，或者紧邻的下一格是纯 "%"）
            String txt = cell.getText();
            boolean pct = txt != null && txt.contains("%");
            if (!pct && i + 1 < cells.size()) {
                TableCell next = cells.get(i + 1);
                if (next != null && "%".equals(next.getText())) pct = true;
            }
            if (pct) continue;
            out.add(cell.getNumericValue());
        }
        return out;
    }

    /**
     * 从表头前几行构建周期序列。委托给 {@link PeriodSequenceBuilder}（与
     * {@link DataExtractor} 共用同一份 header 识别规则），只在这里指定"贝壳 Q4
     * press release 为主要目标"这一 layout 特定的兜底后缀。
     */
    private List<String> buildPeriodSequence(FinancialTable table) {
        // BEKE 的 4Q press release 为主要目标 —— 表头没识别到分组标记 / 未匹配到列时兜底 Q4。
        return PeriodSequenceBuilder.build(table, "Q4");
    }
}
