package io.invest.iagent.service.extraction.service;

import io.invest.iagent.service.extraction.model.Segment;
import io.invest.iagent.service.extraction.model.SegmentMetric;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 验证Google提取后的 EBITA 利润率合理性
 * - 不应该出现 >100% 的利润率
 * - 同一分部的 REVENUE 和 OPERATING_INCOME 不应相等
 */
public class GoogleMarginSanityTest {

    private final Path workspace = Paths.get(System.getProperty("user.dir")).resolve("workspace");

    @Test
    public void verifyGoogleMargins() throws IOException {
        FinancialExtractionService service = new FinancialExtractionService("GOOGL", workspace);
        List<Segment> segments = service.extractFromHtmlFile("GOOGL", null, null);

        int badMarginCount = 0;
        int sameRevEbitaCount = 0;
        StringBuilder report = new StringBuilder();
        report.append("=== Margin sanity check ===\n");

        for (Segment seg : flatten(segments)) {
            Map<String, Map<String, Double>> byPeriod = new HashMap<>();
            for (SegmentMetric m : seg.getMetrics()) {
                byPeriod.computeIfAbsent(m.getPeriod(), k -> new HashMap<>())
                        .put(m.getMetricCode(), m.getValue());
            }
            for (Map.Entry<String, Map<String, Double>> e : byPeriod.entrySet()) {
                Double rev = e.getValue().get("REVENUE");
                Double opi = e.getValue().get("OPERATING_INCOME");
                if (rev == null || opi == null || rev == 0) continue;
                double margin = (opi / rev) * 100;
                // Other Bets 是亏损业务，margin可以为负
                if (!"OTHER_BETS".equals(seg.getSegmentCode())) {
                    if (margin > 100 || margin < -100) {
                        badMarginCount++;
                        report.append(String.format("BAD: %s %s margin=%.1f%% (rev=%.0f, opi=%.0f)\n",
                                seg.getSegmentCode(), e.getKey(), margin, rev, opi));
                    }
                }
                if (rev.equals(opi)) {
                    sameRevEbitaCount++;
                    report.append(String.format("SAME: %s %s rev=opi=%.0f\n",
                            seg.getSegmentCode(), e.getKey(), rev));
                }
            }
        }

        System.out.println(report);
        org.junit.jupiter.api.Assertions.assertEquals(0, badMarginCount,
                "应无 >100% 利润率 (except Other Bets)");
        org.junit.jupiter.api.Assertions.assertEquals(0, sameRevEbitaCount,
                "REVENUE 不应等于 OPERATING_INCOME");
    }

    private List<Segment> flatten(List<Segment> segments) {
        List<Segment> result = new java.util.ArrayList<>();
        for (Segment s : segments) {
            result.add(s);
            if (s.getChildren() != null && !s.getChildren().isEmpty()) {
                result.addAll(flatten(s.getChildren()));
            }
        }
        return result;
    }
}
