package io.invest.iagent.service.extraction.extractor;

import io.invest.iagent.service.extraction.model.CompanyConfig;
import io.invest.iagent.service.extraction.model.FinancialTable;
import io.invest.iagent.service.extraction.model.Segment;
import io.invest.iagent.service.extraction.model.TableCell;
import io.invest.iagent.service.extraction.model.TableRow;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * TCOM 类"每行 = 一个分部 × 多周期列"的 layout handler（layout id
 * = {@link #LAYOUT_ID}）。
 *
 * <p>携程的 6-K 收入分部表和 BABA/PDD 之类的行列表结构表面相似，但两点不同让它无法直接
 * 走 {@link GenericHtmlLayoutHandler}：</p>
 * <ol>
 *   <li>标题行只有一个 {@code Revenue:}，随后 5 行才是各分部（Accommodation reservation /
 *       Transportation ticketing / Packaged-tour / Corporate travel / Others）；
 *       中间还有 {@code Total revenue}、{@code Less: Sales tax}、{@code Net revenue}
 *       等干扰行。</li>
 *   <li>周期列表述是 {@code Quarter ended September 30, 2024} 而不是
 *       {@code Three Months Ended ...}，且同一季度会同时给一个 {@code USD (million)}
 *       换币列 —— 需要按 config 里的 {@code defaultCurrency} 过滤掉 USD 列。</li>
 * </ol>
 *
 * <p>识别策略：</p>
 * <ol>
 *   <li>{@link PeriodSequenceBuilder#build} 从表头前几行拿到周期序列，默认季度后缀由
 *       {@link PeriodTypeUtil#determinePeriodType} 从标题/正文推断（如 "third quarter" → Q3）；</li>
 *   <li>按列扫描 header row，标出**接受 vs 跳过**的数据列 —— 跳过 header 里出现
 *       {@code USD} 且 {@code defaultCurrency} 不是 USD 的列，其余按 period sequence 顺序对应；</li>
 *   <li>逐行匹配 L1 segment（大小写不敏感的 alias/name contains），取该行按列
 *       编号对齐后的数值，加成 {@code (segmentCode, metricCode, period, value)}。指标 code
 *       通过 {@code metricMappingRules.rawMetricNames} 覆盖某个 label 时匹配；一行只对应一个
 *       segment 时，如果 label 没直接命中 metric（TCOM 每行都是纯 segment 名，无 metric 后缀），
 *       退化为该 config 的 <b>第一条</b> {@code metricMappingRule} 的 standardMetricCode。</li>
 * </ol>
 *
 * <p>只有 {@code companyConfig.htmlLayout == "SEGMENT_ROWS_PERIOD_COLUMNS"} 才命中；
 * 与 {@link SegmentContributionHandler}（BEKE）互斥、与 {@link GenericHtmlLayoutHandler}
 * （BABA/PDD/MSFT）不冲突，存量测试零回归。</p>
 */
@Slf4j
public final class SegmentRowPeriodColumnHandler implements HtmlLayoutHandler {

    /** config.htmlLayout 的取值 —— 与 {@link SegmentContributionHandler#LAYOUT_ID} 平级。 */
    public static final String LAYOUT_ID = "SEGMENT_ROWS_PERIOD_COLUMNS";

    private final HtmlExtractionSupport support;

    public SegmentRowPeriodColumnHandler(HtmlExtractionSupport support) {
        this.support = support;
    }

    @Override
    public int priority() {
        // BEKE 是 100，Generic 是 999；这里 200 —— 排在 SegmentContribution 之后，
        // 但比 Generic 早，让 TCOM 的表格优先命中本 handler，其它公司不受影响。
        return 200;
    }

    @Override
    public boolean supports(FinancialTable table, CompanyConfig companyConfig) {
        if (companyConfig == null) return false;
        if (!LAYOUT_ID.equalsIgnoreCase(companyConfig.getHtmlLayout())) return false;
        if (!support.hasAnyDataRow(table)) return false;
        // 表里至少能找到一行 label 命中 config 中任意 L1 segment
        return findFirstSegmentRow(table, companyConfig) >= 0;
    }

    @Override
    public int apply(FinancialTable table, CompanyConfig companyConfig,
                     Map<String, Segment> sink) {
        String defaultQuarter = PeriodTypeUtil.determinePeriodType(table);
        if (defaultQuarter == null || defaultQuarter.isBlank()) {
            // 从 table.period 或 filename 拿不到时兜底 Q3 —— 与 DataExtractor 保持一致
            defaultQuarter = "Q3";
        }

        List<String> periodSequence = PeriodSequenceBuilder.build(table, defaultQuarter);
        if (periodSequence.isEmpty()) {
            return 0;
        }
        // 每列对应的币种（未识别为 ""）—— 用来在 handler 侧剔除 USD 换币等重复列。
        // 序列与 periodSequence 顺序完全一致（都按"年份 cell 从左到右"）。
        List<String> currencyByPeriod = PeriodSequenceBuilder.buildCurrencies(table, defaultQuarter);
        String defaultCurrency = companyConfig.getDefaultCurrency();
        String unitOverride = companyConfig.getDefaultUnit();

        String fallbackMetricCode = firstMetricCode(companyConfig);
        if (fallbackMetricCode == null) {
            // config 没声明任何 metric —— 无法归类，让给下一个 handler
            return 0;
        }

        int hits = 0;
        for (TableRow row : table.getRows()) {
            String label = row.getLabel() == null ? "" : row.getLabel().trim();
            if (label.isEmpty()) continue;

            String segmentCode = matchLevel1SegmentCode(label, companyConfig);
            if (segmentCode == null) continue;

            // 一行可能声明"某分部的某个 metric"（label 里既有分部名又有 metric 关键词），
            // 也可能是"纯分部名"（TCOM 的分部行 label 只有 Accommodation reservation） —— 后者
            // 按 config 首条 metricMappingRule 兜底为 REVENUE。
            String metricCode = matchMetricCode(label, companyConfig);
            if (metricCode == null) {
                metricCode = fallbackMetricCode;
            }

            Segment currentSeg = support.getOrCreateSegment(sink, companyConfig, segmentCode);
            List<Double> numericInOrder = extractNumericInOrder(row);
            int matchCount = Math.min(numericInOrder.size(), periodSequence.size());
            for (int idx = 0; idx < matchCount; idx++) {
                String period = periodSequence.get(idx);
                Double value = numericInOrder.get(idx);
                if (period == null || period.isEmpty() || value == null) continue;
                // 与 SegmentContributionHandler / DataExtractor 语义一致：只留纯季度/FY
                if (period.endsWith("QTD9") || period.endsWith("QTD6") || period.endsWith("H")) continue;
                if (!currencyAccepted(currencyByPeriod, idx, defaultCurrency)) continue;
                support.addMetric(currentSeg, metricCode, period, value, table, unitOverride);
                hits++;
            }
        }

        if (hits > 0) {
            log.info("SegmentRowPeriodColumnHandler consumed table {} → +{} metrics",
                    table.getTableId(), hits);
        }
        return hits;
    }

    // ---- 内部辅助 -------------------------------------------------------------

    private int findFirstSegmentRow(FinancialTable table, CompanyConfig cfg) {
        List<TableRow> rows = table.getRows();
        for (int i = 0; i < rows.size(); i++) {
            TableRow row = rows.get(i);
            String label = row.getLabel() == null ? "" : row.getLabel().trim();
            if (label.isEmpty()) continue;
            if (matchLevel1SegmentCode(label, cfg) != null) {
                return i;
            }
        }
        return -1;
    }

    /**
     * L1 segment 匹配 —— 大小写不敏感的 segmentCode / segmentName / aliases contains 匹配。
     * 精确度优先：完全等值 > 别名等值 > 名称 contains，避免 "Ticket" 意外命中
     * "Transportation ticketing" 之外的行。
     */
    private String matchLevel1SegmentCode(String label, CompanyConfig cfg) {
        if (cfg == null || cfg.getSegments() == null || label == null) return null;
        String labelLower = label.toLowerCase();
        // 1) 别名或 segmentName 完全等值
        for (CompanyConfig.SegmentConfig sc : cfg.getSegments()) {
            if (sc.getLevel() != 1) continue;
            if (equalsIgnoreCase(sc.getSegmentName(), label)) return sc.getSegmentCode();
            if (sc.getAliases() != null) {
                for (String alias : sc.getAliases()) {
                    if (equalsIgnoreCase(alias, label)) return sc.getSegmentCode();
                }
            }
        }
        // 2) segmentName / aliases contains
        for (CompanyConfig.SegmentConfig sc : cfg.getSegments()) {
            if (sc.getLevel() != 1) continue;
            if (containsIgnoreCase(labelLower, sc.getSegmentName())) return sc.getSegmentCode();
            if (sc.getAliases() != null) {
                for (String alias : sc.getAliases()) {
                    if (containsIgnoreCase(labelLower, alias)) return sc.getSegmentCode();
                }
            }
        }
        return null;
    }

    /**
     * label 里是否显式包含 metric 关键词（走 config.metricMappingRules）。找不到返回 null，
     * 由调用方按 fallback 处理。
     */
    private String matchMetricCode(String label, CompanyConfig cfg) {
        if (cfg == null || cfg.getMetricMappingRules() == null || label == null) return null;
        String lower = label.toLowerCase();
        for (CompanyConfig.MetricMappingRule rule : cfg.getMetricMappingRules()) {
            List<String> rawNames = rule.getRawMetricNames();
            if (rawNames == null) continue;
            for (String raw : rawNames) {
                if (raw == null || raw.isBlank()) continue;
                if (lower.contains(raw.toLowerCase())) return rule.getStandardMetricCode();
            }
        }
        return null;
    }

    private static String firstMetricCode(CompanyConfig cfg) {
        if (cfg == null || cfg.getMetricMappingRules() == null
                || cfg.getMetricMappingRules().isEmpty()) return null;
        return cfg.getMetricMappingRules().get(0).getStandardMetricCode();
    }

    /**
     * 判断第 idx 个 period 列（{@link PeriodSequenceBuilder#buildCurrencies} 输出的顺序）
     * 是否应被接受。规则：
     * <ul>
     *   <li>defaultCurrency 为空 → 保守全接受（不做币种过滤）</li>
     *   <li>currencyByPeriod 里对应位置为空 → 表头没显式标注币种，也接受</li>
     *   <li>值与 defaultCurrency 一致 → 接受</li>
     *   <li>值与 defaultCurrency 不一致 → 拒绝（例如 TCOM 每季度的 USD 换币列）</li>
     * </ul>
     */
    private static boolean currencyAccepted(List<String> currencyByPeriod, int idx,
                                            String defaultCurrency) {
        if (defaultCurrency == null || defaultCurrency.isBlank()) return true;
        if (idx < 0 || idx >= currencyByPeriod.size()) return true;
        String cur = currencyByPeriod.get(idx);
        if (cur == null || cur.isEmpty()) return true;
        return cur.equalsIgnoreCase(defaultCurrency);
    }

    /**
     * 按出现顺序抽取该行的数值 cell（跳过百分比、非数值）。与 period sequence 一一对应，
     * 币种过滤在调用方按 idx 完成 —— 避免在这里凭列索引猜列位置。
     */
    private static List<Double> extractNumericInOrder(TableRow row) {
        List<Double> out = new ArrayList<>();
        List<TableCell> cells = row.getCells();
        if (cells == null) return out;
        for (int i = 0; i < cells.size(); i++) {
            TableCell cell = cells.get(i);
            if (cell == null || !cell.isNumeric()) continue;
            String txt = cell.getText();
            if (txt != null && txt.contains("%")) continue;
            out.add(cell.getNumericValue());
        }
        return out;
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private static boolean containsIgnoreCase(String labelLower, String needle) {
        if (needle == null || needle.isBlank()) return false;
        return labelLower.contains(needle.toLowerCase());
    }
}
