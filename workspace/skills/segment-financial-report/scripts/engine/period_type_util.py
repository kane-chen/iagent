# -*- coding: utf-8 -*-
"""表格级周期分类器：判断 FinancialTable 属于 FY/H1/Q1..Q4。

支持通过 ``fiscal_year_end_month`` 指定公司的财年结束月（默认 12 = 日历年），
从而正确将 Mar/Jun/Sep/Dec 等月份名映射到对应财季。例如：
- fiscal_year_end_month=12（默认，日历年）：Mar→Q1, Jun→Q2, Sep→Q3, Dec→Q4
- fiscal_year_end_month=6（Microsoft 等 6 月财年结束公司）：Sep→Q1, Dec→Q2, Mar→Q3, Jun→Q4
- fiscal_year_end_month=3（阿里等零售/HK 3 月财年结束）：Jun→Q1, Sep→Q2, Dec→Q3, Mar→Q4
"""
from __future__ import annotations

import re
from typing import Optional

from .model import FinancialTable, TableRow


# 月份名（1 月起）与 0 基下标
MONTH_LIST = ["january", "february", "march", "april", "may", "june",
              "july", "august", "september", "october", "november", "december"]
_MONTH_INDEX = {name: i for i, name in enumerate(MONTH_LIST)}
# 财历溢出阈值：结束日落在次月 1~7 号视为上一自然月所属财季（见 effective_period_month）
_SPILL_DAY = 7


def effective_period_month(lower_text: Optional[str], month: Optional[str]) -> Optional[str]:
    """4-4-5/13 周财历（如 Apple）财季结束日可能落在次月初 1~7 号
    （如 FY2023 Q2 结束于 "April 1, 2023"、Q3 结束于 "July 1, 2023"）。
    这类结束日经济上属于上一自然月对应的财季；若不归一，April 会被映射成财年 Q3、
    July 成 Q4，导致该季整体错位一季。月末（≥25 号）等正常结束日不受影响。

    :param lower_text: 含 "<Month> <day>" 的期间文本（已转小写）
    :param month:      文本中识别出的月份名
    :return:           归一后用于定财季的月份名
    """
    if not month or not lower_text:
        return month
    rx = re.compile(r"\b" + re.escape(month) + r"\s+(\d{1,2})\b")
    m = rx.search(lower_text)
    if m and int(m.group(1)) <= _SPILL_DAY:
        return MONTH_LIST[(_MONTH_INDEX[month] - 1) % 12]
    return month


# 日历年（Dec FY-end）月份→财季映射
_CALENDAR_MONTH_TO_QUARTER = {
    # Q1
    "january": "Q1", "february": "Q1", "march": "Q1",
    # Q2
    "april": "Q2", "may": "Q2", "june": "Q2",
    # Q3
    "july": "Q3", "august": "Q3", "september": "Q3",
    # Q4
    "october": "Q4", "november": "Q4", "december": "Q4",
}


def _build_month_to_quarter_map(fy_end_month: int) -> dict:
    """根据财年结束月构建 month name → Qn 的映射。"""
    fy_end_month = int(fy_end_month) if fy_end_month else 12
    if fy_end_month < 1 or fy_end_month > 12:
        fy_end_month = 12
    if fy_end_month == 12:
        return dict(_CALENDAR_MONTH_TO_QUARTER)
    # Q1 结束月 = (fy_end + 3 - 1) % 12 + 1  等等，直接枚举更直观
    month_names = ["january", "february", "march", "april", "may", "june",
                   "july", "august", "september", "october", "november", "december"]
    # 季度结束月（按 Q1, Q2, Q3, Q4 顺序）
    # Q1 ends: (fy_end % 12) + 3 months after FY start; simpler enumerate:
    # FY starts month = fy_end_month + 1 (wrapping)
    # Q1: months [start+0..start+2] 三个月，end = start+2
    # start_month (1-indexed, first month of FY): fy_end_month % 12 + 1
    start = (fy_end_month % 12) + 1  # 1-12
    mapping = {}
    for q in range(1, 5):
        # 该季度的三个月（0-indexed offset 0,1,2 from start）
        for offset in range(3):
            m_idx_0 = (start - 1 + (q - 1) * 3 + offset) % 12
            mapping[month_names[m_idx_0]] = f"Q{q}"
    return mapping


