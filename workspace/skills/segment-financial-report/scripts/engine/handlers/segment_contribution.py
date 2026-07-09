# -*- coding: utf-8 -*-
"""SegmentContributionHandler：BEKE 三行块布局。"""
from __future__ import annotations

import logging
from typing import Dict, List, Optional

from .. import period_sequence, period_type_util
from ..html_support import HtmlExtractionSupport
from ..model import CompanyConfig, FinancialTable, Segment, TableRow
from ..segment_recognizer import SegmentRecognizer

logger = logging.getLogger(__name__)

LAYOUT_ID = "SEGMENT_CONTRIBUTION_BLOCKS"


class SegmentContributionHandler:
    priority_value = 100

    def __init__(self, support: HtmlExtractionSupport, recognizer: SegmentRecognizer):
        self.support = support
        self.recognizer = recognizer

    def priority(self) -> int:
        return self.priority_value

    def supports(self, table: FinancialTable, cfg: Optional[CompanyConfig]) -> bool:
        if cfg is None:
            return False
        if cfg.htmlLayout is None or cfg.htmlLayout.upper() != LAYOUT_ID:
            return False
        if not self.support.hasAnyDataRow(table):
            return False
        return self._find_first_segment_title_row(table, cfg) >= 0

    def apply(self, table: FinancialTable, cfg: Optional[CompanyConfig],
              sink: Dict[str, Segment]) -> int:
        rows = table.getRows()
        fyem = int(getattr(cfg, "fiscalYearEndMonth", 12) or 12) if cfg else 12
        default_q = period_type_util.determinePeriodType(table, fyem) or "Q4"
        seq = period_sequence.build(table, default_q, fyem)
        if not seq:
            return 0

        unit_override = cfg.defaultUnit if cfg is not None else None
        hits = 0
        current: Optional[Segment] = None
        for row in rows:
            label = (row.getLabel() or "").strip()
            if not label:
                continue
            matched = self._match_l1(label, cfg)
            if matched is not None and not self._has_any_numeric(row):
                current = self.support.getOrCreateSegment(sink, cfg, matched)
                continue
            if current is None:
                continue
            metric_code = self._match_metric(label, cfg)
            if metric_code is None:
                continue
            nums = self._extract_numeric_in_order(row)
            match_count = min(len(nums), len(seq))
            for idx in range(match_count):
                period = seq[idx]
                v = nums[idx]
                if not period or v is None:
                    continue
                # YTD values retained for downstream derive_ytd_quarters()
                self.support.addMetric(current, metric_code, period, v, table, unit_override)
                hits += 1
        return hits

    def _find_first_segment_title_row(self, table: FinancialTable,
                                      cfg: CompanyConfig) -> int:
        rows = table.getRows()
        for i, row in enumerate(rows):
            label = (row.getLabel() or "").strip()
            if not label:
                continue
            if self._has_any_numeric(row):
                continue
            if self._match_l1(label, cfg) is not None:
                return i
        return -1

    def _match_l1(self, label: str, cfg: CompanyConfig) -> Optional[str]:
        if cfg is None or not cfg.segments:
            return None
        for sc in cfg.segments:
            if sc.level != 1:
                continue
            if not self.recognizer.match(label, sc.segmentCode):
                continue
            return sc.segmentCode
        return None

    def _match_metric(self, label: str, cfg: CompanyConfig) -> Optional[str]:
        if cfg is None or not cfg.metricMappingRules:
            return None
        lower = label.lower()
        for rule in cfg.metricMappingRules:
            for raw in rule.rawMetricNames or []:
                if raw is None or not raw.strip():
                    continue
                if raw.lower() in lower:
                    return rule.standardMetricCode
        return None

    @staticmethod
    def _has_any_numeric(row: TableRow) -> bool:
        if row.getCells() is None:
            return False
        for c in row.getCells():
            if c is not None and c.isNumeric():
                return True
        return False

    @staticmethod
    def _extract_numeric_in_order(row: TableRow) -> List[float]:
        out: List[float] = []
        cells = row.getCells()
        if not cells:
            return out
        for i, c in enumerate(cells):
            if c is None or not c.isNumeric():
                continue
            txt = c.getText() or ""
            pct = "%" in txt
            if not pct and i + 1 < len(cells):
                nxt = cells[i + 1]
                if nxt is not None and (nxt.getText() or "") == "%":
                    pct = True
            if pct:
                continue
            out.append(c.getNumericValue())
        return out
