# -*- coding: utf-8 -*-
"""SegmentRowPeriodColumnHandler：TCOM 行=segment × 列=period 布局。"""
from __future__ import annotations

import logging
from typing import Dict, List, Optional

from .. import period_sequence, period_type_util
from ..html_support import HtmlExtractionSupport
from ..model import CompanyConfig, FinancialTable, Segment, TableRow

logger = logging.getLogger(__name__)

LAYOUT_ID = "SEGMENT_ROWS_PERIOD_COLUMNS"


class SegmentRowPeriodColumnHandler:
    priority_value = 200

    def __init__(self, support: HtmlExtractionSupport):
        self.support = support

    def priority(self) -> int:
        return self.priority_value

    def supports(self, table: FinancialTable, cfg: Optional[CompanyConfig]) -> bool:
        if cfg is None:
            return False
        if cfg.htmlLayout is None or cfg.htmlLayout.upper() != LAYOUT_ID:
            return False
        if not self.support.hasAnyDataRow(table):
            return False
        return self._find_first_segment_row(table, cfg) >= 0

    def apply(self, table: FinancialTable, cfg: Optional[CompanyConfig],
              sink: Dict[str, Segment]) -> int:
        fyem = int(getattr(cfg, "fiscalYearEndMonth", 12) or 12) if cfg else 12
        default_quarter = period_type_util.determinePeriodType(table, fyem)
        if not default_quarter:
            default_quarter = "Q3"
        seq = period_sequence.build(table, default_quarter, fyem)
        if not seq:
            return 0
        currency_by_period = period_sequence.buildCurrencies(table, default_quarter, fyem)
        default_currency = cfg.defaultCurrency
        unit_override = cfg.defaultUnit

        fallback_metric = self._first_metric_code(cfg)
        if fallback_metric is None:
            return 0

        hits = 0
        for row in table.getRows():
            label = (row.getLabel() or "").strip()
            if not label:
                continue
            seg_code = self._match_l1(label, cfg)
            if seg_code is None:
                continue
            metric_code = self._match_metric(label, cfg) or fallback_metric
            current = self.support.getOrCreateSegment(sink, cfg, seg_code)
            nums = self._extract_numeric_in_order(row)
            match_count = min(len(nums), len(seq))
            for idx in range(match_count):
                period = seq[idx]
                v = nums[idx]
                if not period or v is None:
                    continue
                if period.endswith("QTD9") or period.endswith("QTD6") or period.endswith("H"):
                    continue
                if not self._currency_accepted(currency_by_period, idx, default_currency):
                    continue
                self.support.addMetric(current, metric_code, period, v, table, unit_override)
                hits += 1
        return hits

    def _find_first_segment_row(self, table: FinancialTable,
                                cfg: CompanyConfig) -> int:
        for i, row in enumerate(table.getRows()):
            label = (row.getLabel() or "").strip()
            if not label:
                continue
            if self._match_l1(label, cfg) is not None:
                return i
        return -1

    def _match_l1(self, label: str, cfg: CompanyConfig) -> Optional[str]:
        if cfg is None or not cfg.segments or label is None:
            return None
        label_lower = label.lower()
        # 1) equal (segmentName or aliases)
        for sc in cfg.segments:
            if sc.level != 1:
                continue
            if self._eq_ic(sc.segmentName, label):
                return sc.segmentCode
            for alias in sc.aliases or []:
                if self._eq_ic(alias, label):
                    return sc.segmentCode
        # 2) contains
        for sc in cfg.segments:
            if sc.level != 1:
                continue
            if self._contains_ic(label_lower, sc.segmentName):
                return sc.segmentCode
            for alias in sc.aliases or []:
                if self._contains_ic(label_lower, alias):
                    return sc.segmentCode
        return None

    def _match_metric(self, label: str, cfg: CompanyConfig) -> Optional[str]:
        if cfg is None or not cfg.metricMappingRules or label is None:
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
    def _first_metric_code(cfg: CompanyConfig) -> Optional[str]:
        if cfg is None or not cfg.metricMappingRules:
            return None
        return cfg.metricMappingRules[0].standardMetricCode

    @staticmethod
    def _currency_accepted(currency_by_period: List[str], idx: int,
                           default_currency: Optional[str]) -> bool:
        if default_currency is None or not default_currency.strip():
            return True
        if idx < 0 or idx >= len(currency_by_period):
            return True
        cur = currency_by_period[idx]
        if not cur:
            return True
        return cur.lower() == default_currency.lower()

    @staticmethod
    def _extract_numeric_in_order(row: TableRow) -> List[float]:
        out: List[float] = []
        cells = row.getCells()
        if not cells:
            return out
        for c in cells:
            if c is None or not c.isNumeric():
                continue
            txt = c.getText() or ""
            if "%" in txt:
                continue
            out.append(c.getNumericValue())
        return out

    @staticmethod
    def _eq_ic(a: Optional[str], b: Optional[str]) -> bool:
        return a is not None and b is not None and a.strip().lower() == b.strip().lower()

    @staticmethod
    def _contains_ic(label_lower: str, needle: Optional[str]) -> bool:
        if needle is None or not needle.strip():
            return False
        return needle.lower() in label_lower
