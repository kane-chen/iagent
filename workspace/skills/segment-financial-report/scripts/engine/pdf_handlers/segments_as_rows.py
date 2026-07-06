# -*- coding: utf-8 -*-
"""SegmentsAsRowsHandler：行=segments、列=periods、单指标（腾讯多周期块）。"""
from __future__ import annotations

from typing import Dict, List

from ..model import Layout, Segment
from ..pdf_layout_handler import PdfLayoutHandler
from ..pdf_support import PdfExtractionSupport


class SegmentsAsRowsHandler(PdfLayoutHandler):
    def __init__(self, support: PdfExtractionSupport):
        self.support = support

    def layout(self) -> Layout:
        return Layout.SEGMENTS_AS_ROWS

    def apply(self, mapping, data_rows: List[List[str]],
              table_id: str, currency: str, unit: str,
              context, sink: Dict[str, Segment]) -> int:
        segment_codes = mapping.segmentCodes
        period_codes = mapping.periodCodesByColumn
        metric_code = mapping.metricCode
        expected_data_cols = mapping.columnCount
        if (not segment_codes or not period_codes or not metric_code
                or expected_data_cols <= 0
                or len(period_codes) != expected_data_cols):
            return 0

        total_cols = 1 + expected_data_cols
        qualified: List[List[str]] = []
        for row in data_rows:
            if len(row) != total_cols:
                continue
            if self.support.parseNumber(row[0]) is not None:
                continue  # first cell must be a non-numeric label
            valid = True
            for i in range(expected_data_cols):
                code = period_codes[i]
                cell = row[i + 1]
                if code is None or code == "":
                    continue
                if self.support.parseNumber(cell) is None:
                    valid = False
                    break
            if valid:
                qualified.append(row)
        if len(qualified) < len(segment_codes):
            return 0

        total_idx = self.support.lastIndexOfTotal(segment_codes)
        if 0 <= total_idx < len(qualified):
            if not self.support.verifyTotalRow(qualified, total_idx, expected_data_cols,
                                               1, segment_codes):
                return 0

        resolved_periods = [context.resolvePeriod(c) for c in period_codes]

        produced = 0
        for row_idx, seg_code in enumerate(segment_codes):
            if self.support.isSkipColumn(seg_code):
                continue
            row_cells = qualified[row_idx]
            for col_idx in range(expected_data_cols):
                period = resolved_periods[col_idx]
                if not period:
                    continue
                value = self.support.parseNumber(row_cells[col_idx + 1])
                if value is None:
                    continue
                self.support.addMetric(sink, seg_code, metric_code, period, value,
                                       table_id, currency, unit)
                produced += 1
        return produced
