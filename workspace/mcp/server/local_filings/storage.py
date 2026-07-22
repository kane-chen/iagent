# -*- coding: utf-8 -*-
"""
文件发现与 ticker 规范化——集中管理文件查找，避免各 MCP 工具重复 glob 逻辑。
"""

import json
import re
import math
from numbers import Real
from pathlib import Path
from typing import Any, Optional

from .config import get_config

# ── 期间正则 ──────────────────────────────────────────────
PERIOD_RE = re.compile(r"^(?P<year>20\d{2})(?P<type>FY|Q[1-4]|H[12])$")

# ── Excel 文件名正则 ──────────────────────────────────────
EXCEL_RE = re.compile(
    r"^(?P<market>[A-Z]+)_(?P<ticker>[A-Z0-9]+)_"
    r"(?P<statement>income|balance|cashflow)_"
    r"(?P<date>\d{8})_(?P<time>\d{6})\.xlsx$"
)

# ── 报表类型排序辅助 ──────────────────────────────────────
_PERIOD_RANK = {"Q1": 1, "H1": 2, "Q2": 3, "Q3": 4, "H2": 5, "Q4": 6, "FY": 7}


def period_sort_key(label: str) -> tuple:
    """按 (年份, 期间排序值) 排序，用于倒序排列。"""
    m = PERIOD_RE.match(label)
    if not m:
        return (0, 0)
    year = int(m.group("year"))
    rank = _PERIOD_RANK.get(m.group("type"), 99)
    return (year, rank)


def period_is_quarterly(label: str) -> bool:
    m = PERIOD_RE.match(label)
    return m is not None and m.group("type") in ("Q1", "Q2", "Q3", "Q4", "H1", "H2")


def period_is_annual(label: str) -> bool:
    m = PERIOD_RE.match(label)
    return m is not None and m.group("type") == "FY"


# ── Ticker 规范化 ────────────────────────────────────────


def normalize_ticker(raw: str) -> str:
    """600519.SH → 600519_SH, US:AAPL → US_AAPL, 00700 → 00700"""
    return raw.replace(".", "_").replace(":", "_")


def split_ticker(raw: str) -> tuple[Optional[str], str]:
    """从 ticker 分离市场前缀和代码，返回 (market, symbol)。
    US_AAPL → ("US","AAPL"), 00700 → (None,"00700")
    """
    parts = normalize_ticker(raw).split("_", 1)
    if len(parts) == 2 and parts[0].isalpha() and len(parts[0]) <= 8:
        return (parts[0], parts[1])
    return (None, parts[0])


def validate_ticker(raw: str) -> str:
    """校验 ticker 不含路径穿越字符，返回规范化后的值。"""
    if "/" in raw or "\\" in raw or ".." in raw or raw.startswith("."):
        raise ValueError(f"Invalid ticker: {raw}")
    return normalize_ticker(raw)


# ── 财务 Excel 发现 ──────────────────────────────────────


def find_statement_excels(ticker: str) -> dict[str, Optional[Path]]:
    """在 workspace/excels 下递归查找指定 ticker 的三张报表，每张表返回最新版本。

    返回 {income: Path|None, balance: Path|None, cashflow: Path|None}
    """
    market, symbol = split_ticker(ticker)
    excels_dir = get_config().excels_dir
    if not excels_dir.is_dir():
        return {"income": None, "balance": None, "cashflow": None}

    candidates: dict[str, list[tuple[str, str, Path]]] = {
        "income": [],
        "balance": [],
        "cashflow": [],
    }

    for f in excels_dir.rglob("*.xlsx"):
        if f.name.startswith("~$"):
            continue
        m = EXCEL_RE.match(f.name)
        if not m:
            continue
        f_ticker = m.group("ticker")
        f_market = m.group("market")
        # 匹配：相同 symbol；market 可选匹配
        if f_ticker.upper() != symbol.upper():
            continue
        if market and f_market.upper() != market.upper():
            continue
        stmt = m.group("statement")
        if stmt in candidates:
            candidates[stmt].append((m.group("date"), m.group("time"), f))

    result: dict[str, Optional[Path]] = {}
    for stmt, items in candidates.items():
        if items:
            items.sort(key=lambda x: (x[0], x[1]), reverse=True)
            result[stmt] = items[0][2]
        else:
            result[stmt] = None
    return result


# ── 兼容旧格式 Financials 发现 ────────────────────────────


def find_legacy_financials(ticker: str) -> dict[str, list[Path]]:
    """在 <workspace>/financials 下查找 ticker 前缀的 CSV/XLSX。

    返回 {income: [...], balance: [...], cashflow: [...], indicators: [...]}
    """
    result: dict[str, list[Path]] = {
        "income": [],
        "balance": [],
        "cashflow": [],
        "indicators": [],
    }
    financials_dir = get_config().financials_dir
    if not financials_dir.is_dir():
        return result

    norm = normalize_ticker(ticker)
    patterns = [f"{norm}_*.csv", f"{norm}_*.xlsx"]
    files: list[Path] = []
    for pat in patterns:
        files.extend(financials_dir.glob(pat))

    for f in files:
        stem_lower = f.stem.lower()
        if "income" in stem_lower:
            result["income"].append(f)
        elif "balance" in stem_lower:
            result["balance"].append(f)
        elif "cashflow" in stem_lower or "cash_flow" in stem_lower:
            result["cashflow"].append(f)
        elif "indicator" in stem_lower:
            result["indicators"].append(f)
    return result