def determinePeriodType(table: FinancialTable, fiscal_year_end_month: int = 12) -> str:
    title = (table.getTitle() or "").lower()
    m2q = _build_month_to_quarter_map(fiscal_year_end_month)
    pt = _from_title(title, m2q)
    if not pt:
        # Use document-level period hint (e.g., "Three Months Ended June 30, 2025"
        # captured from the text preceding the table in press-release HTML).
        hint = (table.period or "").lower()
        if hint:
            # _from_title works on any period-bearing text (it checks for
            # three/six/nine/twelve months keywords and month names).
            pt = _from_title(hint, m2q)
            if not pt:
                pt = _from_period_phrase(hint, m2q)
    if not pt:
        for h in table.getHeaders() or []:
            r = _from_header(h, m2q)
            if r:
                pt = r
                break
    if not pt:
        pt = _from_rows(table.getRows(), m2q)
    return pt or ""


def _from_period_phrase(lower_text: str, m2q: dict) -> str:
    """Extract quarter/FY from a phrase like 'quarter ended june 30, 2025'."""
    if not lower_text:
        return ""
    if "year ended" in lower_text or "fiscal year ended" in lower_text or "twelve months ended" in lower_text:
        return "FY"
    if "nine months ended" in lower_text:
        return "QTD9"
    if "six months ended" in lower_text or "half year" in lower_text:
        return "QTD6"
    if "quarter ended" in lower_text or "three months ended" in lower_text:
        mon = effective_period_month(lower_text, _month_from_text(lower_text))
        if mon:
            return m2q.get(mon, "Q1")
        return "Q1"
    return ""


def _from_rows(rows, m2q: dict) -> Optional[str]:
    """Detect the table's primary period type by scanning cells for period keywords.

    When a table mixes quarter columns with YTD/FY columns (common in H1/Q2 press
    releases which show both Three Months and Six Months Ended), prefer the
    single-quarter type so that period_sequence can correctly label each column
    (QTD columns are filtered downstream). FY is detected first as it dominates
    annual reports.
    """
    if not rows:
        return None
    has_fy = any(_row_contains(r, kw) for r in rows
                 for kw in ("year ended", "twelve months ended",
                            "fiscal year ended", "12 months ended"))
    has_qtd9 = any(_row_contains(r, kw) for r in rows
                   for kw in ("nine months ended", "9 months ended"))
    has_qtd6 = any(_row_contains(r, kw) for r in rows
                   for kw in ("six months ended", "6 months ended", "half year"))
    has_quarter = any(_row_contains(r, kw) for r in rows
                      for kw in ("three months ended", "three month ended",
                                 "three-month", "3 months ended", "3 month ended",
                                 "quarter ended"))
    # FY dominates (annual report table, no single-quarter breakdown)
    if has_fy and not has_quarter and not has_qtd9 and not has_qtd6:
        return "FY"
    # Single-quarter takes precedence over YTD for mixed press-release tables
    # (e.g. Q2 releases show both Three Months and Six Months Ended).
    if has_quarter:
        latest = _find_latest_year_month(rows)
        if latest is not None:
            _y, mname = latest
            return m2q.get(mname, "Q1")
        return "Q1"
    if has_qtd9:
        return "QTD9"
    if has_qtd6:
        return "QTD6"
    if has_fy:
        return "FY"
    return None


def _row_contains(row: TableRow, keyword: str) -> bool:
    label = (row.getLabel() or "").lower()
    if keyword in label:
        return True
    for c in row.getCells() or []:
        if c is not None and c.getText() is not None:
            if keyword in c.getText().lower():
                return True
    return False


