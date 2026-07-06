# -*- coding: utf-8 -*-
"""SegmentsAsColumnsHandler：列=segments、行=metrics、单周期（腾讯收入/毛利块）。"""
from __future__ import annotations

from typing import Dict, List

from ..model import Layout, Segment
from ..pdf_layout_handler import PdfLayoutHandler
from ..pdf_support import PdfExtractionSupport


class SegmentsAsColumnsHandler(PdfLayoutHandler):
    def __init__(self, support: PdfExtractionSupport):
        self.support = support

    def layout(self) -> Layout:
        return Layout.SEGMENTS_AS_COLUMNS

    def apply(self, mapping, data_rows: List[List[str]],
              table_id: str, currency: str, unit: str,
              context, sink: Dict[str, Segment]) -> int:
        segment_codes = mapping.segmentCodes
        metric_codes = mapping.metricCodesByRow
        expected_cols = mapping.columnCount
        if (not segment_codes or not metric_codes or expected_cols <= 0
                or len(segment_codes) != expected_cols):
            return 0

        all_numeric_rows: List[List[str]] = []
        for row in data_rows:
            if len(row) != expected_cols:
                continue
            if self.support.isAllNumericRow(row):
                all_numeric_rows.append(row)
        if len(all_numeric_rows) < len(metric_codes):
            return 0

        total_idx = self.support.lastIndexOfTotal(segment_codes)
        if total_idx >= 0:
            passing = 0
            for row_idx in range(len(metric_codes)):
                row = all_numeric_rows[row_idx]
                if self.support.verifyTotalCell(row, total_idx, segment_codes):
                    passing += 1
            if passing * 2 < len(metric_codes):
                return 0

        period = context.currentQuarter()
        if not period:
            return 0

        produced = 0
        for row_idx, metric_code in enumerate(metric_codes):
            row_cells = all_numeric_rows[row_idx]
            for col_idx, seg_code in enumerate(segment_codes):
                if self.support.isSkipColumn(seg_code):
                    continue
                value = self.support.parseNumber(row_cells[col_idx])
                if value is None:
                    continue
                self.support.addMetric(sink, seg_code, metric_code, period, value,
                                       table_id, currency, unit)
                produced += 1
        return produced
