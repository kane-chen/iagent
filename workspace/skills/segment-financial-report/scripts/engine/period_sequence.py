# -*- coding: utf-8 -*-
"""列维度周期序列构建器：按列提取（period, currency）序列。

支持通过 ``fiscal_year_end_month`` 指定财年结束月（默认 12 = 日历年），
以便根据财年正确将月份名映射到 Q1/Q2/Q3/Q4，且正确处理日历年 vs 财年的年份偏移
（例如 Microsoft 财年结束于 6 月：Dec 2025 属于 FY2026 Q2，而非 FY2025）。
"""
from __future__ import annotations

import re
from typing import List, Optional, Tuple

from .model import FinancialTable
from .period_type_util import _build_month_to_quarter_map

YEAR_PATTERN = re.compile(r"\b(20\d{2})\b")
MONTH_NAMES = ("january", "february", "march", "april", "may", "june",
               "july", "august", "september", "october", "november", "december")
MONTH_NUM = {name: i + 1 for i, name in enumerate(MONTH_NAMES)}
HEADER_SCAN_ROWS = 5


class _Column:
    __slots__ = ("columnIdx", "period", "currency")

    def __init__(self, columnIdx: int, period: str, currency: Optional[str]):
        self.columnIdx = columnIdx
        self.period = period
        self.currency = currency


class _GroupHeader:
    """Describes a column-group header detected in the top header rows.

    A group header occupies a cell in the header row (typically spanning multiple
    underlying data columns) and declares the period type (Q1/Q2/Q3/Q4/FY/QTD6/QTD9)
    and optionally the month/day that determines the fiscal year.
    """
    __slots__ = ("columnIdx", "suffix", "month")

    def __init__(self, columnIdx: int, suffix: str, month: Optional[str]):
        self.columnIdx = columnIdx
        self.suffix = suffix
        self.month = month


def build(table: FinancialTable, defaultQuarter: str, fiscal_year_end_month: int = 12) -> List[str]:
    cols = _collect_year_columns(table, defaultQuarter, fiscal_year_end_month)
    return [c.period for c in cols]


def buildCurrencies(table: FinancialTable, defaultQuarter: str, fiscal_year_end_month: int = 12) -> List[str]:
    cols = _collect_year_columns(table, defaultQuarter, fiscal_year_end_month)
    return [c.currency or "" for c in cols]


