# -*- coding: utf-8 -*-
"""FilingContext — port of PdfFileSegmentParser.FilingContext.

Extracted to its own module to avoid a circular import between pdf_layout_handler
(which needs FilingContext in apply() signatures) and pdf_parser (which defines it).
"""
from __future__ import annotations

import re
from pathlib import Path
from typing import Optional


_FILING_DIR_PATTERN = re.compile(r"fil_hk_[^_]+_(\d{4})_([A-Z0-9]+)", re.IGNORECASE)


class FilingContext:
    """Filing period context derived from the parent directory name."""

    def __init__(self, year: int = 0, period: str = ""):
        self.year = year
        self.period = (period or "").upper()

    @staticmethod
    def empty() -> "FilingContext":
        return FilingContext(0, "")

    @staticmethod
    def parse(file_path: Optional[Path]) -> "FilingContext":
        if file_path is None:
            return FilingContext.empty()
        parent = file_path.parent
        if parent is None:
            return FilingContext.empty()
        m = _FILING_DIR_PATTERN.search(parent.name)
        if not m:
            return FilingContext.empty()
        return FilingContext(int(m.group(1)), m.group(2).upper())

    def currentQuarter(self) -> str:
        if self.year <= 0:
            return ""
        p = self.period
        if p == "H1":
            return f"{self.year}Q2"
        if p == "H2":
            return f"{self.year}Q4"
        if p == "FY":
            return f"{self.year}Q4"
        if p in ("Q1", "Q2", "Q3", "Q4"):
            return f"{self.year}{p}"
        return f"{self.year}{p}"

    def priorQuarter(self) -> str:
        if self.year <= 0:
            return ""
        cur = self.currentQuarter()
        if len(cur) >= 6:
            tail = cur[-2:]
            return f"{self.year - 1}{tail}"
        return ""

    def currentPeriod(self) -> str:
        if self.year <= 0 or not self.period:
            return ""
        return f"{self.year}{self.period}"

    def priorPeriod(self) -> str:
        if self.year <= 0 or not self.period:
            return ""
        return f"{self.year - 1}{self.period}"

    def resolvePeriod(self, code: Optional[str]) -> str:
        if code is None or code == "":
            return ""
        if code == "CURRENT_Q":
            return self.currentQuarter()
        if code == "PRIOR_Q":
            return self.priorQuarter()
        if code == "CURRENT_P":
            return self.currentPeriod()
        if code == "PRIOR_P":
            return self.priorPeriod()
        return code  # literal value

    def __repr__(self) -> str:
        return (f"FilingContext(year={self.year}, period='{self.period}', "
                f"currentQ={self.currentQuarter()}, priorQ={self.priorQuarter()})")
