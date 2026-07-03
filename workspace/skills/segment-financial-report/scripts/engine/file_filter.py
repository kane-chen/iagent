# -*- coding: utf-8 -*-
"""FinancialFileFilter — port of io.invest.iagent.service.extraction.service.FinancialFileFilter.

Walks workspace/portfolio/<TICKER>/filings/... and returns candidate report files:
- HK/CN markets → PDF files
- US markets → SEC primary HTML file (10-K/10-Q primary doc, or ex99-1 for 6-K/20-F)
"""
from __future__ import annotations

import json
import logging
import re
from pathlib import Path
from typing import List, Optional

logger = logging.getLogger(__name__)


_US_PRIMARY_DOC_PATTERN_CACHE: dict[str, re.Pattern] = {}
_EX99_1_PATTERN = re.compile(r".*ex[-_]?99[-_]?1\.htm[l]?$", re.IGNORECASE)


def _us_primary_doc_pattern(ticker: str) -> re.Pattern:
    if ticker in _US_PRIMARY_DOC_PATTERN_CACHE:
        return _US_PRIMARY_DOC_PATTERN_CACHE[ticker]
    pat = re.compile(r"^" + re.escape(ticker.lower()) + r"[-_]\d{8}\.htm[l]?$", re.IGNORECASE)
    _US_PRIMARY_DOC_PATTERN_CACHE[ticker] = pat
    return pat


def _camel_to_snake(name: str) -> str:
    s1 = re.sub(r"([A-Z])", r"_\1", name)
    return s1.lower().lstrip("_")


def _read_field(meta: dict, camel_case: str) -> Optional[str]:
    """Read a field from meta.json, trying both camelCase and snake_case names.
    Returns the value as a string (ints like fiscalYear=2025 are converted)."""
    for key in (camel_case, _camel_to_snake(camel_case)):
        v = meta.get(key)
        if v is None:
            continue
        if isinstance(v, str):
            if v.strip():
                return v.strip()
        elif isinstance(v, (int, float)):
            return str(v)
    return None


class FinancialFileFilter:
    def __init__(self, workspace: Path):
        self.workspace = workspace

    def filter(self, ticker: str, fiscal_year_start: Optional[str],
               fiscal_year_end: Optional[str]) -> List[Path]:
        ticker = ticker.upper()
        filings_dir = self.workspace / "portfolio" / ticker / "filings"
        if not filings_dir.is_dir():
            return []
        result: List[Path] = []
        for entry in sorted(filings_dir.iterdir()):
            if not entry.is_dir():
                continue
            result.extend(self._filter_one(entry, ticker, fiscal_year_start, fiscal_year_end))
        return result

    def _filter_one(self, filing_dir: Path, ticker: str,
                    fy_start: Optional[str], fy_end: Optional[str]) -> List[Path]:
        meta_file = filing_dir / "meta.json"
        if not meta_file.exists():
            return []
        try:
            meta = json.loads(meta_file.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            return []
        if not self._is_active_complete(meta):
            return []
        if not self._within(meta, fy_start, fy_end):
            return []
        market = (_read_field(meta, "market") or "").upper()
        if market == "US":
            return self._us_primary_files(filing_dir, meta, ticker)
        return self._pdf_files(filing_dir, meta)

    @staticmethod
    def _is_active_complete(meta: dict) -> bool:
        is_del = bool(meta.get("is_deleted") or meta.get("deleted"))
        if "ingest_complete" not in meta:
            return not is_del
        if meta.get("ingest_complete") is False:
            return False
        return not is_del

    @staticmethod
    def _within(meta: dict, fy_start: Optional[str], fy_end: Optional[str]) -> bool:
        fy = _read_field(meta, "fiscalYear")
        if not fy:
            return False
        if fy_start and fy_start > fy:
            return False
        if fy_end and fy_end < fy:
            return False
        return True

    @staticmethod
    def _meta_file_names(meta: dict) -> List[str]:
        names: List[str] = []
        files = meta.get("files")
        if isinstance(files, list):
            for n in files:
                if isinstance(n, str) and n.strip():
                    names.append(n)
        pd = meta.get("primary_document")
        if isinstance(pd, str) and pd.strip():
            names.append(pd)
        pf = meta.get("primaryFile")
        if isinstance(pf, dict):
            n = pf.get("name")
            if isinstance(n, str) and n.strip():
                names.append(n)
        return names

    @staticmethod
    def _distinct_existing(paths: List[Path]) -> List[Path]:
        seen = set()
        out: List[Path] = []
        for p in paths:
            if p.exists() and p not in seen:
                seen.add(p)
                out.append(p)
        return out

    def _pdf_files(self, filing_dir: Path, meta: dict) -> List[Path]:
        pdfs: List[Path] = []
        for name in self._meta_file_names(meta):
            if name.lower().endswith(".pdf"):
                pdfs.append(filing_dir / name)
        return self._distinct_existing(pdfs)

    def _us_primary_files(self, filing_dir: Path, meta: dict, ticker: str) -> List[Path]:
        primary_pat = _us_primary_doc_pattern(ticker)
        primaries: List[Path] = []
        ex99: List[Path] = []
        for name in self._meta_file_names(meta):
            lower = name.lower()
            p = filing_dir / name
            if primary_pat.match(lower):
                primaries.append(p)
            elif _EX99_1_PATTERN.match(lower):
                ex99.append(p)
        return self._distinct_existing(primaries + ex99)
