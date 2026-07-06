# -*- coding: utf-8 -*-
"""GenericHtmlLayoutHandler：通用兜底 handler，委托给 DataExtractor。"""
from __future__ import annotations

from typing import Dict, List, Optional

from ..data_extractor import DataExtractor
from ..model import CompanyConfig, FinancialTable, Segment, SegmentMetric


class GenericHtmlLayoutHandler:
    priority_value = 999

    def __init__(self, data_extractor: DataExtractor):
        self.data_extractor = data_extractor

    def priority(self) -> int:
        return self.priority_value

    def supports(self, table: FinancialTable, cfg: Optional[CompanyConfig]) -> bool:
        return True

    def apply(self, table: FinancialTable, cfg: Optional[CompanyConfig],
              sink: Dict[str, Segment]) -> int:
        segments = self.data_extractor.extractSegmentData(table)
        if not segments:
            return 0
        hits = 0
        for src in segments:
            code = src.segmentCode
            if not code:
                continue
            dst = sink.get(code)
            if dst is None:
                sink[code] = src
                hits += self._count(src)
            else:
                hits += self._merge_into(dst, src)
        return hits

    def _merge_into(self, dst: Segment, src: Segment) -> int:
        added = 0
        for m in src.metrics:
            if dst.getMetric(m.metricCode, m.period) is None:
                dst.addMetric(m)
                added += 1
        for src_child in src.children:
            existing = None
            for c in dst.children:
                if (src_child.segmentCode is not None
                        and c.segmentCode is not None
                        and src_child.segmentCode.lower() == c.segmentCode.lower()):
                    existing = c
                    break
            if existing is None:
                dst.addChild(src_child)
                added += self._count(src_child)
            else:
                added += self._merge_into(existing, src_child)
        return added

    def _count(self, s: Segment) -> int:
        n = len(s.metrics)
        for c in s.children:
            n += self._count(c)
        return n
