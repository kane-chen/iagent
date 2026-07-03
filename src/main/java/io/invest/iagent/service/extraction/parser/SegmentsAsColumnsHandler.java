package io.invest.iagent.service.extraction.parser;

import io.invest.iagent.service.extraction.model.CompanyConfig;
import io.invest.iagent.service.extraction.model.Segment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SEGMENTS_AS_COLUMNS：每列一个分部、每行一个指标，单一周期。
 *
 * 例：腾讯财报 page 44 五列 [VAS, ONLINE_ADS, FINTECH, OTHERS, TOTAL]，
 *     多行 [REVENUE, GROSS_PROFIT, ...]，对应当期。
 *
 * 严格要求避免误匹配：
 *   - 行长度恰好等于 segmentCodes.size()
 *   - 整行没有标签列（所有 cell 必须是纯数字或占位符）
 *   - 至少能凑齐 metricCodesByRow.size() 个这种行
 */
final class SegmentsAsColumnsHandler implements PdfLayoutHandler {

    private final PdfExtractionSupport support;

    SegmentsAsColumnsHandler(PdfExtractionSupport support) {
        this.support = support;
    }

    @Override
    public CompanyConfig.PdfColumnMapping.Layout layout() {
        return CompanyConfig.PdfColumnMapping.Layout.SEGMENTS_AS_COLUMNS;
    }

    @Override
    public int apply(CompanyConfig.PdfColumnMapping mapping,
                     List<List<String>> dataRows,
                     String tableId, String currency, String unit,
                     PdfFileSegmentParser.FilingContext context,
                     Map<String, Segment> sink) {
        List<String> segmentCodes = mapping.getSegmentCodes();
        List<String> metricCodes = mapping.getMetricCodesByRow();
        int expectedCols = mapping.getColumnCount();
        if (segmentCodes == null || segmentCodes.isEmpty()
                || metricCodes == null || metricCodes.isEmpty()
                || expectedCols <= 0
                || segmentCodes.size() != expectedCols) {
            return 0;
        }

        // 收集"整行都是数字、列数等于 expectedCols"的行 —— 这条规则只在"分部汇总数字块"出现
        List<List<String>> allNumericRows = new ArrayList<>();
        for (List<String> row : dataRows) {
            if (row.size() != expectedCols) continue;
            if (support.isAllNumericRow(row)) {
                allNumericRows.add(row);
            }
        }
        if (allNumericRows.size() < metricCodes.size()) {
            return 0;
        }

        // 严格一致性校验：每一行中"非 TOTAL 列"之和 ≈ "TOTAL 列"值
        int totalIdx = support.lastIndexOfTotal(segmentCodes);
        if (totalIdx >= 0) {
            int passing = 0;
            for (int rowIdx = 0; rowIdx < metricCodes.size(); rowIdx++) {
                List<String> row = allNumericRows.get(rowIdx);
                if (support.verifyTotalCell(row, totalIdx, segmentCodes)) {
                    passing++;
                }
            }
            // 至少一半的指标行通过校验，才认为这是真的分部表
            if (passing * 2 < metricCodes.size()) {
                return 0;
            }
        }

        String period = context.currentQuarter();
        if (period == null || period.isEmpty()) {
            return 0;
        }

        int produced = 0;
        for (int rowIdx = 0; rowIdx < metricCodes.size(); rowIdx++) {
            String metricCode = metricCodes.get(rowIdx);
            List<String> rowCells = allNumericRows.get(rowIdx);
            for (int colIdx = 0; colIdx < segmentCodes.size(); colIdx++) {
                String segCode = segmentCodes.get(colIdx);
                if (support.isSkipColumn(segCode)) {
                    continue;
                }
                Double value = support.parseNumber(rowCells.get(colIdx));
                if (value == null) {
                    continue;
                }
                support.addMetric(sink, segCode, metricCode, period, value,
                        tableId, currency, unit);
                produced++;
            }
        }
        return produced;
    }
}
