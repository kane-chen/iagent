# -*- coding: utf-8 -*-
"""SubsegmentMatrixHandler：列=L1 segments、行=混合（L2 收入 / L1 成本/OP_INCOME），美团布局。"""
from __future__ import annotations

from typing import Dict, List

from ..model import CompanyConfig, Layout, Segment
from ..pdf_layout_handler import PdfLayoutHandler
from ..pdf_support import PdfExtractionSupport


class SubsegmentMatrixHandler(PdfLayoutHandler):
    def __init__(self, support: PdfExtractionSupport):
        self.support = support

    def layout(self) -> Layout:
        return Layout.SUBSEGMENT_MATRIX

    def apply(self, mapping, data_rows: List[List[str]],
              table_id: str, currency: str, unit: str,
              context, sink: Dict[str, Segment]) -> int:
        segment_codes: List[str] = mapping.segmentCodes
        row_descs: List[CompanyConfig.PdfColumnMapping.RowDescriptor if False else object] = mapping.rowDescriptors
        expected_data_cols = mapping.columnCount
        if (not segment_codes or not row_descs or expected_data_cols <= 0
                or len(segment_codes) != expected_data_cols):
            return 0

        total_cols = 1 + expected_data_cols
        qualified: List[List[str]] = []
        for row in data_rows:
            if len(row) != total_cols:
                continue
            if self.support.parseNumber(row[0]) is not None:
                continue
            ok = True
            for i in range(expected_data_cols):
                cell = row[i + 1]
                if self.support.parseNumber(cell) is None and not self.support.isPlaceholderCell(cell):
                    ok = False
                    break
            if ok:
                qualified.append(row)
        if len(qualified) != len(row_descs):
            return 0

        total_idx = self.support.lastIndexOfTotal(segment_codes)
        if total_idx >= 0:
            passed = 0
            attempted = 0
            for row in qualified:
                total_val = self.support.parseNumber(row[total_idx + 1])
                if total_val is None:
                    continue
                s = 0.0
                contributors = 0
                for col in range(len(segment_codes)):
                    if col == total_idx:
                        continue
                    code = segment_codes[col]
                    v = self.support.parseNumber(row[col + 1])
                    if v is None:
                        continue
                    s += v
                    contributors += 1
                if contributors < 2:
                    continue
                attempted += 1
                diff = abs(s - total_val)
                tol = max(1.0, abs(total_val) * 0.005)
                if diff <= tol:
                    passed += 1
            if attempted == 0 or passed * 2 < attempted:
                return 0

        period = context.resolvePeriod(mapping.periodCode)
        if not period:
            return 0

        l1_buckets: Dict[str, Segment] = {}

        produced = 0
        for row_idx, desc in enumerate(row_descs):
            if desc is None:
                continue
            metric_code = desc.metricCode
            if metric_code is None or metric_code == "":
                continue
            sub_code = desc.subSegmentCode
            row_cells = qualified[row_idx]

            for col_idx in range(len(segment_codes)):
                if col_idx == total_idx:
                    continue
                l1_code = segment_codes[col_idx]
                if self.support.isSkipColumn(l1_code):
                    continue

                value = self.support.parseNumber(row_cells[col_idx + 1])
                if value is None:
                    continue

                l1 = l1_buckets.get(l1_code)
                if l1 is None:
                    l1 = self._create_segment(l1_code)
                    l1_buckets[l1_code] = l1

                if sub_code is None or sub_code == "":
                    self.support.addMetricToSegment(l1, metric_code, period, value,
                                                    table_id, currency, unit, desc.abs)
                else:
                    l2 = self._find_or_create_child(l1, sub_code)
                    self.support.addMetricToSegment(l2, metric_code, period, value,
                                                    table_id, currency, unit, desc.abs)
                produced += 1

        for code, fresh in l1_buckets.items():
            existing = sink.get(code)
            if existing is None:
                sink[code] = fresh
            else:
                self._merge_into(existing, fresh)
        return produced

    def _create_segment(self, code: str) -> Segment:
        s = Segment()
        s.segmentCode = code
        sc = self.support.findSegmentConfig(code)
        if sc is not None:
            s.segmentName = sc.segmentName
            s.level = sc.level if (sc.level and sc.level > 0) else 1
        else:
            s.segmentName = code
            s.level = 1
        return s

    def _find_or_create_child(self, parent: Segment, child_code: str) -> Segment:
        if parent.children:
            for c in parent.children:
                if child_code.lower() == (c.segmentCode or "").lower():
                    return c
        child = Segment()
        child.segmentCode = child_code
        sc = self.support.findSegmentConfig(child_code)
        if sc is not None:
            child.segmentName = sc.segmentName
            child.level = sc.level if (sc.level and sc.level > 0) else 2
        else:
            child.segmentName = child_code
            child.level = 2
        parent.addChild(child)
        return child

    def _merge_into(self, dst: Segment, src: Segment) -> None:
        for m in src.metrics:
            if dst.getMetric(m.metricCode, m.period) is None:
                dst.addMetric(m)
        for src_child in src.children:
            existing = None
            for c in dst.children:
                if (src_child.segmentCode or "").lower() == (c.segmentCode or "").lower():
                    existing = c
                    break
            if existing is None:
                dst.addChild(src_child)
            else:
                self._merge_into(existing, src_child)
