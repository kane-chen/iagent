# -*- coding: utf-8 -*-
"""表格级周期分类器：判断 FinancialTable 属于 FY/H1/Q1..Q4。

支持通过 ``fiscal_year_end_month`` 指定公司的财年结束月（默认 12 = 日历年），
从而正确将 Mar/Jun/Sep/Dec 等月份名映射到对应财季。例如：
- fiscal_year_end_month=12（默认，日历年）：Mar→Q1, Jun→Q2, Sep→Q3, Dec→Q4
- fiscal_year_end_month=6（Microsoft 等 6 月财年结束公司）：Sep→Q1, Dec→Q2, Mar→Q3, Jun→Q4
- fiscal_year_end_month=3（阿里等零售/HK 3 月财年结束）：Jun→Q1, Sep→Q2, Dec→Q3, Mar→Q4
"""
from __future__ import annotations

from typing import Optional

from .model import FinancialTable, TableRow


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
        for month, q in m2q.items():
            if month in lower_text:
                return q
        return "Q1"
    return ""


def _from_rows(rows, m2q: dict) -> Optional[str]:
    if not rows:
        return None
    # Look for "year ended" / "twelve months" / "fiscal year" phrase anywhere in table
    for kw in ("year ended", "twelve months ended", "fiscal year ended", "12 months ended"):
        if any(_row_contains(r, kw) for r in rows):
            return "FY"
    if any(_row_contains(r, "three months ended") for r in rows):
        for month, q in m2q.items():
            if any(_row_contains(r, month) for r in rows):
                return q
    if any(_row_contains(r, "six months ended") for r in rows):
        return "QTD6"
    if any(_row_contains(r, "nine months ended") for r in rows):
        return "QTD9"
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


def _from_title(lower_title: str, m2q: dict) -> str:
    if not lower_title:
        return ""
    if "three months" in lower_title or "quarter" in lower_title:
        for month, q in m2q.items():
            if month in lower_title:
                return q
        return "Q1"
    if "nine months" in lower_title or "9 months" in lower_title:
        return "QTD9"
    if any(x in lower_title for x in ("six months", "six-month", "half year")):
        return "QTD6"
    if any(x in lower_title for x in (
            "year ended", "fiscal year", "twelve months", "12 months", "full year")):
        return "FY"
    return ""


def _from_header(header: Optional[str], m2q: dict) -> Optional[str]:
    if not header or not header.strip():
        return None
    h = header.lower()
    if "year ended" in h:
        return "FY"
    if "six months ended" in h:
        return "QTD6"
    if "nine months ended" in h:
        return "QTD9"
    if "three months ended" in h:
        for month, q in m2q.items():
            if month in h:
                return q
    return None
