# -*- coding: utf-8 -*-
"""
本地财务报表与财报文件 MCP Server。

默认从 <应用根目录>/workspace 读取；可通过 --directory 或环境变量
IAGENT_WORKSPACE_DIR 指定 workspace 根目录。
"""

import argparse
import json
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path
from typing import Optional

import pandas as pd
from mcp.server.fastmcp import FastMCP

# 兼容直接执行 server.py 和作为包导入两种方式
if __package__ in (None, ""):
    # 直接执行时，将 local_filings 的父目录加入 sys.path
    _HERE = Path(__file__).resolve().parent
    _PARENT = _HERE.parent
    if str(_PARENT) not in sys.path:
        sys.path.insert(0, str(_PARENT))
    from local_filings.config import WorkspaceConfig, get_config, resolve_and_set
    from local_filings.parsers.csv_parser import parse_financials_csv
    from local_filings.parsers.excel_parser import parse_financials_excel
    from local_filings.parsers.pdf_parser import extract_filing_tables, extract_pdf_tables
    from local_filings.schema import build_filings_response, build_historicals_response
    from local_filings.storage import (
        find_filings_meta,
        find_legacy_consensus,
        find_legacy_financials,
        find_legacy_reports,
        find_statement_excels,
        normalize_ticker,
        validate_ticker,
    )
else:
    from .config import WorkspaceConfig, get_config, resolve_and_set
    from .parsers.csv_parser import parse_financials_csv
    from .parsers.excel_parser import parse_financials_excel
    from .parsers.pdf_parser import extract_filing_tables, extract_pdf_tables
    from .schema import build_filings_response, build_historicals_response
    from .storage import (
        find_filings_meta,
        find_legacy_consensus,
        find_legacy_financials,
        find_legacy_reports,
        find_statement_excels,
        normalize_ticker,
        validate_ticker,
    )

mcp = FastMCP("local-filings")

VALID_PERIODS = {"quarterly", "annual"}
VALID_FILING_TYPES = {"all", "quarterly", "annual"}
VALID_TABLE_TYPES = {"all", "income", "balance", "cashflow", "segments"}

# lf_filings 结果缓存有效期
FILINGS_CACHE_TTL = timedelta(days=7)


def _error(message: str) -> dict:
    return {"error": message}


def _ensure_config() -> None:
    """确保配置已初始化。测试直接调用工具时若未配置则使用默认目录。"""
    try:
        get_config()
    except RuntimeError:
        resolve_and_set()


# ──────────────────────────────────────────
# Tool 1: 获取历史财务数据（三表）
# ──────────────────────────────────────────
@mcp.tool()
def lf_historicals(
    ticker: str,
    period: str = "quarterly",
    num_periods: int = 8,
) -> dict:
    """
    从本地 CSV/Excel 文件获取指定 ticker 的历史财务报表（三大表）。

    返回格式：
    { ticker, historicals: { income_statement, balance_sheet, cash_flow,
      financial_indicators }, consensus, source, extraction_note }
    """
    _ensure_config()

    try:
        validate_ticker(ticker)
    except ValueError as exc:
        return _error(str(exc))
    if period not in VALID_PERIODS:
        return _error(f"Invalid period: {period}. Must be quarterly or annual")
    if not isinstance(num_periods, int) or num_periods < 1 or num_periods > 100:
        return _error("num_periods must be an integer between 1 and 100")

    # 1. 新格式：workspace/excels/{MARKET}_{TICKER}_{statement}_{timestamp}.xlsx
    excel_files = find_statement_excels(ticker)
    income, balance, cashflow, indicators = parse_financials_excel(
        excel_files, period, num_periods
    )

    # 2. 兼容旧格式：workspace/financials/<ticker>_*.csv/xlsx
    # 仅在新格式对应报表为空时补缺
    legacy = find_legacy_financials(ticker)
    legacy_csvs = []
    for stmt_files in legacy.values():
        legacy_csvs.extend([f for f in stmt_files if f.suffix.lower() == ".csv"])
    if legacy_csvs:
        li, lb, lc, lind = parse_financials_csv(legacy_csvs, period, num_periods)
        if not income:
            income = li
        if not balance:
            balance = lb
        if not cashflow:
            cashflow = lc
        if not indicators:
            indicators = lind

    # 旧格式 XLSX：如果仍缺报表，尝试按生成式格式解析
    legacy_xlsx_map = {
        "income": legacy["income"][0] if legacy["income"] else None,
        "balance": legacy["balance"][0] if legacy["balance"] else None,
        "cashflow": legacy["cashflow"][0] if legacy["cashflow"] else None,
    }
    if any(legacy_xlsx_map.values()) and (not income or not balance or not cashflow):
        try:
            xi, xb, xc, xind = parse_financials_excel(legacy_xlsx_map, period, num_periods)
            if not income:
                income = xi
            if not balance:
                balance = xb
            if not cashflow:
                cashflow = xc
            if not indicators:
                indicators = xind
        except Exception:
            # 旧文件格式无法解析时不影响新格式结果
            pass

    if not any((income, balance, cashflow, indicators)):
        return _error(f"No local financial data found for {ticker}")

    return build_historicals_response(ticker, income, balance, cashflow, indicators)


