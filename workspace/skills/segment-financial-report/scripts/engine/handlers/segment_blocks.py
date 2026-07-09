# -*- coding: utf-8 -*-
"""SegmentBlocksHandler：segment 作为块标题行、指标行作为其下方子行的布局（自识别）。

这是 Microsoft 从 FY2026 Q1 起采用的新 MD&A 分部表布局，形如::

    Productivity and Business Processes        <- 非数字行，匹配 segment
      Revenue            $34,116   $29,437 ...
      Cost of revenue     6,110     5,569
      Operating income   20,599    16,885
    Intelligent Cloud                          <- 下一个 segment
      ...

也覆盖 BEKE 三行块布局（原 SegmentContributionHandler 语义），所以此 handler 优先级
高于 GenericHtmlLayoutHandler、低于显式指定的 htmlLayout handler。
"""
from __future__ import annotations

import logging
from typing import Dict, List, Optional

from .. import period_sequence, period_type_util
from ..html_support import HtmlExtractionSupport
from ..model import CompanyConfig, FinancialTable, Segment, SegmentConfig, TableRow
from ..section_title_detector import isSectionTitleRow

logger = logging.getLogger(__name__)


# Recognized metric row labels (case-insensitive substring match)
_METRIC_KEYWORDS = {
    "REVENUE": ("revenue", "revenues", "net sales", "sales", "收入", "营收", "营业额"),
    "OPERATING_INCOME": ("operating income", "operating profit", "income from operations",
                         "经营利润", "营业利润", "经营溢利"),
    "COST_OF_REVENUE": ("cost of revenue", "cost of sales", "成本", "营业成本"),
    "OPERATING_EXPENSES": ("operating expenses", "operating expense", "费用", "营业费用",
                           "销售及营销", "研发", "一般及行政"),
    "GROSS_PROFIT": ("gross profit", "gross margin", "毛利"),
    "NET_INCOME": ("net income", "净利润", "纯利"),
}
# Skip labels (totals, subtotals, unrelated)
_SKIP_KEYWORDS = ("total", "subtotal", "other", "corporate", "reconciliation",
                  "unallocated", "elimination")


class SegmentBlocksHandler:
    priority_value = 300

    def __init__(self, support: HtmlExtractionSupport):
        self.support = support

    def priority(self) -> int:
        return self.priority_value

    # Titles/keywords that disqualify a table from being a segment-revenue table
    _NON_SEGMENT_TITLE_KEYWORDS = ("unearned revenue", "deferred revenue", "contract liabilities",
                                   "unearned income")

    def supports(self, table: FinancialTable, cfg: Optional[CompanyConfig]) -> bool:
        if cfg is None or not cfg.segments:
            return False
        title = (table.getTitle() or "").lower()
        for kw in self._NON_SEGMENT_TITLE_KEYWORDS:
            if kw in title:
                return False
        # Auto-detect: find non-numeric rows whose labels match level-1 segments.
        segment_title_rows = self._find_segment_title_rows(table, cfg)
        if len(segment_title_rows) < 2:
            return False
        # Require that at least one of these segment header rows is followed within
        # the next several rows by a recognized metric keyword row (Revenue,
        # Operating income, etc.) carrying numeric data.  This filters out tables
        # that list segment names as hierarchy headers but whose children are
        # sub-segment labels, not metric rows (e.g., BABA revenue-by-segment table
        # with sub-segments under each segment header).
        rows = table.getRows()
        metric_hits = 0
        end_rows = [r for (_, r) in segment_title_rows[1:]] + [len(rows)]
        for idx, (_, start_row) in enumerate(segment_title_rows):
            end_row = end_rows[idx]
            window = rows[start_row + 1: min(start_row + 8, end_row)]
            for r in window:
                lbl = (r.getLabel() or "").strip().lower()
                if not lbl:
                    continue
                # Must have at least one numeric cell to be a metric row
                has_num = any(c is not None and c.isNumeric() for c in (r.getCells() or []))
                if not has_num:
                    continue
                if self._match_metric(lbl) is not None:
                    metric_hits += 1
                    break
        return metric_hits >= 2

    def apply(self, table: FinancialTable, cfg: CompanyConfig,
              sink: Dict[str, Segment]) -> int:
        fyem = getattr(cfg, "fiscalYearEndMonth", 12) or 12
        default_quarter = period_type_util.determinePeriodType(table, fyem)
        if not default_quarter:
            # Heuristic: if header rows contain bare year cells but no month/quarter
            # keywords and no percentage-comparison columns, this is likely the
            # FY summary table (e.g., "SEGMENT RESULTS OF OPERATIONS" in Microsoft 10-K).
            if _looks_like_annual_summary(table):
                default_quarter = "FY"
            else:
                default_quarter = "Q3"
        seq = period_sequence.build(table, default_quarter, fyem)
        if not seq:
            return 0
        unit_override = cfg.defaultUnit if cfg is not None else None

        # Identify segment title rows: list of (segmentCode, rowIndex)
        seg_title_rows = self._find_segment_title_rows(table, cfg)
        if not seg_title_rows:
            return 0
        # End row indices (last segment extends to end of table)
        end_rows = [r for (_, r) in seg_title_rows[1:]] + [len(table.getRows())]

        hits = 0
        for idx, (seg_code, start_row) in enumerate(seg_title_rows):
            current = self.support.getOrCreateSegment(sink, cfg, seg_code)
            end_row = end_rows[idx]
            for ri in range(start_row + 1, end_row):
                row = table.getRows()[ri]
                label = (row.getLabel() or "").strip()
                if not label:
                    continue
                lower = label.lower()
                # Stop if we hit another segment title row
                if self._match_segment(lower, cfg) is not None and self._row_non_numeric(row):
                    break
                if any(sk in lower for sk in _SKIP_KEYWORDS):
                    continue
                if isSectionTitleRow(row, lower):
                    # E.g., a "Total" row -- skip
                    continue
                metric_code = self._match_metric(lower)
                if metric_code is None:
                    continue
                nums = _extract_numeric_in_order(row)
                match_count = min(len(nums), len(seq))
                for i in range(match_count):
                    period = seq[i]
                    v = nums[i]
                    if not period or v is None:
                        continue
                    # YTD values (QTD6/QTD9/H) are retained under their natural suffix so
                    # downstream derive_ytd_quarters() can compute missing quarters
                    # (Q4=FY-QTD9, Q2=QTD6-Q1). They will be filtered by includePeriodTypes.
                    self.support.addMetric(current, metric_code, period, v, table, unit_override)
                    hits += 1
        return hits

    # --- helpers ---

    def _find_segment_title_rows(self, table: FinancialTable,
                                 cfg: CompanyConfig) -> List:
        """Return [(segmentCode, rowIndex)] for non-numeric rows whose label matches
        a level-1 segment in the config, in document order."""
        out = []
        rows = table.getRows()
        for i, r in enumerate(rows):
            label = (r.getLabel() or "").strip()
            if not label:
                continue
            lower = label.lower()
            if any(sk in lower for sk in _SKIP_KEYWORDS):
                continue
            code = self._match_segment(lower, cfg)
            if code is None:
                continue
            if not self._row_non_numeric(r):
                # Segment names that carry numbers (e.g., the old layout "Revenue"
                # followed by segment-with-numbers) should be excluded.
                continue
            out.append((code, i))
        return out

    def _match_segment(self, lower_label: str, cfg: CompanyConfig) -> Optional[str]:
        for sc in cfg.segments or []:
            if sc.level != 1:
                continue
            if _label_matches(lower_label, sc):
                return sc.segmentCode
        return None

    def _match_metric(self, lower_label: str) -> Optional[str]:
        for code, kws in _METRIC_KEYWORDS.items():
            for kw in kws:
                if kw in lower_label:
                    return code
        return None

    @staticmethod
    def _row_non_numeric(row: TableRow) -> bool:
        cells = row.getCells()
        if not cells:
            return True
        for c in cells:
            if c is not None and c.isNumeric():
                return False
        return True


