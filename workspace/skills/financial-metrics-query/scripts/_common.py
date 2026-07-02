#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
financial-metrics-query skill 内部公共层。
两个查询脚本（query_income.py / query_segments.py）共享的：
  - 路径推断（workspace/excels 定位）
  - 文件名解析（正则 + 时间戳排序取最新）
  - period 过滤器（fiscal_year + fiscal_period 组合）
  - 单元格数值规范化
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any, Iterable

# Windows 上 stdout 默认走 GBK，导致中文输出乱码或重定向落盘破损
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except AttributeError:
    pass


SCRIPT_DIR = Path(__file__).parent
SKILL_DIR = SCRIPT_DIR.parent
WORKSPACE_DIR = SKILL_DIR.parent.parent            # workspace/
DEFAULT_CONFIG = SKILL_DIR / "config" / "excel-format.json"


def load_config(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


# ---------------------------------------------------------------------------
# 文件挑选：同 ticker 多版本时取时间戳最新
# ---------------------------------------------------------------------------

def find_latest_excel(excels_dir: Path,
                      pattern: str,
                      ticker: str) -> Path | None:
    """按正则 pattern 扫 excels_dir，命中同 ticker 的多份文件，返回 date_time 最新的一份。"""
    regex = re.compile(pattern)
    matched: list[tuple[str, str, Path]] = []
    for f in excels_dir.glob("*.xlsx"):
        if f.name.startswith("~$"):  # Excel 打开时的临时锁文件
            continue
        m = regex.match(f.name)
        if not m:
            continue
        if m.group("ticker").upper() != ticker.upper():
            continue
        matched.append((m.group("date"), m.group("time"), f))
    if not matched:
        return None
    matched.sort(key=lambda x: (x[0], x[1]), reverse=True)
    return matched[0][2]


# ---------------------------------------------------------------------------
# openpyxl 惰性加载
# ---------------------------------------------------------------------------

def open_worksheet(xlsx_path: Path, sheet_name: str | None):
    """打开 workbook，取指定 sheet；None 时用 workbook.active。返回 (ws, workbook)。"""
    try:
        import openpyxl  # type: ignore
    except ImportError as e:
        raise RuntimeError(
            "需要 openpyxl：pip install openpyxl"
        ) from e
    wb = openpyxl.load_workbook(xlsx_path, data_only=True, read_only=True)
    ws = wb[sheet_name] if sheet_name else wb.active
    return ws, wb


# ---------------------------------------------------------------------------
# period 过滤器
# ---------------------------------------------------------------------------

def parse_period_filter(fiscal_years: str | None,
                        fiscal_periods: str | None,
                        periods: str | None,
                        period_regex: str) -> tuple[set[int] | None, set[str] | None, set[str] | None]:
    """
    组合 3 类过滤条件：
      - fiscal_years：'2022' 或 '2022-2025'（闭区间）；None=不限
      - fiscal_periods：'FY,Q3' 逗号分隔；None=不限
      - periods：'2024Q3,2025FY' 精确列表；None=不限
    返回 (year_set | None, period_type_set | None, exact_period_set | None)
    """
    years = None
    if fiscal_years:
        years = set()
        for part in fiscal_years.split(","):
            part = part.strip()
            if "-" in part:
                a, b = part.split("-", 1)
                for y in range(int(a), int(b) + 1):
                    years.add(y)
            elif part:
                years.add(int(part))

    period_types = None
    if fiscal_periods:
        period_types = {p.strip().upper() for p in fiscal_periods.split(",") if p.strip()}

    exact_periods = None
    if periods:
        exact_periods = {p.strip().upper() for p in periods.split(",") if p.strip()}

    return years, period_types, exact_periods


def period_passes(period_label: str,
                  years: set[int] | None,
                  period_types: set[str] | None,
                  exact_periods: set[str] | None,
                  period_regex: re.Pattern) -> bool:
    """判断某一列 period（如 '2024Q3'）是否满足三个过滤条件（都通过才算通过）。"""
    if exact_periods is not None and period_label.upper() not in exact_periods:
        return False
    m = period_regex.match(period_label.strip())
    if not m:
        # 非规范列名（例如 '财报日期' 会被上层预过滤，这里保险起见跳过）
        return False
    year = int(m.group("year"))
    ptype = m.group("type").upper()
    if years is not None and year not in years:
        return False
    if period_types is not None and ptype not in period_types:
        return False
    return True


# ---------------------------------------------------------------------------
# 单元格值规范化
# ---------------------------------------------------------------------------

def normalize_value(v: Any) -> Any:
    """把 excel 单元格转 JSON 友好类型：int/float 保留；'-' 视为 None；其它 str 原样。"""
    if v is None:
        return None
    if isinstance(v, str):
        s = v.strip()
        if s in ("-", "—", "–", "*", ""):
            return None
        try:
            return float(s) if "." in s else int(s)
        except ValueError:
            return s
    return v


# ---------------------------------------------------------------------------
# 输出
# ---------------------------------------------------------------------------

def emit(payload: dict[str, Any], pretty: bool) -> None:
    indent = 2 if pretty else None
    print(json.dumps(payload, ensure_ascii=False, indent=indent))


def fail(company: str, error: str, exit_code: int = 2) -> int:
    emit({"success": False, "company": company, "error": error}, pretty=False)
    return exit_code