_MONTH_SET = {"january","february","march","april","may","june",
              "july","august","september","october","november","december"}


def _find_latest_year_month(rows) -> Optional[tuple]:
    """Scan all cells for (year, month_name) pairs and return the latest chronologically.

    Looks for phrases like ``september 30, 2025`` or ``june 2025`` in cell text.
    Returns (year_int, month_name_lower) or None if no date found. 返回的月份名已经过
    财历溢出归一（结束日落在次月 1~7 号时归到上一月），比较仍按真实日期先后。
    """
    import re
    # Match <Month> <day>, <year>（day 必填：无日的纯年月无法判断溢出，回退原月）
    _DATE_RE = re.compile(
        r"(january|february|march|april|may|june|july|august|september|"
        r"october|november|december)\s+(\d{1,2})\s*,?\s*(20\d{2})",
        re.IGNORECASE,
    )
    # best: (year, real_month_index, effective_month)
    best: Optional[tuple] = None
    for row in rows or []:
        # Check label
        for text_src in [(row.getLabel() if row else "")] + [
            (c.getText() if c else "") for c in (row.getCells() if row else [])
        ]:
            if not text_src:
                continue
            low = text_src.lower().replace("\xa0", " ")
            for m in _DATE_RE.finditer(low):
                mon = m.group(1).lower()
                day = int(m.group(2))
                yr = int(m.group(3))
                eff = MONTH_LIST[(_MONTH_INDEX[mon] - 1) % 12] if day <= _SPILL_DAY else mon
                cand = (yr, _MONTH_INDEX[mon], eff)
                if best is None or (cand[0], cand[1]) > (best[0], best[1]):
                    best = cand
    if best is None:
        return None
    return (best[0], best[2])


def _date_tuple_gt(a: tuple, b: tuple) -> bool:
    """Return True if (year, month_name) a is later than b."""
    month_order = {n: i + 1 for i, n in enumerate(
        ["january","february","march","april","may","june",
         "july","august","september","october","november","december"])}
    if a[0] != b[0]:
        return a[0] > b[0]
    return month_order.get(a[1], 0) > month_order.get(b[1], 0)


def _month_from_text(lower_text: str) -> Optional[str]:
    """Return the latest month name appearing in lower_text, or None."""
    found = None
    month_order = {n: i + 1 for i, n in enumerate(
        ["january","february","march","april","may","june",
         "july","august","september","october","november","december"])}
    for mon in _MONTH_SET:
        if mon in lower_text:
            if found is None or month_order[mon] > month_order[found]:
                found = mon
    return found


def _from_title(lower_title: str, m2q: dict) -> str:
    if not lower_title:
        return ""
    if any(x in lower_title for x in (
            "year ended", "fiscal year", "twelve months", "12 months", "full year")):
        return "FY"
    if "nine months" in lower_title or "9 months" in lower_title:
        return "QTD9"
    if any(x in lower_title for x in ("six months", "six-month", "half year")):
        return "QTD6"
    if "three months" in lower_title or "three-month" in lower_title or "quarter" in lower_title:
        mon = effective_period_month(lower_title, _month_from_text(lower_title))
        if mon:
            return m2q.get(mon, "Q1")
        return "Q1"
    return ""


def _from_header(header: Optional[str], m2q: dict) -> Optional[str]:
    if not header or not header.strip():
        return None
    h = header.lower().replace("\xa0", " ")
    if any(x in h for x in ("year ended", "fiscal year ended", "twelve months ended")):
        return "FY"
    if "nine months ended" in h or "9 months ended" in h:
        return "QTD9"
    if "six months ended" in h or "6 months ended" in h or "half year" in h:
        return "QTD6"
    if any(x in h for x in ("three months ended", "three month ended",
                            "three-month", "3 months ended", "quarter ended")):
        mon = effective_period_month(h, _month_from_text(h))
        if mon:
            return m2q.get(mon, "Q1")
        return "Q1"
    return None
