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
import os
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
# 注意：SKILL_DIR.parent.parent 只在 skill 位于 workspace/skills/<name>/ 时才等于 workspace/。
# 但在 subagent 场景，skill 会被缓存到 workspace/agents/<agent>/workspace/.skills-cache/... 下，
# 这时 SKILL_DIR.parent.parent 指向的是 .skills-cache 而非真正的 workspace。
# 因此下面统一用 resolve_workspace() 做健壮推断，不要再直接用 WORKSPACE_DIR 拼相对路径。
WORKSPACE_DIR = SKILL_DIR.parent.parent            # workspace/（可能位于 .skills-cache 下，仅作 fallback）
DEFAULT_CONFIG = SKILL_DIR / "config" / "excel-format.json"


def resolve_workspace(cli_workspace: str | Path | None = None) -> Path:
    """解析真正的 workspace 根目录（含 excels/ portfolio/ 等业务子目录）。

    优先级：
      1. 显式传入的 CLI 参数
      2. 环境变量 IAGENT_WORKSPACE_DIR（Java 侧或运行脚本时设置）
      3. 从脚本位置向上回溯，寻找同时包含 excels/ 或 portfolio/ 的 workspace 目录
         —— 兼容 subagent 场景下 skill 被缓存到 .skills-cache/ 的路径错位问题
      4. fallback 到 SKILL_DIR.parent.parent（原始默认值，最稳但可能落到 .skills-cache）
    """
    if cli_workspace:
        return Path(cli_workspace).resolve()
    env_ws = os.environ.get("IAGENT_WORKSPACE_DIR")
    if env_ws:
        return Path(env_ws).resolve()

    # 从 SCRIPT_DIR 向上回溯，找一个名字叫 workspace 且不在 .skills-cache 下
    # 且里面有 excels/ 或 portfolio/ 的目录（业务约定的 workspace 布局特征）
    candidates: list[Path] = []
    cursor = SCRIPT_DIR.resolve()
    for _ in range(10):
        parent = cursor.parent
        if parent == cursor:
            break
        # 名字是 workspace 且路径中不含 .skills-cache
        if parent.name == "workspace" and ".skills-cache" not in parent.parts:
            candidates.append(parent)
        cursor = parent

    for c in candidates:
        if (c / "excels").is_dir() or (c / "portfolio").is_dir():
            return c

    # 至少返回一个名叫 workspace 的目录（可能刚创建、里面还是空的）
    if candidates:
        return candidates[0]

    # 极端 fallback：脚本位于 workspace/skills/<name>/scripts/ 时旧行为
    return SKILL_DIR.parent.parent


def load_config(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def resolve_excels_dir(cfg: dict[str, Any],
                       cli_excels_dir: str | Path | None = None,
                       cli_workspace: str | Path | None = None) -> Path:
    """把 config['excelsDir']（可能是 'workspace/excels' 或 'excels'）解析成绝对路径。

    - 若 CLI 显式传了 --excels-dir，直接使用（相对路径按当前 CWD 解析）。
    - 否则以 resolve_workspace() 为基础，去掉 'workspace/' 前缀后拼接。
    """
    if cli_excels_dir:
        p = Path(cli_excels_dir)
        return p.resolve() if p.is_absolute() else p.resolve()

    ws = resolve_workspace(cli_workspace)
    raw = str(cfg.get("excelsDir", "excels"))
    # 去掉可能的 "workspace/" 前缀，因为 ws 本身已指向 workspace 根
    if raw.startswith("workspace/") or raw.startswith("workspace\\"):
        raw = raw.split("/", 1)[-1] if "/" in raw else raw.split("\\", 1)[-1]
    return (ws / raw).resolve()


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
