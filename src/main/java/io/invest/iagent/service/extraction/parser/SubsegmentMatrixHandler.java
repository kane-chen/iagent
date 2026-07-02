package io.invest.iagent.service.extraction.parser;

import io.invest.iagent.service.extraction.model.CompanyConfig;
import io.invest.iagent.service.extraction.model.Segment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SUBSEGMENT_MATRIX 布局：单一周期的"L1×L2 复合分部表"。
 *
 * <p>典型表结构（美团 Q3 报 page 2）：
 * <pre>
 *              LOCAL_SERVICE  NEW_SERVICE  UNALLOCATED  TOTAL
 *   配送服务       23,021,931       -            -       23,021,931       <- L2 REVENUE
 *   佣金           26,375,119    1,627,723       -       28,002,842       <- L2 REVENUE
 *   在線營銷        14,193,343      133,193       -       14,326,536       <- L2 REVENUE
 *   其他            3,856,473   26,280,331       -       30,136,804       <- L2 REVENUE
 *   分部收入合计   67,446,866   28,041,247       -       95,488,113       <- skip
 *   销售成本+…    (81,517,876) (29,319,156) (4,410,431) (115,247,463)     <- L1 COST
 *   经营利润      (14,071,010)  (1,277,909)  (4,410,431)  (19,759,350)     <- L1 OP_INCOME
 * </pre>
 *
 * <p>关键点：
 * <ul>
 *   <li>列（除标签列外）对应 L1 分部；空/SKIP 位跳过（如"未分配"列）；TOTAL 参与校验</li>
 *   <li>行是异构的：前面 N 行 = L2 子分部（写入 L1 的 children），
 *       后面 M 行 = L1 层指标（COST/OPERATING_INCOME）</li>
 *   <li>整张表共享一个 period（当期或上年同期）</li>
 * </ul>
 *
 * <p>与 SEGMENTS_AS_COLUMNS 的差别：SEGMENTS_AS_COLUMNS 假设所有行都是 L1 指标；
 * SUBSEGMENT_MATRIX 允许部分行落到 L2 子分部里，并按 config 的 parentCode 自动挂到 L1 children 下。
 *
 * <p>匹配约束：
 * <ul>
 *   <li>行长度 = 1 + columnCount（标签列 + 数据列数）</li>
 *   <li>首格是非数字（标签，中文乱码 OK）；后续数据列全部是数字（占位符空串也 OK 但空串不参与写入）</li>
 *   <li>rowDescriptors.size() == 已过滤 dataRows 的行数 —— 严格对齐，避免把无关表误抓</li>
 *   <li>可选：末列 TOTAL 与其他 L1 列之和一致</li>
 * </ul>
 */
final class SubsegmentMatrixHandler implements PdfLayoutHandler {

    private final PdfExtractionSupport support;

    SubsegmentMatrixHandler(PdfExtractionSupport support) {
        this.support = support;
    }

    @Override
    public CompanyConfig.PdfColumnMapping.Layout layout() {
        return CompanyConfig.PdfColumnMapping.Layout.SUBSEGMENT_MATRIX;
    }

