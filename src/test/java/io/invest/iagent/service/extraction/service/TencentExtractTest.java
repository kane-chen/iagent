package io.invest.iagent.service.extraction.service;

import io.invest.iagent.service.extraction.model.Segment;
import io.invest.iagent.service.extraction.model.SegmentMetric;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class TencentExtractTest {

    private final Path workspace = Paths.get(System.getProperty("user.dir")).resolve("workspace");

    @Test
    public void extract_tencent() throws Exception {
        FinancialExtractionService service = new FinancialExtractionService("00700", workspace);
        List<Segment> segments = service.extractFromHtmlFile("00700", null, null);

        System.out.println("Found " + segments.size() + " segments");
        printSegments(segments, 0);
    }

    private void printSegments(List<Segment> segments, int depth) {
        String indent = "  ".repeat(depth);
        for (Segment seg : segments) {
            System.out.println(indent + "Segment: " + seg.getSegmentCode() + " (" + seg.getSegmentName() + ")");
            for (SegmentMetric m : seg.getMetrics()) {
                System.out.printf(indent + "  %s: %s=%.0f%n",
                        m.getPeriod(), m.getMetricCode(), m.getValue());
            }
            if (seg.getChildren() != null && !seg.getChildren().isEmpty()) {
                printSegments(seg.getChildren(), depth + 1);
            }
        }
    }
}
