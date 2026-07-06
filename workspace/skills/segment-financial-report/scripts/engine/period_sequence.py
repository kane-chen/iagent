# -*- coding: utf-8 -*-
"""列维度周期序列构建器：按列提取（period, currency）序列。"""
from __future__ import annotations

import re
from typing import List, Optional

from .model import FinancialTable

YEAR_PATTERN = re.compile(r"\b(20\d{2})\b")
HEADER_SCAN_ROWS = 5


class _Column:
    __slots__ = ("columnIdx", "period", "currency")

    def __init__(self, columnIdx: int, period: str, currency: Optional[str]):
        self.columnIdx = columnIdx
        self.period = period
        self.currency = currency


def build(table: FinancialTable, defaultQuarter: str) -> List[str]:
    cols = _collect_year_columns(table, defaultQuarter)
    return [c.period for c in cols]


def buildCurrencies(table: FinancialTable, defaultQuarter: str) -> List[str]:
    cols = _collect_year_columns(table, defaultQuarter)
    return [c.currency or "" for c in cols]


def _collect_year_columns(table: FinancialTable, defaultQuarter: str) -> List[_Column]:
    out: List[_Column] = []
    if table is None or not table.getRows():
        return out

    group_columns: List[List[int]] = []
    group_suffixes: List[str] = []
    self_contained: List[_Column] = []
    plain_year_columns: List[List[int]] = []
    plain_year_currencies: List[str] = []

    header_end = min(HEADER_SCAN_ROWS, len(table.getRows()))
    for r in range(header_end):
        row = table.getRows()[r]
        if row is None or not row.getCells():
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

            if group_suffix is not None and has_year:
                month_quarter = _month_to_quarter(lower)
                final_suffix = month_quarter if month_quarter else group_suffix
                self_contained.append(_Column(c_idx, f"{year}{final_suffix}", _detect_currency(lower)))
                continue

            if group_suffix is not None:
                group_columns.append([c_idx, len(group_suffixes)])
                group_suffixes.append(group_suffix)
            if has_year:
                plain_year_columns.append([c_idx, year])
                plain_year_currencies.append(_detect_currency(lower) or "")

    if plain_year_columns:
        # sort plain year columns by column idx
        order = sorted(range(len(plain_year_columns)),
                       key=lambda i: plain_year_columns[i][0])
        group_columns.sort(key=lambda g: g[0])
        year_columns_sorted = [plain_year_columns[i] for i in order]
        year_currencies_sorted = [plain_year_currencies[i] for i in order]

        if not group_suffixes:
            for i, yc in enumerate(year_columns_sorted):
                out.append(_Column(yc[0], f"{yc[1]}{defaultQuarter}", year_currencies_sorted[i]))
        else:
            num_groups = len(group_suffixes)
            num_years = len(year_columns_sorted)
            if num_years % num_groups == 0:
                years_per_group = num_years // num_groups
                for g in range(num_groups):
                    suffix = group_suffixes[g]
                    for y in range(years_per_group):
                        yc = year_columns_sorted[g * years_per_group + y]
                        out.append(_Column(yc[0], f"{yc[1]}{suffix}",
                                           year_currencies_sorted[g * years_per_group + y]))
            else:
                for i, yc in enumerate(year_columns_sorted):
                    col_idx = yc[0]
                    year = yc[1]
                    suffix = defaultQuarter
                    for gc in group_columns:
                        if gc[0] <= col_idx:
                            suffix = group_suffixes[gc[1]]
                        else:
                            break
                    out.append(_Column(col_idx, f"{year}{suffix}", year_currencies_sorted[i]))

    out.extend(self_contained)
    out.sort(key=lambda c: c.columnIdx)
    return out


def _classify_group_suffix(lower: str, defaultQuarter: str) -> Optional[str]:
    if ("nine month" in lower or "9 month" in lower or "nine-month" in lower
            or "year to date" in lower or "ytd" in lower):
        return "QTD9"
    if "six month" in lower or "6 month" in lower or "six-month" in lower:
        return "QTD6"
    if ("year ended" in lower or "twelve months" in lower
            or "12 months" in lower or "full year" in lower
            or "fiscal year" in lower):
        return "FY"
    if "three month" in lower or "3 month" in lower or "quarter" in lower:
        return defaultQuarter
    return None


def _month_to_quarter(lower: str) -> Optional[str]:
    if any(m in lower for m in ("march", "january", "february")):
        return "Q1"
    if any(m in lower for m in ("june", "april", "may")):
        return "Q2"
    if any(m in lower for m in ("september", "july", "august")):
        return "Q3"
    if any(m in lower for m in ("december", "october", "november")):
        return "Q4"
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
