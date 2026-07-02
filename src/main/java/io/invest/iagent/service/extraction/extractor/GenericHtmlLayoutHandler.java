package io.invest.iagent.service.extraction.extractor;

import io.invest.iagent.service.extraction.model.CompanyConfig;
import io.invest.iagent.service.extraction.model.FinancialTable;
import io.invest.iagent.service.extraction.model.Segment;
import io.invest.iagent.service.extraction.model.SegmentMetric;

import java.util.List;
import java.util.Map;

/**
 * 泛用兜底 handler：委托给现有的 {@link DataExtractor#extractSegmentData} —— BABA/PDD/MSFT/GOOG/
 * NVDA/AAPL 这些行列式分部表就走它。逻辑完全不动，保证既有测试不回归。
 *
 * <p>优先级 999，永远最后一个被尝试；只有前面的特化 handler 都没吃这张表时才轮到。</p>
 */
public final class GenericHtmlLayoutHandler implements HtmlLayoutHandler {

    private final DataExtractor dataExtractor;

    public GenericHtmlLayoutHandler(DataExtractor dataExtractor) {
        this.dataExtractor = dataExtractor;
    }

    @Override
    public int priority() {
        return 999;
    }

    @Override
    public boolean supports(FinancialTable table, CompanyConfig companyConfig) {
        return true; // 兜底
    }

    @Override
    public int apply(FinancialTable table, CompanyConfig companyConfig,
                     Map<String, Segment> sink) {
        List<Segment> segments = dataExtractor.extractSegmentData(table);
        if (segments == null || segments.isEmpty()) {
            return 0;
        }
        int hits = 0;
        for (Segment src : segments) {
            String code = src.getSegmentCode();
            if (code == null || code.isBlank()) continue;
            Segment dst = sink.get(code);
            if (dst == null) {
                sink.put(code, src);
                hits += countMetricsRecursive(src);
            } else {
                hits += mergeInto(dst, src);
            }
        }
        return hits;
    }

    private int mergeInto(Segment dst, Segment src) {
        int added = 0;
        for (SegmentMetric m : src.getMetrics()) {
            if (dst.getMetric(m.getMetricCode(), m.getPeriod()) == null) {
                dst.addMetric(m);
                added++;
            }
        }
        for (Segment srcChild : src.getChildren()) {
            Segment existingChild = null;
            for (Segment c : dst.getChildren()) {
                if (srcChild.getSegmentCode() != null
                        && srcChild.getSegmentCode().equalsIgnoreCase(c.getSegmentCode())) {
                    existingChild = c;
                    break;
                }
            }
            if (existingChild == null) {
                dst.addChild(srcChild);
                added += countMetricsRecursive(srcChild);
            } else {
                added += mergeInto(existingChild, srcChild);
            }
        }
        return added;
    }

    private int countMetricsRecursive(Segment s) {
        int n = s.getMetrics().size();
        for (Segment c : s.getChildren()) n += countMetricsRecursive(c);
        return n;
    }
}
