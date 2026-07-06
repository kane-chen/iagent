# -*- coding: utf-8 -*-
"""财报文件过滤器：遍历 workspace/portfolio/<TICKER>/filings/，返回候选报告文件。
- HK/CN 市场 → PDF 文件
- US 市场 → SEC 主 HTML 文件（10-K/10-Q 主文档，6-K/20-F 走 ex99-1）
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


class FilterDiagnostics:
    """收集文件过滤阶段的诊断信息，供上层生成友好的错误提示。"""
    def __init__(self) -> None:
        self.filings_dir_exists: bool = True
        self.total_filing_dirs: int = 0
        self.missing_meta: list[str] = []
        self.corrupt_meta: list[str] = []
        self.incomplete: list[str] = []
        self.out_of_range: list[str] = []
        self.no_files_in_meta: list[str] = []
        self.selected: list[Path] = []
        self.fy_start: Optional[str] = None
        self.fy_end: Optional[str] = None
        self.ticker: str = ""

    def summarize(self) -> str:
        lines: list[str] = []
        if not self.filings_dir_exists:
            lines.append(
                f"未找到 {self.ticker} 的财报目录（workspace/portfolio/{self.ticker}/filings/ 不存在）。"
                f"请先用 futu-filing skill 下载该公司的财报。"
            )
            return "\n".join(lines)
        if not self.selected and self.total_filing_dirs == 0:
            lines.append(
                f"{self.ticker} 的财报目录为空（workspace/portfolio/{self.ticker}/filings/ 下没有子目录）。"
                f"请先用 futu-filing skill 下载财报。"
            )
            return "\n".join(lines)
        if not self.selected:
            lines.append(f"在 {self.total_filing_dirs} 个财报目录中没有找到符合条件的文件：")
            rng = ""
            if self.fy_start or self.fy_end:
                rng = f"（财年范围 {self.fy_start or '不限'} ~ {self.fy_end or '不限'}）"
            lines.append(f"  - 过滤财年范围{rng}")
            if self.incomplete:
                lines.append(f"  - {len(self.incomplete)} 个目录未完成下载或已被标记删除: "
                             f"{', '.join(self.incomplete[:3])}{'...' if len(self.incomplete) > 3 else ''}")
            if self.out_of_range:
                lines.append(f"  - {len(self.out_of_range)} 个目录在指定财年范围外")
            if self.missing_meta:
                lines.append(f"  - {len(self.missing_meta)} 个目录缺 meta.json（可能下载中断）")
            if self.corrupt_meta:
                lines.append(f"  - {len(self.corrupt_meta)} 个目录 meta.json 损坏")
            if self.no_files_in_meta:
                lines.append(f"  - {len(self.no_files_in_meta)} 个目录 meta.json 中没登记文件")
            if self.fy_start or self.fy_end:
                lines.append("  提示：尝试放宽 --fiscal-year-start/--fiscal-year-end 范围，或不带范围参数重跑。")
            return "\n".join(lines)
        if self.missing_meta or self.corrupt_meta or self.incomplete:
            lines.append(f"（注意：{len(self.missing_meta)} 个缺meta、{len(self.corrupt_meta)} 个meta损坏、"
                         f"{len(self.incomplete)} 个未完成，共选中 {len(self.selected)} 个文件）")
        return "\n".join(lines) if lines else ""


class FinancialFileFilter:
    def __init__(self, workspace: Path):
        self.workspace = workspace
        self.last_diagnostics: Optional[FilterDiagnostics] = None

    def filter(self, ticker: str, fiscal_year_start: Optional[str],
               fiscal_year_end: Optional[str]) -> List[Path]:
        ticker = ticker.upper()
        diag = FilterDiagnostics()
        diag.ticker = ticker
        diag.fy_start = fiscal_year_start
        diag.fy_end = fiscal_year_end
        self.last_diagnostics = diag

        filings_dir = self.workspace / "portfolio" / ticker / "filings"
        if not filings_dir.is_dir():
            diag.filings_dir_exists = False
            return []

        result: List[Path] = []
        for entry in sorted(filings_dir.iterdir()):
            if not entry.is_dir():
                continue
            diag.total_filing_dirs += 1
            got = self._filter_one(entry, ticker, fiscal_year_start, fiscal_year_end, diag)
            result.extend(got)
        diag.selected = result
        return result

    def _filter_one(self, filing_dir: Path, ticker: str,
                    fy_start: Optional[str], fy_end: Optional[str],
                    diag: FilterDiagnostics) -> List[Path]:
        meta_file = filing_dir / "meta.json"
        if not meta_file.exists():
            diag.missing_meta.append(filing_dir.name)
            return []
        try:
            meta = json.loads(meta_file.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            diag.corrupt_meta.append(filing_dir.name)
            return []
        if not self._is_active_complete(meta):
            diag.incomplete.append(filing_dir.name)
            return []
        if not self._within(meta, fy_start, fy_end):
            diag.out_of_range.append(filing_dir.name)
            return []
        market = (_read_field(meta, "market") or "").upper()
        if market == "US":
            files = self._us_primary_files(filing_dir, meta, ticker)
        else:
            files = self._pdf_files(filing_dir, meta)
        if not files:
            diag.no_files_in_meta.append(filing_dir.name)
        return files

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