# ──────────────────────────────────────────
# Tool 2: 获取一致预期 / 实际值
# ──────────────────────────────────────────
@mcp.tool()
def lf_consensus(ticker: str) -> dict:
    """
    获取本地实际值和一致预期。若本地不存在一致预期文件，consensus 为 null。
    """
    _ensure_config()
    try:
        validate_ticker(ticker)
    except ValueError as exc:
        return _error(str(exc))

    actuals = _extract_actuals(ticker)
    consensus = None
    consensus_file = find_legacy_consensus(ticker)
    if consensus_file is not None:
        try:
            df = pd.read_csv(consensus_file)
            df = df.where(pd.notna(df), None)
            consensus = df.to_dict(orient="records")
        except Exception:
            consensus = None

    return {
        "ticker": ticker,
        "historicals": actuals,
        "consensus": consensus,
    }


# ──────────────────────────────────────────
# Tool 3: 获取监管文件 / 财报
# ──────────────────────────────────────────
@mcp.tool()
def lf_filings(
    ticker: str,
    filing_type: str = "all",
) -> dict:
    """
    从本地 portfolio/<TICKER>/filings/<documentId>/meta.json 读取财报，
    支持 PDF 和 HTML 格式，并提取结构化表格。

    结果会缓存到 workspace/models/{ticker}_filings_yyyymmdd_timestamp.json，
    缓存有效期 7 天，命中缓存时直接返回，不会重新解析 PDF/HTML。
    """
    _ensure_config()
    try:
        validate_ticker(ticker)
    except ValueError as exc:
        return _error(str(exc))
    # ── filing_type 过滤逻辑已停用，一律按 "all" 返回全部财报 ──
    # if filing_type not in VALID_FILING_TYPES:
    #     return _error(
    #         f"Invalid filing_type: {filing_type}. Must be all, quarterly, or annual"
    #     )
    effective_filing_type = "all"

    # 命中 7 天内的缓存则直接返回
    cached = _load_filings_cache(ticker)
    if cached is not None:
        return cached

    # 优先使用 metadata-backed 新布局
    records = find_filings_meta(ticker, effective_filing_type)
    extracted = []
    for record in records:
        meta = record["meta"]
        primary = record["primary_path"]
        tables = extract_filing_tables(primary)
        extracted.append({
            "filename": primary.name,
            "filing_type": record["filing_type"],
            "tables": tables,
            # 增加可溯源元数据（不破坏原有字段）
            "document_id": meta.get("documentId"),
            "form_type": meta.get("formType"),
            "fiscal_year": meta.get("fiscalYear"),
            "report_date": meta.get("reportDate"),
            "filing_date": meta.get("filingDate"),
            "source_url": meta.get("sourceNoticeUrl") or meta.get("primaryFile", {}).get("sourceUrl"),
            "content_type": meta.get("primaryFile", {}).get("contentType"),
        })

    # 新布局无结果时兼容旧扁平 PDF 布局
    if not extracted:
        legacy_files = find_legacy_reports(ticker, effective_filing_type)
        for f in legacy_files:
            extracted.append({
                "filename": f.name,
                "filing_type": "quarterly" if "Q" in f.stem else "annual",
                "tables": extract_pdf_tables(str(f)),
            })

    if not extracted:
        return _error(f"No local reports found for {ticker}")

    response = build_filings_response(ticker, extracted)
    _store_filings_cache(ticker, response)
    return response


# ──────────────────────────────────────────
# Tool 4: 从 PDF 报告中提取结构化表格
# ──────────────────────────────────────────
@mcp.tool()
def lf_extract_table(
    pdf_path: str,
    table_type: str = "all",
) -> dict:
    """
    从指定本地 PDF 财报提取结构化表格。
    相对路径基于配置的 workspace 根目录解析。
    """
    _ensure_config()
    if table_type not in VALID_TABLE_TYPES:
        return _error(
            f"Invalid table_type: {table_type}. Must be all, income, balance, cashflow, or segments"
        )

    path = Path(pdf_path)
    if not path.is_absolute():
        path = get_config().workspace_root / path
    path = path.resolve()

    if not path.is_file():
        return _error(f"File not found: {pdf_path}")
    if path.suffix.lower() != ".pdf":
        return _error(f"Unsupported file type: {path.suffix}. Only PDF is supported")

    try:
        tables = extract_pdf_tables(str(path), table_type=table_type)
    except Exception as exc:
        return _error(f"Failed to extract PDF tables: {exc}")

    return {
        "source": pdf_path,
        "tables": tables,
        "extraction_note": "Data extracted from local PDF. Mark [UNSOURCED] if cross-referencing fails.",
    }