def _collect_year_columns(table: FinancialTable, defaultQuarter: str,
                          fiscal_year_end_month: int = 12) -> List[_Column]:
    out: List[_Column] = []
    if table is None or not table.getRows():
        return out

    m2q = _build_month_to_quarter_map(fiscal_year_end_month)

    # col_idx -> (suffix, month_name_or_None) inferred from header rows above the column.
    # suffix is "Q1"/"Q2"/"Q3"/"Q4"/"FY"/"QTD6"/"QTD9".
    col_month_map = _build_column_month_map(table, m2q)

    # Also scan table.headers (which contains the text of the rows skipped by
    # _parse_rows, e.g. the period group header "Three months ended March 31,")
    # for a global month/period qualifier.  This is the case for BABA press
    # releases where TR0 holds the "Three months ended March 31," cell spanning
    # all data columns.
    if not col_month_map:
        hdr_month, hdr_suffix = _scan_headers_for_period(table, m2q, defaultQuarter)
        if hdr_month is not None:
            year_cols_all: List[int] = []
            for r in range(min(HEADER_SCAN_ROWS, len(table.getRows()))):
                row = table.getRows()[r]
                if row is None or not row.getCells():
                    continue
                for c_idx, cell in enumerate(row.getCells()):
                    if cell is None:
                        continue
                    txt = cell.getText()
                    if txt and YEAR_PATTERN.search(txt):
                        year_cols_all.append(c_idx)
            for c_idx in set(year_cols_all):
                col_month_map[c_idx] = (hdr_suffix, hdr_month)

    # If the table has a document-level period hint (e.g., "Three Months Ended
    # June 30, 2025" or "fiscal year ended March 31, 2026" from surrounding
    # press-release text) and no column month map could be derived from the
    # table's own headers, use the hint to set the suffix/month for all bare
    # year columns.
    if not col_month_map and getattr(table, "period", None):
        hint_lower = table.period.lower().replace("\xa0", " ")
        mon = _find_month(hint_lower)
        if mon is not None:
            # Determine suffix from the hint wording: FY vs quarter
            hint_suffix = _classify_hint_suffix(hint_lower, defaultQuarter, m2q, mon)
            year_cols_all: List[int] = []
            for r in range(min(HEADER_SCAN_ROWS, len(table.getRows()))):
                row = table.getRows()[r]
                if row is None or not row.getCells():
                    continue
                for c_idx, cell in enumerate(row.getCells()):
                    if cell is None:
                        continue
                    txt = cell.getText()
                    if txt and YEAR_PATTERN.search(txt):
                        year_cols_all.append(c_idx)
            for c_idx in set(year_cols_all):
                col_month_map[c_idx] = (hint_suffix, mon)

    # ----- Pass 1: scan header rows to collect group headers, self-contained dates,
    # and plain year cells -----
    group_headers: List[_GroupHeader] = []  # sorted by columnIdx, left-to-right
    self_contained: List[_Column] = []
    plain_year_columns: List[Tuple[int, int]] = []  # (col_idx, calendar_year)
    plain_year_currencies: List[str] = []

    # Track whether we saw a "Three Months Ended <Month>..." label in the row label
    # (Microsoft layout where the period phrase is in the label, not split across cells).
    label_default_month: Optional[str] = None
    label_default_suffix: Optional[str] = None

    header_end = min(HEADER_SCAN_ROWS, len(table.getRows()))
    for r in range(header_end):
        row = table.getRows()[r]
        if row is None:
            continue
        if row.getLabel():
            lbl_lower = row.getLabel().lower()
            mon = _find_month(lbl_lower)
            if mon is not None:
                if "three month" in lbl_lower:
                    label_default_month = mon
                    label_default_suffix = defaultQuarter
                elif "six month" in lbl_lower:
                    label_default_month = mon
                    label_default_suffix = "QTD6"
                elif "nine month" in lbl_lower:
                    label_default_month = mon
                    label_default_suffix = "QTD9"
                elif "year ended" in lbl_lower or "fiscal year" in lbl_lower:
                    label_default_month = mon
                    label_default_suffix = "FY"

        if not row.getCells():
            continue
        for c_idx, cell in enumerate(row.getCells()):
            if cell is None:
                continue
            text = cell.getText()
            if text is None or not text.strip():
                continue
            lower = text.lower()

            group_suffix = _classify_group_suffix(lower, defaultQuarter)
            m = YEAR_PATTERN.search(text)
            has_year = m is not None
            year = int(m.group(1)) if has_year else 0
            mon_in_cell = _find_month(lower)

            if group_suffix is not None:
                # A group header cell: "Three Months Ended December 31,",
                # "Six Months Ended December 31,", "Year Ended June 30,".
                # If it also contains a year (self-contained), we'll handle below.
                group_headers.append(_GroupHeader(c_idx, group_suffix, mon_in_cell))

            if has_year and mon_in_cell is not None and group_suffix is None:
                # Self-contained standalone date like "September 30, 2025" with no
                # group qualifier -- treat as the quarter for that month.
                q = m2q[mon_in_cell]
                fy = _fiscal_year(year, MONTH_NUM[mon_in_cell], fiscal_year_end_month)
                self_contained.append(_Column(c_idx, f"{fy}{q}", _detect_currency(lower)))
                continue

            if group_suffix is not None and has_year:
                # Self-contained group cell with year: "Year Ended June 30, 2025"
                # or "Quarter ended September 30, 2025". When a month is present,
                # derive the quarter from the month rather than trusting the
                # generic defaultQuarter (which may reflect a different column's
                # period when a table mixes multiple quarters).
                final_suffix = group_suffix
                if mon_in_cell is not None:
                    year = _fiscal_year(year, MONTH_NUM[mon_in_cell], fiscal_year_end_month)
                    if final_suffix in ("Q1", "Q2", "Q3", "Q4"):
                        final_suffix = m2q[mon_in_cell]
                self_contained.append(_Column(c_idx, f"{year}{final_suffix}", _detect_currency(lower)))
                continue

            if has_year:
                # Bare year cell -- assign later based on groups
                plain_year_columns.append((c_idx, year))
                plain_year_currencies.append(_detect_currency(lower) or "")

    # ----- Pass 2: assign periods to plain year columns -----
    if plain_year_columns:
        group_headers.sort(key=lambda g: g.columnIdx)
        year_sorted_idx = sorted(range(len(plain_year_columns)),
                                 key=lambda i: plain_year_columns[i][0])
        year_columns_sorted = [plain_year_columns[i] for i in year_sorted_idx]
        year_currencies_sorted = [plain_year_currencies[i] for i in year_sorted_idx]

        def _adjust(year: int, suffix: str, mon: Optional[str]) -> Tuple[int, str]:
            """Apply fiscal-year offset and resolve a *generic* quarter suffix via month.

            We only replace the suffix with a month-derived quarter when the suffix
            is the defaultQuarter placeholder AND defaultQuarter itself is a single
            quarter (Q1/Q2/Q3/Q4).  When defaultQuarter is FY or a YTD marker (which
            happens when determinePeriodType already classifies the table as FY/H1/
            etc.), the suffix is already correct and must not be overridden with
            the month's quarter (June -> Q4 would incorrectly turn FY into Q4).
            """
            if mon is not None:
                fy = _fiscal_year(year, MONTH_NUM[mon], fiscal_year_end_month)
                # Only resolve to a specific quarter when the suffix is a generic
                # quarter placeholder AND the default itself is a quarter.
                if suffix == defaultQuarter and defaultQuarter in ("Q1", "Q2", "Q3", "Q4"):
                    suffix = m2q[mon]
                return fy, suffix
            return year, suffix

        if not group_headers:
            # No group headers -- bare year columns. Use label-derived default if present,
            # or fall back to col_month_map (from document-level period hint or header rows),
            # else defaultQuarter.
            for i, (col_idx, year) in enumerate(year_columns_sorted):
                if col_idx in col_month_map:
                    suffix, mon = col_month_map[col_idx]
                    if mon is not None and suffix in ("Q1","Q2","Q3","Q4"):
                        fy = _fiscal_year(year, MONTH_NUM[mon], fiscal_year_end_month)
                    else:
                        fy = year
                    out.append(_Column(col_idx, f"{fy}{suffix}", year_currencies_sorted[i]))
                else:
                    suffix0 = label_default_suffix if label_default_suffix else defaultQuarter
                    mon0 = label_default_month
                    fy, s = _adjust(year, suffix0, mon0)
                    out.append(_Column(col_idx, f"{fy}{s}", year_currencies_sorted[i]))
        else:
            num_groups = len(group_headers)
            num_years = len(year_columns_sorted)
            # When years divide evenly by groups (typical case: each group has N
            # comparison years), assign in document order -- this handles both
            # flat same-width rows (new MSFT layout) and colspan-compressed rows
            # (old MSFT layout) where column position alone is unreliable.
            if num_years % num_groups == 0:
                years_per_group = num_years // num_groups
                for gi, gh in enumerate(group_headers):
                    mon = gh.month
                    for y in range(years_per_group):
                        i = gi * years_per_group + y
                        col_idx, year = year_columns_sorted[i]
                        if col_idx in col_month_map and not mon:
                            suffix, cm_mon = col_month_map[col_idx]
                            if cm_mon is not None and suffix in ("Q1","Q2","Q3","Q4"):
                                fy = _fiscal_year(year, MONTH_NUM[cm_mon], fiscal_year_end_month)
                            else:
                                fy = year
                            out.append(_Column(col_idx, f"{fy}{suffix}", year_currencies_sorted[i]))
                        else:
                            fy, s = _adjust(year, gh.suffix, mon)
                            out.append(_Column(col_idx, f"{fy}{s}", year_currencies_sorted[i]))
            else:
                # Fallback: position-based assignment using column indices.
                # For each year column, find the rightmost group whose columnIdx
                # is <= year column (with small tolerance for $ in adjacent cell).
                group_boundaries = [g.columnIdx for g in group_headers]
                for i, (col_idx, year) in enumerate(year_columns_sorted):
                    if col_idx in col_month_map:
                        suffix, cm_mon = col_month_map[col_idx]
                        if cm_mon is not None and suffix in ("Q1","Q2","Q3","Q4"):
                            fy = _fiscal_year(year, MONTH_NUM[cm_mon], fiscal_year_end_month)
                        else:
                            fy = year
                        out.append(_Column(col_idx, f"{fy}{suffix}", year_currencies_sorted[i]))
                        continue
                    gi = 0
                    for j, gb in enumerate(group_boundaries):
                        if gb <= col_idx + 1:
                            gi = j
                        else:
                            break
                    gh = group_headers[gi]
                    fy, s = _adjust(year, gh.suffix, gh.month)
                    out.append(_Column(col_idx, f"{fy}{s}", year_currencies_sorted[i]))

    out.extend(self_contained)
    out.sort(key=lambda c: c.columnIdx)
    return out


