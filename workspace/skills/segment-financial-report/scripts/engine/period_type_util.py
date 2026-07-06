# -*- coding: utf-8 -*-
"""表格级周期分类器：判断 FinancialTable 属于 FY/H1/Q1..Q4。"""
from __future__ import annotations

from typing import Optional

from .model import FinancialTable, TableRow


def determinePeriodType(table: FinancialTable) -> str:
    title = (table.getTitle() or "").lower()
    pt = _from_title(title)
    if not pt:
        for h in table.getHeaders() or []:
            r = _from_header(h)
            if r:
                pt = r
                break
    if not pt:
        pt = _from_rows(table.getRows())
    return pt or ""


def _from_rows(rows) -> Optional[str]:
    if not rows:
        return None
    if any(_row_contains(r, "year ended") for r in rows):
        return "FY"
    if any(_row_contains(r, "three months ended") for r in rows):
        if any(_row_contains(r, "march") for r in rows):
            return "Q1"
        if any(_row_contains(r, "june") for r in rows):
            return "Q2"
        if any(_row_contains(r, "september") for r in rows):
            return "Q3"
        if any(_row_contains(r, "december") for r in rows):
            return "Q4"
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


def _from_title(lower_title: str) -> str:
    if not lower_title:
        return ""
    if "three months" in lower_title or "quarter" in lower_title:
        if any(m in lower_title for m in ("march", "march 31", "february", "january")):
            return "Q1"
        if any(m in lower_title for m in ("june", "may", "april")):
            return "Q2"
        if any(m in lower_title for m in ("september", "august", "july")):
            return "Q3"
        if any(m in lower_title for m in ("december", "november", "october")):
            return "Q4"
        return "Q1"
    if any(x in lower_title for x in ("six months", "six-month", "half year")):
        if "june" in lower_title or "first half" in lower_title:
            return "H1"
        return "H2"
    if any(x in lower_title for x in (
            "year ended", "fiscal year", "twelve months", "12 months", "full year")):
        return "FY"
    return ""


def _from_header(header: Optional[str]) -> Optional[str]:
    if not header or not header.strip():
        return None
    h = header.lower()
    if "year ended" in h:
        return "FY"
    if "six months ended" in h:
        return "H"
    if "three months ended" in h:
        if "march" in h:
            return "Q1"
        if "june" in h:
            return "Q2"
        if "september" in h:
            return "Q3"
        if "december" in h:
            return "Q4"
    return None