# ──────────────────────────────────────────
# Tool 5: 数据完整性校验
# ──────────────────────────────────────────
@mcp.tool()
def lf_validate(ticker: str) -> dict:
    """
    校验本地财务数据完整性：三张报表覆盖情况和可用财报数量。
    """
    _ensure_config()
    try:
        validate_ticker(ticker)
    except ValueError as exc:
        return _error(str(exc))

    excel_files = find_statement_excels(ticker)
    legacy = find_legacy_financials(ticker)

    has_income = excel_files.get("income") is not None or bool(legacy["income"])
    has_balance = excel_files.get("balance") is not None or bool(legacy["balance"])
    has_cashflow = excel_files.get("cashflow") is not None or bool(legacy["cashflow"])

    coverage_count = sum((has_income, has_balance, has_cashflow))
    if coverage_count == 3:
        quality = "complete"
    elif coverage_count == 2:
        quality = "acceptable"
    else:
        quality = "incomplete"

    filings = find_filings_meta(ticker, "all")
    if filings:
        report_count = len(filings)
    else:
        report_count = len(find_legacy_reports(ticker, "all"))

    return {
        "ticker": ticker,
        "has_income_statement": has_income,
        "has_balance_sheet": has_balance,
        "has_cash_flow": has_cashflow,
        "report_count": report_count,
        "data_quality": quality,
    }


# ──────────────────────────────────────────
# 辅助函数
# ──────────────────────────────────────────

def _normalize_ticker(ticker: str) -> str:
    """600519.SH → 600519_SH, AAPL → AAPL"""
    return normalize_ticker(ticker)


# ── lf_filings 缓存：workspace/models/{ticker}_filings_yyyymmdd_timestamp.json ──

def _filings_cache_dir() -> Path:
    """返回缓存目录 workspace/models，若不存在会自动创建。"""
    directory = get_config().workspace_root / "models"
    directory.mkdir(parents=True, exist_ok=True)
    return directory


def _filings_cache_prefix(ticker: str) -> str:
    """构造缓存文件前缀，使用规范化后的 ticker。"""
    return f"{normalize_ticker(ticker)}_filings_"


def _load_filings_cache(ticker: str) -> Optional[dict]:
    """尝试加载 7 天内最新的缓存文件，返回反序列化结果或 None。"""
    directory = _filings_cache_dir()
    prefix = _filings_cache_prefix(ticker)
    candidates = sorted(
        directory.glob(f"{prefix}*.json"),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    if not candidates:
        return None

    now = datetime.now()
    ttl = FILINGS_CACHE_TTL
    for candidate in candidates:
        mtime = datetime.fromtimestamp(candidate.stat().st_mtime)
        if now - mtime > ttl:
            continue
        try:
            with candidate.open("r", encoding="utf-8") as handle:
                return json.load(handle)
        except (OSError, json.JSONDecodeError):
            # 缓存损坏时忽略，继续找下一份或走正常解析路径
            continue
    return None


def _store_filings_cache(ticker: str, payload: dict) -> Optional[Path]:
    """将 lf_filings 结果写入缓存，失败时返回 None，不阻塞主流程。"""
    try:
        directory = _filings_cache_dir()
        now = datetime.now()
        filename = (
            f"{_filings_cache_prefix(ticker)}"
            f"{now.strftime('%Y%m%d')}_{int(time.time())}.json"
        )
        cache_path = directory / filename
        with cache_path.open("w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
        return cache_path
    except OSError:
        return None


def _extract_actuals(ticker: str) -> dict:
    """从本地三表文件提取 reported actuals。"""
    hist = lf_historicals(ticker)
    if "error" in hist:
        return {}
    return hist.get("historicals", {})


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="本地财务报表与财报文件 MCP Server",
    )
    parser.add_argument(
        "--directory",
        default=None,
        help=(
            "workspace 根目录（包含 excels/、portfolio/）；"
            "未传时使用 IAGENT_WORKSPACE_DIR，仍未设置则默认为 <应用根目录>/workspace"
        ),
    )
    return parser


def main(argv: Optional[list[str]] = None) -> int:
    """初始化 workspace 配置并以 stdio transport 启动 MCP Server。"""
    parser = build_arg_parser()
    args = parser.parse_args(argv)
    try:
        resolve_and_set(args.directory)
    except (NotADirectoryError, OSError) as exc:
        print(f"local-filings startup error: {exc}", file=sys.stderr)
        return 2

    mcp.run()
    return 0


if __name__ == "__main__":
    sys.exit(main())