def _build_column_month_map(table: FinancialTable, m2q: dict) -> dict:
    """Scan the first few header rows to associate each data column with (suffix, month_name).

    suffix is "Q1"/"Q2"/"Q3"/"Q4"/"FY"/"QTD6"/"QTD9" determined from the group header
    wording ("Three Months Ended" → quarter; "Year Ended" → FY; etc.).

    For headers like "Three Months Ended December 31," placed in a cell spanning columns,
    propagate the (suffix, month) to the year-bearing cells to its right until the next
    header.  Also picks up period phrases placed in row labels (e.g. a label cell that
    reads "Three Months Ended September 30,").
    """
    col_map: dict = {}
    rows = table.getRows()
    if not rows:
        return col_map
    header_end = min(HEADER_SCAN_ROWS, len(rows))
    # (col_idx, suffix, month) -- suffix comes from classifying the header phrase
    month_cells: List[Tuple[int, str, str]] = []
    for r in range(header_end):
        row = rows[r]
        if row is None:
            continue
        # Cells
        if row.getCells():
            for c_idx, cell in enumerate(row.getCells()):
                if cell is None:
                    continue
                text = cell.getText()
                if text is None:
                    continue
                lower = text.lower()
                mon = _find_month(lower)
                if mon is not None:
                    suffix = _classify_group_suffix(lower, None)
                    if suffix is None:
                        suffix = m2q[mon]
                    month_cells.append((c_idx, suffix, mon))
        # Row label -- e.g. "Three Months Ended September 30," in Microsoft 10-Q
        if row.getLabel():
            lbl_lower = row.getLabel().lower()
            mon = _find_month(lbl_lower)
            if mon is not None:
                if "three month" in lbl_lower or "quarter" in lbl_lower:
                    month_cells.append((1, m2q[mon], mon))
                elif "six month" in lbl_lower:
                    month_cells.append((1, "QTD6", mon))
                elif "nine month" in lbl_lower:
                    month_cells.append((1, "QTD9", mon))
                elif "year ended" in lbl_lower or "fiscal year" in lbl_lower:
                    month_cells.append((1, "FY", mon))
    if not month_cells:
        return col_map
    month_cells.sort(key=lambda x: x[0])
    # Collect year-cell column indices
    year_cols: List[int] = []
    for r in range(header_end):
        row = rows[r]
        if row is None or not row.getCells():
            continue
        for c_idx, cell in enumerate(row.getCells()):
            if cell is None:
                continue
            text = cell.getText()
            if text and YEAR_PATTERN.search(text):
                year_cols.append(c_idx)
    for yc in year_cols:
        best: Optional[Tuple[int, str, str]] = None
        for mc, suffix, mon in month_cells:
            if mc <= yc + 1:
                best = (mc, suffix, mon)
            else:
                break
        if best is not None:
            _, suffix, mon = best
            col_map[yc] = (suffix, mon)
    return col_map