def find_legacy_consensus(ticker: str) -> Optional[Path]:
    """查找一致预期 CSV。优先 financials，再 fallback excels。"""
    norm = normalize_ticker(ticker)
    cfg = get_config()
    for base in (cfg.financials_dir, cfg.excels_dir):
        if not base.is_dir():
            continue
        path = base / f"{norm}_consensus.csv"
        if path.is_file():
            return path
    return None


# ── 财报文件发现 ──────────────────────────────────────────


def _parse_meta(path: Path) -> Optional[dict]:
    """安全读取并校验 meta.json。"""
    try:
        data = json.loads(path.read_text("utf-8"))
    except (json.JSONDecodeError, OSError):
        return None
    # 至少需要这些字段
    for key in ("documentId", "ticker", "formType", "fiscalYear", "reportDate",
                "filingDate", "primaryFile"):
        if key not in data:
            return None
    pf = data["primaryFile"]
    if not isinstance(pf, dict) or "name" not in pf:
        return None
    return data


def _resolve_primary(meta_dir: Path, meta: dict) -> Optional[Path]:
    """解析 primaryFile 路径，并校验不越界。"""
    pf = meta["primaryFile"]
    name = pf["name"]
    resolved = (meta_dir / name).resolve()
    # 确保不越出 meta_dir
    try:
        resolved.relative_to(meta_dir.resolve())
    except ValueError:
        return None
    if not resolved.is_file():
        return None
    return resolved


def _classify_form_type(form_type: str) -> str:
    """将 formType 映射为季度/年度分类。"""
    ft = form_type.upper().strip()
    if ft in ("FY",):
        return "annual"
    if ft in ("Q1", "Q2", "Q3", "Q4", "H1", "H2"):
        return "quarterly"
    # 其他未知类型按 quarterly 对待
    return "quarterly"


def find_filings_meta(ticker: str, filing_type: str = "all") -> list[dict]:
    """在 portfolio/<TICKER>/filings/ 下按 meta.json 发现财报。

    返回排序后的列表，每项：
      {meta: dict, primary_path: Path, filing_type: str, meta_path: Path}
    """
    _, symbol = split_ticker(ticker)
    portfolio_dir = get_config().portfolio_dir
    base = portfolio_dir / symbol / "filings"
    if not base.is_dir():
        return []

    results: list[dict] = []
    for meta_path in sorted(base.glob("*/meta.json")):
        meta = _parse_meta(meta_path)
        if meta is None:
            continue
        meta_ticker = str(meta.get("ticker", ""))
        if meta_ticker.upper() != symbol.upper():
            continue
        ft = _classify_form_type(meta.get("formType", ""))
        if filing_type != "all" and ft != filing_type:
            continue
        primary = _resolve_primary(meta_path.parent, meta)
        if primary is None:
            continue
        results.append({
            "meta": meta,
            "primary_path": primary,
            "filing_type": ft,
            "meta_path": meta_path,
        })

    # 按 reportDate 降序，其次 filingDate 降序，最后 documentId 降序
    results.sort(
        key=lambda r: (r["meta"].get("reportDate", ""),
                       r["meta"].get("filingDate", ""),
                       r["meta"].get("documentId", "")),
        reverse=True,
    )
    return results[:4]


def find_legacy_reports(ticker: str, filing_type: str = "all") -> list[Path]:
    """在 <workspace>/reports 下查找旧扁平 PDF 布局。"""
    reports_dir = get_config().reports_dir
    if not reports_dir.is_dir():
        return []
    norm = normalize_ticker(ticker)
    files = sorted(reports_dir.glob(f"{norm}_*.pdf"), key=lambda f: f.stem, reverse=True)

    if filing_type == "quarterly":
        files = [f for f in files if "Q" in f.stem]
    elif filing_type == "annual":
        files = [f for f in files if "annual" in f.stem.lower()]

    return files[:4]


# ── 数值工具 ──────────────────────────────────────────────


def safe_float(v: Any) -> Optional[float]:
    """将值转为 float 或 None（空/'-'/NaN/Inf）。"""
    if v is None:
        return None
    if isinstance(v, str):
        s = v.strip()
        if s in ("-", "—", "–", "*", "", "N/A", "n/a"):
            return None
        try:
            v = float(v)
        except (ValueError, TypeError):
            return None
    # 支持 Python 标准数字及 numpy.number
    try:
        num = float(v)
    except (ValueError, TypeError):
        return None
    if math.isnan(num) or math.isinf(num):
        return None
    return num


def is_json_safe(obj: Any) -> bool:
    """检查对象是否可被 json.dumps(..., allow_nan=False) 序列化。"""
    try:
        json.dumps(obj, allow_nan=False)
        return True
    except (ValueError, TypeError, OverflowError):
        return False