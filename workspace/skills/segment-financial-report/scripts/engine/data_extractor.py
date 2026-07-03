# -*- coding: utf-8 -*-
"""DataExtractor — ported from io.invest.iagent.service.extraction.extractor.DataExtractor.

Number semantics MUST match Java: normalizeToMillion uses Math.floor for thousand->million
(the Java DataExtractor path; note HtmlExtractionSupport uses truncTowardZero -- BEKE relies
on the trunc-toward-zero variant, which is what the BEKE handler goes through).
"""
from __future__ import annotations

import logging
import math
import re
from typing import Dict, List, Optional

from . import period_sequence, period_type_util, section_title_detector
from .metric_mapper import MetricMapper
from .model import (
    CompanyConfig,
    FinancialTable,
    MetricDict,
    Segment,
    SegmentMetric,
    TableCell,
    TableRow,
)
from .segment_recognizer import SegmentRecognizer

logger = logging.getLogger(__name__)


YEAR_RE = re.compile(r"\b(20\d{2})\b")
PERIOD_TYPE_RE = re.compile(r"(FY|Q[1-4]|H[12])$", re.IGNORECASE)


class DataExtractor:
    def __init__(self, segmentRecognizer: SegmentRecognizer, metricMapper: MetricMapper):
        self.segmentRecognizer = segmentRecognizer
        self.metricMapper = metricMapper

    # ported from DataExtractor.extractSegmentData
    def extractSegmentData(self, table: FinancialTable) -> List[Segment]:
        logger.info("Extracting segment data from table: %s", table.getTitle())
        segments = self.segmentRecognizer.recognizeSegments(table)
        for seg in segments:
            self._extract_metrics_for_segment(seg, table)
            self._extract_metrics_for_children(seg, table)
        self._aggregate_child_metrics(segments)
        segments = self._filter_by_period_type(segments)
        return segments

    def _filter_by_period_type(self, segments: List[Segment]) -> List[Segment]:
        self._filter_segments_by_period_type(segments)
        return [s for s in segments if s.segmentCode is not None or s.children is not None]

    def _extract_metrics_for_children(self, parent: Segment, table: FinancialTable) -> None:
        for child in parent.children:
            self._extract_metrics_for_segment(child, table)
            self._extract_metrics_for_children(child, table)

    def _extract_metrics_for_segment(self, segment: Segment, table: FinancialTable) -> None:
        possible = self._infer_possible_metrics(table)
        if not possible:
            return
        for metricCode in possible:
            row = self._find_segment_row_for_metric(segment, metricCode, table)
            if row is None:
                continue
            self._extract_metric_data_for_row(segment, metricCode, row, table)

    def _extract_metric_data_for_row(self, segment: Segment, metricCode: str,
                                     row: TableRow, table: FinancialTable) -> None:
        cells = row.getCells()
        title = table.getTitle() or ""
        title_year = self._extract_year(title)

        quarter = period_type_util.determinePeriodType(table)
        if not quarter:
            quarter = "Q3"

        seq = period_sequence.build(table, quarter)
        if not seq:
            if title_year > 0:
                seq = [f"{title_year - 1}{quarter}", f"{title_year}{quarter}"]
            else:
                return

        valid_cells: List[TableCell] = []
        for i in range(len(cells)):
            c = cells[i]
            if not c.isNumeric():
                continue
            is_pct = False
            if c.getText() is not None and "%" in c.getText():
                is_pct = True
            if i + 1 < len(cells):
                nxt = cells[i + 1].getText()
                if nxt == "%":
                    is_pct = True
            if is_pct:
                continue
            valid_cells.append(c)

        match_count = min(len(valid_cells), len(seq))
        for idx in range(match_count):
            period = seq[idx]
            cell = valid_cells[idx]
            if period.endswith("QTD9") or period.endswith("QTD6") or period.endswith("H"):
                continue
            if segment.getMetric(metricCode, period) is not None:
                continue
            metric = self._create_metric(metricCode, cell, table, period)
            segment.addMetric(metric)

    def _find_segment_row_for_metric(self, segment: Segment, metricCode: Optional[str],
                                     table: FinancialTable) -> Optional[TableRow]:
        section_header = self._find_metric_section_header_row(metricCode, table)
        start = section_header + 1 if section_header >= 0 else 0
        end = len(table.getRows())
        if section_header >= 0:
            nxt = self._find_next_section_header_row(table, section_header + 1)
            if nxt >= 0:
                end = nxt

        segmentName = segment.segmentName
        segmentCode = segment.segmentCode
        for i in range(start, min(end, len(table.getRows()))):
            row = table.getRows()[i]
            if row.getLabel() is None:
                continue
            lower_label = row.getLabel().lower().strip()
            if self._matches_segment(lower_label, segmentCode, segmentName):
                return row

        if section_header >= 0:
            # fallback: search the whole table without section (recursive with metric=None)
            return self._find_segment_row_for_metric(segment, None, table)
        return None

    def _matches_segment(self, lower_label: str, segmentCode: Optional[str],
                         segmentName: Optional[str]) -> bool:
        if self.segmentRecognizer.match(lower_label, segmentCode):
            return True
        candidates: List[str] = []
        if segmentCode is not None:
            candidates.append(segmentCode.lower())
            candidates.append(segmentCode.replace("_", " ").lower())
        if segmentName is not None:
            candidates.append(segmentName.lower().strip())

        trimmed = lower_label.strip()
        for cand in candidates:
            if trimmed == cand:
                return True
            if trimmed.endswith(" - " + cand):
                return True
            if trimmed.endswith("- " + cand):
                return True
            if trimmed.startswith("- " + cand):
                return True
            if (trimmed.startswith(cand + " ") or trimmed.startswith(cand + "(")
                    or trimmed.startswith(cand + "-")):
                return True
            if (" " + cand + " " in lower_label
                    or lower_label.startswith(cand + " ")
                    or lower_label.endswith(" " + cand)):
                return True

        if segmentName is not None:
            parts = segmentName.lower().split()
            if len(parts) >= 2:
                match_count = sum(1 for p in parts if p in lower_label)
                if match_count == len(parts):
                    if "international" in lower_label and "international" not in segmentName.lower():
                        return False
                    if "china" in lower_label and "china" not in segmentName.lower():
                        return False
                    return True
        return False

    def _find_metric_section_header_row(self, metricCode: Optional[str],
                                        table: FinancialTable) -> int:
        if metricCode is None:
            return -1
        keywords = self._get_metric_keywords(metricCode)
        if not keywords:
            return -1
        for i, row in enumerate(table.getRows()):
            if row.getLabel() is None:
                continue
            lower_label = row.getLabel().lower().strip()
            if not any(k in lower_label for k in keywords):
                continue
            if section_title_detector.isSectionTitleRow(row, lower_label):
                return i
        # shortcut fallback via title
        if table.getTitle() is not None:
            lower_title = table.getTitle().lower()
            for kw in keywords:
                if kw in lower_title and not self._title_mentions_other_metrics(lower_title, metricCode):
                    return -1
        return -1

    def _title_mentions_other_metrics(self, lower_title: str, current: str) -> bool:
        has_revenue = "revenue" in lower_title or "收入" in lower_title
        has_operating = ("operating income" in lower_title or "operating profit" in lower_title
                        or "income from operations" in lower_title
                        or "经营利润" in lower_title or "营业利润" in lower_title)
        has_gross = "gross profit" in lower_title or "毛利" in lower_title
        has_ebit = "ebit" in lower_title
        other = 0
        if has_revenue and current != "REVENUE": other += 1
        if has_operating and current != "OPERATING_INCOME": other += 1
        if has_gross and current != "GROSS_PROFIT": other += 1
        if has_ebit and current not in ("ADJUSTED_EBITA", "EBIT", "EBITDA"): other += 1
        return other >= 1

    def _find_next_section_header_row(self, table: FinancialTable, start: int) -> int:
        for i in range(start, len(table.getRows())):
            row = table.getRows()[i]
            if row.getLabel() is None:
                continue
            lower_label = row.getLabel().lower().strip()
            contains_kw = any(x in lower_label for x in (
                "revenue", "income", "profit", "expense", "cost", "loss", "supplemental"))
            if contains_kw and section_title_detector.isSectionTitleRow(row, lower_label):
                return i
        return -1

    def _get_metric_keywords(self, metricCode: str) -> List[str]:
        if metricCode == "REVENUE":
            return ["revenue", "revenues", "收入"]
        if metricCode == "OPERATING_INCOME":
            return ["operating", "income", "profit", "利润", "收益"]
        if metricCode == "ADJUSTED_EBITA":
            return ["ebita", "ebit", "调整后"]
        return []

    def _infer_possible_metrics(self, table: FinancialTable) -> List[str]:
        title = table.getTitle()
        lower_title = title.lower() if title else ""

        has_rev_section = False
        has_oi_section = False
        has_ebita_section = False
        for r in table.getRows():
            label = r.getLabel()
            if label is None:
                continue
            ll = label.lower().strip()
            if section_title_detector.isSectionTitleRow(r, ll):
                if "revenue" in ll or "revenues" in ll:
                    has_rev_section = True
                if ("operating income" in ll or "operating profit" in ll
                        or "income from operations" in ll):
                    has_oi_section = True
                if "ebita" in ll:
                    has_ebita_section = True

        metrics: List[str] = []
        is_segment_table = "segment" in lower_title
        has_revenue = ("revenue" in lower_title or "revenues" in lower_title or has_rev_section)
        has_prof = ("profitability" in lower_title or "profit" in lower_title
                    or "operating income" in lower_title or "经营利润" in lower_title
                    or has_oi_section)
        has_adjusted_ebita = ("adjusted ebita" in lower_title or "调整后ebita" in lower_title
                              or "经调整ebita" in lower_title or "ebita by segment" in lower_title
                              or has_ebita_section)
        has_ebita = ("ebita" in lower_title and not has_adjusted_ebita)
        has_ebitda = "ebitda" in lower_title
        has_ebit = ("ebit" in lower_title and not has_ebita and not has_ebitda)

        if is_segment_table or has_rev_section or has_oi_section or has_ebita_section:
            if has_revenue:
                metrics.append("REVENUE")
            if has_adjusted_ebita:
                metrics.append("ADJUSTED_EBITA")
            elif has_ebitda:
                metrics.append("EBITDA")
            elif has_ebita:
                metrics.append("ADJUSTED_EBITA")
            elif has_ebit:
                metrics.append("EBIT")
            elif has_prof:
                metrics.append("OPERATING_INCOME")
            if not metrics:
                metrics.extend(["REVENUE", "OPERATING_INCOME", "ADJUSTED_EBITA"])
            return metrics

        if ("adjusted ebita" in lower_title or "经调整ebita" in lower_title
                or "调整后ebita" in lower_title or "ebita by segment" in lower_title):
            metrics.append("ADJUSTED_EBITA")
        if "ebit" in lower_title and "EBIT" not in metrics:
            metrics.append("EBIT")
        if ("operating income" in lower_title or "经营利润" in lower_title
                or "营业利润" in lower_title):
            metrics.append("OPERATING_INCOME")
        if ("revenue" in lower_title or "收入" in lower_title
                or "营收" in lower_title or "revenues" in lower_title):
            metrics.append("REVENUE")

        if not metrics:
            single = self._infer_metric_from_table(table)
            if single is not None:
                metrics.append(single)
        return metrics

    def _infer_metric_from_table(self, table: FinancialTable) -> Optional[str]:
        title = table.getTitle()
        if title is None:
            return None
        lt = title.lower()
        if ("adjusted ebita" in lt or "经调整ebita" in lt
                or "调整后ebita" in lt or "ebita by segment" in lt):
            return "ADJUSTED_EBITA"
        if "revenue" in lt or "收入" in lt or "营收" in lt or "revenues" in lt:
            return "REVENUE"
        if "ebitda" in lt:
            return "EBITDA"
        if "ebit" in lt:
            return "EBIT"
        if "operating income" in lt or "经营利润" in lt or "营业利润" in lt:
            return "OPERATING_INCOME"
        if "net income" in lt or "净利润" in lt:
            return "NET_INCOME"
        if "cost" in lt or "成本" in lt:
            return "COST_OF_REVENUE"
        if "expense" in lt or "费用" in lt:
            return "OPERATING_EXPENSES"
        return None

    def _extract_year(self, text: str) -> int:
        if not text:
            return 0
        m = YEAR_RE.search(text)
        return int(m.group(1)) if m else 0

    def _create_metric(self, metricCode: str, cell: TableCell, table: FinancialTable,
                       period: str) -> SegmentMetric:
        m = SegmentMetric()
        m.metricCode = metricCode
        m.period = period
        d = self.metricMapper.getMetricByCode(metricCode)
        if d is not None:
            m.metricName = d.metricName
        m.value = self._normalize_to_million(cell.getNumericValue(), table.getUnit())
        m.currency = table.getCurrency()
        m.unit = "million"
        m.sourceType = "TABLE_EXTRACT"
        m.sourceLocation = table.getTableId()
        m.confidenceScore = self._calculate_confidence(cell, table)
        return m

    def _normalize_to_million(self, value: float, unit: Optional[str]) -> float:
        # ported from DataExtractor.normalizeToMillion — uses Math.floor (not truncTowardZero!)
        if unit is None or not unit.strip():
            return value
        lower = unit.lower().strip()
        if "million" in lower or "百万" in lower:
            return value
        if "thousand" in lower or "千" in lower:
            return math.floor(value / 1000.0)
        if "billion" in lower or "十亿" in lower:
            return math.floor(value * 1000.0)
        return value

    def _calculate_confidence(self, cell: TableCell, table: FinancialTable) -> int:
        conf = 80
        if cell.isParentheses:
            conf -= 5
        if table.getUnit() is not None:
            conf += 5
        if table.getCurrency() is not None:
            conf += 5
        return max(0, conf)

    # ---- aggregation ----
    def _aggregate_child_metrics(self, segments: List[Segment]) -> None:
        for s in segments:
            self._aggregate_child_metrics(s.children)
            if not s.metrics and s.children:
                self._aggregate_from_children(s)

    def _aggregate_from_children(self, parent: Segment) -> None:
        # {(metricCode, period): totalValue}
        aggregated: Dict[str, Dict[str, float]] = {}
        currency_map: Dict[str, str] = {}
        for child in parent.children:
            for m in child.metrics:
                key = f"{m.metricCode}|{m.period}"
                bucket = aggregated.setdefault(key, {})
                bucket["value"] = bucket.get("value", 0.0) + (m.value or 0.0)
                if m.currency is not None:
                    currency_map[m.metricCode] = m.currency
        for key, bucket in aggregated.items():
            parts = key.split("|", 1)
            code = parts[0]
            period = parts[1] if len(parts) > 1 else ""
            total = bucket.get("value")
            if total is not None:
                agg = SegmentMetric()
                agg.metricCode = code
                agg.metricName = self._get_metric_name(code)
                agg.value = total
                agg.period = period
                agg.currency = currency_map.get(code)
                agg.unit = "million"
                agg.sourceType = "AGGREGATED"
                agg.sourceLocation = "aggregated from children"
                agg.confidenceScore = 70
                parent.addMetric(agg)

    def _get_metric_name(self, code: str) -> str:
        d = self.metricMapper.getMetricByCode(code)
        return d.metricName if d is not None else code

    # ---- period-type filter ----
    def _filter_segments_by_period_type(self, segments: List[Segment]) -> None:
        cfg = self.segmentRecognizer.getCompanyConfig()
        include = cfg.includePeriodTypes if cfg is not None else None
        if not include:
            return
        for s in segments:
            self._filter_one(s, include)
            if s.children:
                self._filter_segments_by_period_type(s.children)

    def _filter_one(self, segment: Segment, include: List[str]) -> None:
        if not segment.metrics:
            return
        filtered: List[SegmentMetric] = []
        for m in segment.metrics:
            p = m.period
            if not p:
                filtered.append(m)
                continue
            pt = self._extract_period_type(p)
            if pt in include:
                filtered.append(m)
        segment.setMetrics(filtered)

    def _extract_period_type(self, period: Optional[str]) -> str:
        if not period or not period.strip():
            return ""
        m = PERIOD_TYPE_RE.search(period.strip())
        if m:
            return m.group(1).upper()
        return ""

    # ---- Multi-table extraction (unused in HTML orchestrator path but keeps API parity) ----
    def extractFromMultipleTables(self, tables: List[FinancialTable]) -> List[Segment]:
        all_segments: List[Segment] = []
        for t in tables:
            self._merge_segments(all_segments, self.extractSegmentData(t))
        return [s for s in all_segments if s is not None and s.segmentCode]

    def _merge_segments(self, target: List[Segment], source: List[Segment]) -> None:
        for src in source:
            existing = self._find_by_name(target, src.segmentName)
            if existing is None:
                target.append(src)
            else:
                for m in src.metrics:
                    if existing.getMetric(m.metricCode, m.period) is None:
                        existing.addMetric(m)
                self._merge_segments(existing.children, src.children)

    def _find_by_name(self, segments: List[Segment], name: Optional[str]) -> Optional[Segment]:
        if not name:
            return None
        for s in segments:
            if s.segmentName and s.segmentName.lower() == name.lower():
                return s
            found = self._find_by_name(s.children, name)
            if found is not None:
                return found
        return None