def _classify_hint_suffix(hint_lower: str, defaultQuarter: str,
                          m2q: dict, mon: str) -> str:
    """Classify a document-level period hint into a suffix (Q1/Q2/Q3/Q4/FY/QTD6/QTD9)."""
    if "year ended" in hint_lower or "fiscal year" in hint_lower or "twelve months" in hint_lower:
        return "FY"
    if "nine month" in hint_lower:
        return "QTD9"
    if "six month" in hint_lower:
        return "QTD6"
    # "quarter ended" or "three months ended"
    return m2q.get(mon, defaultQuarter)


def _scan_headers_for_period(table: FinancialTable, m2q: dict,
                              defaultQuarter: str):
    """Examine table.headers (text of the rows skipped by _parse_rows) for a
    period phrase like "Three months ended March 31," or "Year ended March 31,".
    Returns (month_name, suffix) or (None, None) if no clear period phrase found.

    This handles BABA-style press-release tables where the period group header
    occupies TR0 (consumed into table.headers) rather than a TableRow.

    To avoid false positives on other layouts (MSFT, etc.) where _parse_headers
    may collect unrelated cells, we require that the header text explicitly
    contains a period-starter keyword ("three months", "six months", "nine
    months", "twelve months", "year ended", "fiscal year", "quarter ended")
    together with a month name.
    """
    headers = table.getHeaders() or []
    combined = " ".join(h for h in headers if h).lower().replace("\xa0", " ")
    combined = re.sub(r"[,\s]+", " ", combined)
    mon = _find_month(combined)
    if mon is None:
        return None, None
    # Require an explicit period-starter keyword to avoid false matches
    has_period_kw = any(kw in combined for kw in (
        "three months", "six months", "nine months", "twelve months",
        "year ended", "fiscal year", "quarter ended",
    ))
    if not has_period_kw:
        return None, None
    # Classify suffix from header text
    if any(kw in combined for kw in ("year ended", "fiscal year", "twelve months")):
        return mon, "FY"
    if "nine months" in combined:
        return mon, "QTD9"
    if "six months" in combined:
        return mon, "QTD6"
    # "three months" / "quarter" → use month→quarter mapping
    return mon, m2q.get(mon, defaultQuarter)


