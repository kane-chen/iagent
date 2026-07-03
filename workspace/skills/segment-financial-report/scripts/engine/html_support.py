# -*- coding: utf-8 -*-
"""HtmlExtractionSupport — ported from io.invest.iagent.service.extraction.extractor.HtmlExtractionSupport.

Shared numeric/segment helpers for HTML layout handlers.
"""
from __future__ import annotations

import math
from typing import Dict, List, Optional

from .metric_mapper import MetricMapper
from .model import CompanyConfig, FinancialTable, Segment, SegmentConfig, SegmentMetric


def _trunc_toward_zero(v: float) -> float:
    # ported from HtmlExtractionSupport.truncTowardZero
    return math.ceil(v) if v < 0 else math.floor(v)


def normalizeToMillion(value: float, unit: Optional[str]) -> float:
    if unit is None or not unit.strip():
        return value
    lower = unit.lower().strip()
    if "million" in lower or "百万" in lower or "百萬" in lower:
        return value
    if "thousand" in lower or "千" in lower:
        return _trunc_toward_zero(value / 1000.0)
    if "billion" in lower or "十亿" in lower or "十億" in lower:
        return _trunc_toward_zero(value * 1000.0)
    return value


class HtmlExtractionSupport:
    def __init__(self, metricMapper: MetricMapper):
        self.metricMapper = metricMapper

    def findSegmentConfig(self, cfg: Optional[CompanyConfig], segmentCode: Optional[str]) -> Optional[SegmentConfig]:
        if cfg is None or not cfg.segments or segmentCode is None:
            return None
        for sc in cfg.segments:
            if sc.segmentCode is not None and sc.segmentCode.lower() == segmentCode.lower():
                return sc
        return None

    def getOrCreateSegment(self, sink: Dict[str, Segment], cfg: Optional[CompanyConfig],
                           segmentCode: str) -> Segment:
        if segmentCode in sink:
            return sink[segmentCode]
        s = Segment()
        s.segmentCode = segmentCode
        sc = self.findSegmentConfig(cfg, segmentCode)
        if sc is not None:
            s.segmentName = sc.segmentName
            s.level = sc.level if (sc.level and sc.level > 0) else 1
        else:
            s.segmentName = segmentCode
            s.level = 1
        sink[segmentCode] = s
        return s

    def addMetric(self, segment: Segment, metricCode: str, period: str,
                  rawValue: float, table: FinancialTable,
                  unitOverride: Optional[str] = None) -> None:
        if segment.getMetric(metricCode, period) is not None:
            return
        unit = unitOverride if (unitOverride is not None and unitOverride.strip()) else table.getUnit()
        m = SegmentMetric()
        m.metricCode = metricCode
        d = self.metricMapper.getMetricByCode(metricCode)
        m.metricName = d.metricName if d is not None else metricCode
        m.value = normalizeToMillion(rawValue, unit)
        m.period = period
        m.sourceType = "TABLE_EXTRACT"
        m.sourceLocation = table.getTableId()
        m.currency = table.getCurrency()
        m.unit = "million"
        m.confidenceScore = 85
        segment.addMetric(m)

    def hasAnyDataRow(self, table: Optional[FinancialTable]) -> bool:
        if table is None or table.getRows() is None:
            return False
        return len(table.getRows()) > 0

    def collectLabels(self, table: FinancialTable) -> List[str]:
        return [(r.getLabel().strip() if r.getLabel() else "") for r in table.getRows()]
