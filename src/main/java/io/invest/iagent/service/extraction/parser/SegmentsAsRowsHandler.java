package io.invest.iagent.service.extraction.parser;

import io.invest.iagent.service.extraction.model.CompanyConfig;
import io.invest.iagent.service.extraction.model.Segment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SEGMENTS_AS_ROWS：每行=分部、每列=周期，单一指标。
 *
 * 例：腾讯财报 page 46 五行 [VAS, ONLINE_ADS, FINTECH, OTHERS, TOTAL]，
 *     每列一个周期。首列是分部标签（中文乱码无法识别）。
 *
 * mapping.columnCount = 标签列之后的数据列数（不含标签列）。
 *
 * 严格要求避免误匹配：
 *   - 行总长度 == 1 + columnCount
 *   - 首格是非数字（标签）
 *   - 后续 columnCount 格全部是数字
 *   - 至少凑齐 segmentCodes.size() 这种行
 */
final class SegmentsAsRowsHandler implements PdfLayoutHandler {

    private final PdfExtractionSupport support;

    SegmentsAsRowsHandler(PdfExtractionSupport support) {
        this.support = support;
    }

    @Override
    public CompanyConfig.PdfColumnMapping.Layout layout() {
        return CompanyConfig.PdfColumnMapping.Layout.SEGMENTS_AS_ROWS;
    }

    @Override
    public int apply(CompanyConfig.PdfColumnMapping mapping,
                     List<List<String>> dataRows,
                     String tableId, String currency, String unit,
                     PdfReportParser.FilingContext context,
                     Map<String, Segment> sink) {
        List<String> segmentCodes = mapping.getSegmentCodes();
        List<String> periodCodes = mapping.getPeriodCodesByColumn();
        String metricCode = mapping.getMetricCode();
        int expectedDataCols = mapping.getColumnCount();
        if (segmentCodes == null || segmentCodes.isEmpty()
                || periodCodes == null || periodCodes.isEmpty()
                || metricCode == null || expectedDataCols <= 0
                || periodCodes.size() != expectedDataCols) {
            return 0;
        }

        int totalCols = 1 + expectedDataCols;
        List<List<String>> qualifiedRows = new ArrayList<>();
        for (List<String> row : dataRows) {
            if (row.size() != totalCols) continue;
            // 首格必须不是数字（必须是标签 —— 中文乱码也算"非数字"）
            if (support.parseNumber(row.get(0)) != null) continue;
            // 我们只要求"需要取的列"（periodCodes 里非空的位置）是数字；
            // 其他列允许是 % / 空 / 占位符
            boolean validForMapping = true;
            for (int i = 0; i < expectedDataCols; i++) {
                String code = periodCodes.get(i);
                String cell = row.get(i + 1);
                if (code == null || code.isEmpty()) {
                    continue;
                }
                if (support.parseNumber(cell) == null) {
                    validForMapping = false;
                    break;
                }
            }
            if (validForMapping) {
                qualifiedRows.add(row);
            }
        }
        if (qualifiedRows.size() < segmentCodes.size()) {
            return 0;
        }

        // 严格一致性校验：合计行的每一列值 ≈ 同列其他行之和
        int totalIdx = support.lastIndexOfTotal(segmentCodes);
        if (totalIdx >= 0 && totalIdx < qualifiedRows.size()) {
            if (!support.verifyTotalRow(qualifiedRows, totalIdx, expectedDataCols, 1, segmentCodes)) {
                return 0;
            }
        }

        // 解析每列对应的 period
        List<String> resolvedPeriods = new ArrayList<>();
        for (String code : periodCodes) {
            resolvedPeriods.add(context.resolvePeriod(code));
        }

        int produced = 0;
        for (int rowIdx = 0; rowIdx < segmentCodes.size(); rowIdx++) {
            String segCode = segmentCodes.get(rowIdx);
            if (support.isSkipColumn(segCode)) {
                continue;
            }
            List<String> rowCells = qualifiedRows.get(rowIdx);
            for (int colIdx = 0; colIdx < expectedDataCols; colIdx++) {
                String period = resolvedPeriods.get(colIdx);
                if (period == null || period.isEmpty()) {
                    continue;
                }
                Double value = support.parseNumber(rowCells.get(colIdx + 1)); // +1 跳过 label
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