def _fiscal_year(calendar_year: int, month: int, fy_end_month: int) -> int:
    """Map (calendar_year, month) to the fiscal year label.

    For a company whose FY ends in month ``fy_end_month`` (1-12), the FY is labeled
    by the calendar year in which the FY ends.

    Examples (fy_end_month=6 for Microsoft, FY Jul-Jun):
      Sep 2025 (m=9) -> FY2026 (since 9 > 6, FY ends in 2026)
      Dec 2025 (m=12) -> FY2026
      Mar 2026 (m=3) -> FY2026 (3 <= 6, FY ends in 2026)
      Jun 2026 (m=6) -> FY2026

    For calendar-year companies (fy_end_month=12):
      All months return calendar_year.
    """
    if not calendar_year:
        return calendar_year
    fy_end_month = int(fy_end_month) if fy_end_month else 12
    if fy_end_month <= 0 or fy_end_month > 12:
        fy_end_month = 12
    if month <= fy_end_month:
        return calendar_year
    return calendar_year + 1


def _find_month(lower: str) -> Optional[str]:
    for mon in MONTH_NAMES:
        if mon in lower:
            return mon
    return None


def _classify_group_suffix(lower: str, defaultQuarter: str) -> Optional[str]:
    # Normalize hyphens: "three-month" -> "three month"
    l = lower.replace("-", " ")
    if ("nine month" in l or "9 month" in l
            or "year to date" in l or "ytd" in l):
        return "QTD9"
    if "six month" in l or "6 month" in l:
        return "QTD6"
    if ("year ended" in l or "twelve months" in l
            or "12 months" in l or "full year" in l
            or "fiscal year" in l):
        return "FY"
    if "three month" in l or "3 month" in l or "quarter" in l:
        # Return the defaultQuarter only if it looks like a real quarter/FY marker;
        # otherwise return None so that self-contained date cells can resolve via
        # their own month.
        if defaultQuarter and defaultQuarter in (
                "Q1", "Q2", "Q3", "Q4", "FY", "QTD6", "QTD9", "H1", "H2"):
            return defaultQuarter
        return None
    return None


def _detect_currency(lower: str) -> Optional[str]:
    if "rmb" in lower or "cny" in lower:
        return "RMB"
    if "usd" in lower or "us$" in lower:
        return "USD"
    if "hkd" in lower or "hk$" in lower:
        return "HKD"
    if "eur" in lower:
        return "EUR"
    if "gbp" in lower:
        return "GBP"
    if "jpy" in lower:
        return "JPY"
    return None
