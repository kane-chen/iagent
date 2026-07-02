package io.invest.iagent.service.extraction.extractor;

import io.invest.iagent.service.extraction.mapper.MetricMapper;
import io.invest.iagent.service.extraction.model.CompanyConfig;
import io.invest.iagent.service.extraction.model.FinancialTable;
import io.invest.iagent.service.extraction.model.MetricDict;
import io.invest.iagent.service.extraction.model.Segment;
import io.invest.iagent.service.extraction.model.SegmentMetric;

import java.util.List;
import java.util.Map;

/**
 * HTML 抽取路径的公共工具。
 *
 * <p>参照 {@code PdfExtractionSupport} 的分层思路：与 layout 无关的"数字规则"和
 * "Segment/Metric 组装"抽到这里，让不同的 {@link HtmlLayoutHandler} 复用同一份底层校验，
 * 免得每个 handler 都重复实现单位归一、去重写入这类样板逻辑。</p>
 */
public final class HtmlExtractionSupport {

    private final MetricMapper metricMapper;

    public HtmlExtractionSupport(MetricMapper metricMapper) {
        this.metricMapper = metricMapper;
    }

    /**
     * 找 config 里指定 code 的 SegmentConfig（大小写不敏感），找不到返回 null。
     */
    public CompanyConfig.SegmentConfig findSegmentConfig(CompanyConfig cfg, String segmentCode) {
        if (cfg == null || cfg.getSegments() == null || segmentCode == null) {
            return null;
        }
        for (CompanyConfig.SegmentConfig sc : cfg.getSegments()) {
            if (sc.getSegmentCode() != null
                    && sc.getSegmentCode().equalsIgnoreCase(segmentCode)) {
                return sc;
            }
        }
        return null;
    }

    /**
     * 在 sink 里按 segmentCode 找到或新建 Segment（首次创建时依据 config 补 name/level）。
     */
    public Segment getOrCreateSegment(Map<String, Segment> sink, CompanyConfig cfg,
                                      String segmentCode) {
        return sink.computeIfAbsent(segmentCode, code -> {
            Segment s = new Segment();
            s.setSegmentCode(code);
            CompanyConfig.SegmentConfig sc = findSegmentConfig(cfg, code);
            if (sc != null) {
                s.setSegmentName(sc.getSegmentName());
                s.setLevel(sc.getLevel() <= 0 ? 1 : sc.getLevel());
            } else {
                s.setSegmentName(code);
                s.setLevel(1);
            }
            return s;
        });
    }

    /**
     * 把一条 metric 加到 segment；已存在 (metricCode, period) 保留第一份（幂等）。
     * 值统一按 {@code table.getUnit()} 归一到 million 后写入，与 PDF 路径行为一致。
     */
    public void addMetric(Segment segment, String metricCode, String period,
                          double rawValue, FinancialTable table) {
        addMetric(segment, metricCode, period, rawValue, table, null);
    }

    /**
     * 带 unit override 的重载：BEKE 这类"分部块表"里，HtmlReportParser 自动识别的单位
     * 会被表格标题/正文里其它上下文误导（正文里到处是 "$XXX million" 的叙述），实际上
     * 表内数字才是"in thousands"。此时由 handler 从 config.defaultUnit 显式覆盖单位，
     * 优先级高于 table.getUnit()。
     */
    public void addMetric(Segment segment, String metricCode, String period,
                          double rawValue, FinancialTable table, String unitOverride) {
        if (segment.getMetric(metricCode, period) != null) {
            return;
        }
        String unit = (unitOverride != null && !unitOverride.isBlank())
                ? unitOverride : table.getUnit();
        SegmentMetric metric = new SegmentMetric();
        metric.setMetricCode(metricCode);
        MetricDict dict = metricMapper.getMetricByCode(metricCode);
        metric.setMetricName(dict != null ? dict.getMetricName() : metricCode);
        metric.setValue(normalizeToMillion(rawValue, unit));
        metric.setPeriod(period);
        metric.setSourceType("TABLE_EXTRACT");
        metric.setSourceLocation(table.getTableId());
        metric.setCurrency(table.getCurrency());
        metric.setUnit("million");
        metric.setConfidenceScore(85);
        segment.addMetric(metric);
    }

    /**
     * 数值归一到 million：与 {@code DataExtractor.normalizeToMillion} /
     * {@code PdfExtractionSupport.normalizeToMillion} 保持完全一致的语义。
     *
     * <p>{@code thousand → million} 用截断（toward zero），而不是 Math.floor：
     * 负数 {@code -3240687 / 1000 → -3240}，如果用 floor 会得到 -3241，与业务侧
     * 对"千元 ÷ 1000"的直观理解不一致。</p>
     */
    public static double normalizeToMillion(double value, String unit) {
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

    /** 供子类 dispatch 前问一句：这张表基本合法（有数据行）。 */
    public boolean hasAnyDataRow(FinancialTable table) {
        if (table == null || table.getRows() == null) return false;
        return !table.getRows().isEmpty();
    }

    /** 把 rows 转成扁平 label 列表，方便 handler 做序列扫描。 */
    public List<String> collectLabels(FinancialTable table) {
        return table.getRows().stream()
                .map(r -> r.getLabel() == null ? "" : r.getLabel().trim())
                .toList();
    }
}