    @Override
    public int apply(CompanyConfig.PdfColumnMapping mapping,
                     List<List<String>> dataRows,
                     String tableId, String currency, String unit,
                     PdfReportParser.FilingContext context,
                     Map<String, Segment> sink) {
        List<String> segmentCodes = mapping.getSegmentCodes(); // 列对应的 L1 分部 code
        List<CompanyConfig.PdfColumnMapping.RowDescriptor> rowDescs = mapping.getRowDescriptors();
        int expectedDataCols = mapping.getColumnCount();
        if (segmentCodes == null || segmentCodes.isEmpty()
                || rowDescs == null || rowDescs.isEmpty()
                || expectedDataCols <= 0
                || segmentCodes.size() != expectedDataCols) {
            return 0;
        }

        // 过滤符合结构的行：首格非数字 + 后续 expectedDataCols 列全是"数字或空占位符"
        int totalCols = 1 + expectedDataCols;
        List<List<String>> qualifiedRows = new ArrayList<>();
        for (List<String> row : dataRows) {
            if (row.size() != totalCols) continue;
            if (support.parseNumber(row.get(0)) != null) continue; // 首格必须是标签
            boolean ok = true;
            for (int i = 0; i < expectedDataCols; i++) {
                String cell = row.get(i + 1);
                if (support.parseNumber(cell) == null && !support.isPlaceholderCell(cell)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                qualifiedRows.add(row);
            }
        }
        if (qualifiedRows.size() != rowDescs.size()) {
            return 0;
        }

        // 可选的 TOTAL 列校验：只挑"至少有 2 个 L1 贡献者"的行做校验，
        // 因为顶部 L2 REVENUE 行经常一整列全空（例如"配送服务"只在 LOCAL_SERVICE 有值）。
        int totalIdx = support.lastIndexOfTotal(segmentCodes);
        if (totalIdx >= 0) {
            int passed = 0;
            int attempted = 0;
            for (List<String> row : qualifiedRows) {
                Double totalVal = support.parseNumber(row.get(totalIdx + 1));
                if (totalVal == null) continue;
                double sum = 0;
                int contributors = 0;
                for (int col = 0; col < segmentCodes.size(); col++) {
                    if (col == totalIdx) continue;
                    String code = segmentCodes.get(col);
                    if (support.isSkipColumn(code)) {
                        // "SKIP"（未分配列）仍要计入合计，因为报表里未分配值也参与到 TOTAL
                        // —— 只是我们不写入 segment 里
                    }
                    Double v = support.parseNumber(row.get(col + 1));
                    if (v == null) continue;
                    sum += v;
                    contributors++;
                }
                if (contributors < 2) continue;
                attempted++;
                double diff = Math.abs(sum - totalVal);
                double tolerance = Math.max(1.0, Math.abs(totalVal) * 0.005);
                if (diff <= tolerance) passed++;
            }
            if (attempted == 0 || passed * 2 < attempted) {
                return 0;
            }
        }

        String period = context.resolvePeriod(mapping.getPeriodCode());
        if (period == null || period.isEmpty()) {
            return 0;
        }

        // 建立 L1 分部（segmentsByCode 用于 metric 去重去覆盖）。
        // 对于 L2 行，我们要把数据写到 L1.children 下的 L2 分部里，且每个 L1 各有一份自己的 L2 子树。
        Map<String, Segment> l1Buckets = new LinkedHashMap<>();

        int produced = 0;
        for (int rowIdx = 0; rowIdx < rowDescs.size(); rowIdx++) {
            CompanyConfig.PdfColumnMapping.RowDescriptor desc = rowDescs.get(rowIdx);
            if (desc == null) continue;
            String metricCode = desc.getMetricCode();
            if (metricCode == null || metricCode.isEmpty()) {
                continue; // 跳过占位行（如"分部收入合计"）
            }
            String subCode = desc.getSubSegmentCode();
            List<String> rowCells = qualifiedRows.get(rowIdx);

            for (int colIdx = 0; colIdx < segmentCodes.size(); colIdx++) {
                if (colIdx == totalIdx) continue; // TOTAL 列不写入
                String l1Code = segmentCodes.get(colIdx);
                if (support.isSkipColumn(l1Code)) continue;

                Double value = support.parseNumber(rowCells.get(colIdx + 1));
                if (value == null) continue;
                if (desc.isAbs()) {
                    value = Math.abs(value);
                }

                Segment l1 = l1Buckets.computeIfAbsent(l1Code,
                        code -> createSegment(code, sink));

                if (subCode == null || subCode.isEmpty()) {
                    // 直接落到 L1
                    writeMetricIfAbsent(l1, metricCode, period, value, tableId, currency, unit);
                } else {
                    // 落到该 L1 下的 L2 子分部
                    Segment l2 = findOrCreateChild(l1, subCode);
                    writeMetricIfAbsent(l2, metricCode, period, value, tableId, currency, unit);
                }
                produced++;
            }
        }

        // 合并进 sink：sink 用于跨"多张 SUBSEGMENT_MATRIX 表"（当前 mapping 和 prior mapping）的
        // metric/period 合并去重，也用于让 SegmentMetricUtil.merge 后续处理。
        // 对于同一个 L1，如果 sink 已有则合并 metrics 和 children；否则放入。
        for (Map.Entry<String, Segment> e : l1Buckets.entrySet()) {
            Segment fresh = e.getValue();
            Segment existing = sink.get(e.getKey());
            if (existing == null) {
                sink.put(e.getKey(), fresh);
            } else {
                mergeInto(existing, fresh);
            }
        }
        return produced;
    }

    private Segment createSegment(String code, Map<String, Segment> sinkForNameLookup) {
        Segment s = new Segment();
        s.setSegmentCode(code);
        CompanyConfig.SegmentConfig sc = support.findSegmentConfig(code);
        if (sc != null) {
            s.setSegmentName(sc.getSegmentName());
            s.setLevel(sc.getLevel() <= 0 ? 1 : sc.getLevel());
        } else {
            s.setSegmentName(code);
            s.setLevel(1);
        }
        return s;
    }

    private Segment findOrCreateChild(Segment parent, String childCode) {
        if (parent.getChildren() != null) {
            for (Segment c : parent.getChildren()) {
                if (childCode.equalsIgnoreCase(c.getSegmentCode())) {
                    return c;
                }
            }
        }
        Segment child = new Segment();
        child.setSegmentCode(childCode);
        CompanyConfig.SegmentConfig sc = support.findSegmentConfig(childCode);
        if (sc != null) {
            child.setSegmentName(sc.getSegmentName());
            child.setLevel(sc.getLevel() <= 0 ? 2 : sc.getLevel());
        } else {
            child.setSegmentName(childCode);
            child.setLevel(2);
        }
        parent.addChild(child);
        return child;
    }

    private void writeMetricIfAbsent(Segment segment, String metricCode, String period,
                                     double value, String tableId, String currency, String unit) {
        if (segment.getMetric(metricCode, period) != null) return;
        io.invest.iagent.service.extraction.model.SegmentMetric metric =
                new io.invest.iagent.service.extraction.model.SegmentMetric();
        metric.setMetricCode(metricCode);
        metric.setMetricName(metricCode);
        // 单位归一到 million —— 与 PdfExtractionSupport.addMetric / HTML 路径 DataExtractor 一致，
        // 避免"千元"表出来的原值（如美团分部披露的 81,517,876）与其它表 million 单位混用。
        metric.setValue(PdfExtractionSupport.normalizeToMillion(value, unit));
        metric.setPeriod(period);
        metric.setSourceType("TABLE_EXTRACT");
        metric.setSourceLocation(tableId);
        metric.setCurrency(currency);
        metric.setUnit("million");
        metric.setConfidenceScore(80);
        segment.addMetric(metric);
    }

    /** 把 src 的 metrics/children 并入 dst；已有 (metric,period) 或 childCode 保留 dst 的。 */
    private void mergeInto(Segment dst, Segment src) {
        for (io.invest.iagent.service.extraction.model.SegmentMetric m : src.getMetrics()) {
            if (dst.getMetric(m.getMetricCode(), m.getPeriod()) == null) {
                dst.addMetric(m);
            }
        }
        for (Segment srcChild : src.getChildren()) {
            Segment existing = null;
            for (Segment c : dst.getChildren()) {
                if (srcChild.getSegmentCode().equalsIgnoreCase(c.getSegmentCode())) {
                    existing = c;
                    break;
                }
            }
            if (existing == null) {
                dst.addChild(srcChild);
            } else {
                mergeInto(existing, srcChild);
            }
        }
    }
}