def _label_matches(lower_label: str, sc: SegmentConfig) -> bool:
    """Fuzzy match label against segment name and aliases."""
    candidates: List[str] = []
    if sc.segmentCode:
        candidates.append(sc.segmentCode.lower())
        candidates.append(sc.segmentCode.replace("_", " ").lower())
    if sc.segmentName:
        candidates.append(sc.segmentName.lower().strip())
    for a in (sc.aliases or []):
        if a:
            candidates.append(a.lower().strip())
    trimmed = lower_label.strip().rstrip(":")
    for cand in candidates:
        if not cand:
            continue
        if trimmed == cand:
            return True
        if trimmed.endswith(" - " + cand):
            return True
        if trimmed.endswith("- " + cand):
            return True
        if trimmed.startswith(cand + " ") or trimmed.startswith(cand + "("):
            return True
        if " " + cand + " " in (" " + trimmed + " "):
            return True
    # Multi-word match (all words of segment name present in label)
    if sc.segmentName:
        parts = sc.segmentName.lower().split()
        if len(parts) >= 2 and all(p in trimmed for p in parts):
            return True
    return False


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


_PERIOD_KEYWORDS = ("three month", "six month", "nine month", "year ended",
                    "quarter", "fiscal year", "12 months", "twelve months",
                    "january", "february", "march", "april", "may", "june",
                    "july", "august", "september", "october", "november", "december")


def _looks_like_annual_summary(table: FinancialTable) -> bool:
    """Heuristic: segment table with bare year columns (no period/month/quarter phrase
    and no percentage-change comparison column) is treated as FY (annual).

    This handles the Microsoft 10-K "SEGMENT RESULTS OF OPERATIONS" summary table
    that shows FY totals with just year headings.
    """
    import re
    year_re = re.compile(r"\b20\d{2}\b")
    has_year_cell = False
    has_period_keyword = False
    has_pct_column = False
    header_end = min(5, len(table.getRows()))
    for r in range(header_end):
        row = table.getRows()[r]
        if row is None:
            continue
        if row.getLabel():
            lbl = row.getLabel().lower()
            for kw in _PERIOD_KEYWORDS:
                if kw in lbl:
                    has_period_keyword = True
        if row.getCells():
            for c in row.getCells():
                if c is None:
                    continue
                txt = c.getText()
                if not txt:
                    continue
                t = txt.lower()
                if year_re.search(txt):
                    has_year_cell = True
                if "%" in txt or "percentage change" in t or "change %" in t:
                    has_pct_column = True
                for kw in _PERIOD_KEYWORDS:
                    if kw in t:
                        has_period_keyword = True
    # Annual summary: has year columns, no period keyword, and typically has a
    # percentage-change column (single-year-over-year comparison).
    return has_year_cell and not has_period_keyword and has_pct_column
