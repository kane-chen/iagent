package io.invest.iagent.service.extraction.parser;

import io.invest.iagent.service.extraction.model.CompanyConfig;
import io.invest.iagent.service.extraction.model.Segment;
import io.invest.iagent.service.extraction.model.SegmentMetric;

import java.util.List;
import java.util.Map;

/**
 * PDF 分部表提取的公共工具。
 *
 * 从早期实现（{@link PdfFileSegmentParser} 单文件）里抽离出来的"与 layout 无关"的部分：
 * <ul>
 *   <li>数值解析、空占位符判定</li>
 *   <li>分部表的合计行/合计列一致性校验</li>
 *   <li>{@link Segment} / {@link SegmentMetric} 组装与去重写入</li>
 * </ul>
 *
 * 目的：让不同 {@link PdfLayoutHandler} 的实现共享同一份"数字规则"，
 * 每张公司报表可以按需组合自己的 layout handler，而不用重复实现这些低层校验。
 */
final class PdfExtractionSupport {

    private final CompanyConfig companyConfig;

    PdfExtractionSupport(CompanyConfig companyConfig) {
        this.companyConfig = companyConfig;
    }

    // ------------- 数值 / 占位符 -------------

    /**
     * 解析数值：去千分位、负号、括号负数、空格。无法解析返回 null。
     */
    Double parseNumber(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        // 跳过明显非数字（百分号、占位破折号）
        if (t.endsWith("%") || "-".equals(t) || "–".equals(t) || "—".equals(t) || "*".equals(t)) {
            return null;
        }
        boolean negative = false;
        if (t.startsWith("(") && t.endsWith(")")) {
            negative = true;
            t = t.substring(1, t.length() - 1);
        }
        t = t.replace(",", "").replace(" ", "");
        if (t.startsWith("-")) {
            negative = true;
            t = t.substring(1);
        }
        if (t.isEmpty()) return null;
        try {
            double v = Double.parseDouble(t);
            return negative ? -v : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    boolean isPlaceholderCell(String c) {
        if (c == null) return true;
        String s = c.trim();
        return s.isEmpty() || "-".equals(s) || "–".equals(s) || "—".equals(s) || "*".equals(s);
    }

    boolean isSkipColumn(String segCode) {
        if (segCode == null || segCode.isEmpty()) return true;
        return "TOTAL".equalsIgnoreCase(segCode) || "SKIP".equalsIgnoreCase(segCode);
    }

    /** 整行单元格全部是数字（允许个别空串/占位符）；至少要有 2 个真数字才算数。 */
    boolean isAllNumericRow(List<String> row) {
        int numeric = 0;
        for (String c : row) {
            if (parseNumber(c) != null) {
                numeric++;
            } else if (!isPlaceholderCell(c)) {
                return false;
            }
        }
        return numeric >= 2;
    }

    // ------------- TOTAL 一致性校验 -------------

    /**
     * 找 segmentCodes 里 "TOTAL" 占位符的下标（不区分大小写）；找不到返回 -1。
     */
    int lastIndexOfTotal(List<String> segmentCodes) {
        for (int i = segmentCodes.size() - 1; i >= 0; i--) {
            String c = segmentCodes.get(i);
            if (c != null && "TOTAL".equalsIgnoreCase(c)) return i;
        }
        return -1;
    }

    /**
     * 校验单行：除 totalIdx 列外其他分部列之和 ≈ totalIdx 列的值。
     * 用于 SEGMENTS_AS_COLUMNS 布局的"合计列一致性"检测。
     */
    boolean verifyTotalCell(List<String> row, int totalIdx, List<String> segmentCodes) {
        if (totalIdx < 0 || totalIdx >= row.size()) return false;
        Double totalVal = parseNumber(row.get(totalIdx));
        if (totalVal == null) return false;
        double sum = 0;
        int contributors = 0;
        for (int col = 0; col < segmentCodes.size() && col < row.size(); col++) {
            if (col == totalIdx) continue;
            String code = segmentCodes.get(col);
            if (code == null || code.isEmpty() || "TOTAL".equalsIgnoreCase(code)) continue;
            Double v = parseNumber(row.get(col));
            if (v == null) continue;
            sum += v;
            contributors++;
        }
        if (contributors < 2) return false;
        double diff = Math.abs(sum - totalVal);
        double tolerance = Math.max(1.0, Math.abs(totalVal) * 0.005);
        return diff <= tolerance;
    }

    /**
     * 校验"合计行" qualifiedRows.get(totalIdx) 的每一列值 ≈ 同列其他行之和。
     * 容忍 ±1（int 四舍五入）和 ±0.5% 的小幅误差。
     * 至少要求一列通过校验；全部失败则视为"不是分部表"。
     *
     * dataStartCol：数据列在 row 里从哪一列开始（SEGMENTS_AS_ROWS 场景 = 1，跳过标签列；
     * SUBSEGMENT_MATRIX 场景可能 = 1）。
     */
    boolean verifyTotalRow(List<List<String>> qualifiedRows, int totalIdx,
                           int dataCols, int dataStartCol, List<String> segmentCodes) {
        List<String> totalRow = qualifiedRows.get(totalIdx);
        int passedColumns = 0;
        for (int col = 0; col < dataCols; col++) {
            Double totalVal = parseNumber(totalRow.get(col + dataStartCol));
            if (totalVal == null) continue;
            double sum = 0;
            int contributors = 0;
            for (int r = 0; r < segmentCodes.size() && r < qualifiedRows.size(); r++) {
                if (r == totalIdx) continue;
                String code = segmentCodes.get(r);
                if (code == null || code.isEmpty() || "TOTAL".equalsIgnoreCase(code)) continue;
                Double v = parseNumber(qualifiedRows.get(r).get(col + dataStartCol));
                if (v == null) continue;
                sum += v;
                contributors++;
            }
            if (contributors < 2) continue;
            double diff = Math.abs(sum - totalVal);
            double tolerance = Math.max(1.0, Math.abs(totalVal) * 0.005);
            if (diff <= tolerance) {
                passedColumns++;
            }
        }
        return passedColumns >= 1;
    }

    // ------------- Segment 组装 -------------

    /**
     * 把一条指标加到 segmentsByCode 里。如果该 (segment, metric, period) 已经存在，
     * 则保留第一份（多个表可能重复抓到同一格数据）。
     *
     * <p>单位归一：与 HTML 抽取路径一致，所有指标最终写入前统一换算成 million。
     * 港股财报常见"千元 / thousand"表述（如美团业绩公告 page 2 的分部披露），
     * 若不做归一化，Excel/DTO 会混着 million 和 thousand，下游对比、聚合都会出错。
     *
     * @param abs 若为 true，写入前对 value 取绝对值（COST 类负值列常用）
     */
    void addMetric(Map<String, Segment> segmentsByCode,
                   String segCode, String metricCode, String period, double value,
                   String tableId, String currency, String unit, boolean abs) {
        if (abs) {
            value = Math.abs(value);
        }
        Segment segment = segmentsByCode.computeIfAbsent(segCode, code -> {
            Segment s = new Segment();
            s.setSegmentCode(code);
            CompanyConfig.SegmentConfig sc = findSegmentConfig(code);
            if (sc != null) {
                s.setSegmentName(sc.getSegmentName());
                s.setLevel(sc.getLevel() <= 0 ? 1 : sc.getLevel());
            } else {
                s.setSegmentName(code);
                s.setLevel(1);
            }
            return s;
        });

        if (segment.getMetric(metricCode, period) != null) {
            return;
        }

        SegmentMetric metric = new SegmentMetric();
        metric.setMetricCode(metricCode);
        metric.setMetricName(metricCode);
        metric.setValue(normalizeToMillion(value, unit));
        metric.setPeriod(period);
        metric.setSourceType("TABLE_EXTRACT");
        metric.setSourceLocation(tableId);
        metric.setCurrency(currency);
        metric.setUnit("million");
        metric.setConfidenceScore(80);
        segment.addMetric(metric);
    }

    /** 无 abs 重载，默认不取绝对值。 */
    void addMetric(Map<String, Segment> segmentsByCode,
                   String segCode, String metricCode, String period, double value,
                   String tableId, String currency, String unit) {
        addMetric(segmentsByCode, segCode, metricCode, period, value, tableId, currency, unit, false);
    }

    /**
     * 直接往一个已存在的 Segment 写入 metric（不经过 segmentsByCode 查找/创建），
     * 适用于 SubsegmentMatrixHandler 这种 L1/L2 分段桶场景。已有 (metricCode, period) 跳过。
     */
    void addMetricToSegment(Segment segment, String metricCode, String period, double value,
                            String tableId, String currency, String unit, boolean abs) {
        if (segment == null) return;
        if (segment.getMetric(metricCode, period) != null) return;
        double v = abs ? Math.abs(value) : value;
        SegmentMetric metric = new SegmentMetric();
        metric.setMetricCode(metricCode);
        metric.setMetricName(metricCode);
        metric.setValue(normalizeToMillion(v, unit));
        metric.setPeriod(period);
        metric.setSourceType("TABLE_EXTRACT");
        metric.setSourceLocation(tableId);
        metric.setCurrency(currency);
        metric.setUnit("million");
        metric.setConfidenceScore(80);
        segment.addMetric(metric);
    }

    /**
     * 数值单位归一到 million：与 {@link io.invest.iagent.service.extraction.extractor.HtmlExtractionSupport#normalizeToMillion(double, String)}
     * 语义一致，使用 trunc-toward-zero 避免 thousand→million 在负数时 floor 偏差。
     * <ul>
     *   <li>{@code null/空/million/百万} → 保持原值</li>
     *   <li>{@code thousand/千} → 除以 1000 后向零截断</li>
     *   <li>{@code billion/十亿} → 乘以 1000 后向零截断</li>
     *   <li>其它未知 → 保持原值（假设已经是 million）</li>
     * </ul>
     */
    static double normalizeToMillion(double value, String unit) {
        if (unit == null || unit.isBlank()) {
            return value;
        }
        String lower = unit.toLowerCase().trim();
        if (lower.contains("million") || lower.contains("百万") || lower.contains("百萬")) {
            return value;
        }
        if (lower.contains("thousand") || lower.contains("千")) {
            return truncTowardZero(value / 1000.0);
        }
        if (lower.contains("billion") || lower.contains("十亿") || lower.contains("十億")) {
            return truncTowardZero(value * 1000.0);
        }
        return value;
    }

    private static double truncTowardZero(double v) {
        return v < 0 ? Math.ceil(v) : Math.floor(v);
    }

    CompanyConfig.SegmentConfig findSegmentConfig(String segmentCode) {
        if (companyConfig == null || companyConfig.getSegments() == null) {
            return null;
        }
        for (CompanyConfig.SegmentConfig sc : companyConfig.getSegments()) {
            if (sc.getSegmentCode() != null && sc.getSegmentCode().equalsIgnoreCase(segmentCode)) {
                return sc;
            }
        }
        return null;
    }
}
