#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
按公司名从东方财富搜索接口查询股票代码，并补齐市场归属与财报类型信息。

数据流：
  1. 载入 config/stock-search.json —— 所有映射、过滤词、profile 表都在里面
  2. 请求东方财富 suggest 接口，抓 QuotationCodeTable.Data
  3. 按 SecurityTypeName / DERIVATIVE_KEYWORDS 过滤非股票 & 衍生品
  4. MktNum → 交易所简称 → 大区（CN/HK/US）→ profile（含 US 中概股分支）
  5. 按 --preferred-exchanges 稳定排序 → 截取 --limit 条 → 打分 → 输出 JSON

用法示例：
  python search_ticker.py --company "阿里巴巴" --limit 3
  python search_ticker.py --company "腾讯" --preferred-exchanges HKG,NASDAQ
  python search_ticker.py --company "格力电器"                # 默认 limit=1
  python search_ticker.py --company "BABA" --pretty          # 缩进输出

原实现：io.invest.iagent.tools.StockInfoTool#searchTicker。
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


# Windows 默认 stdout 编码可能不是 UTF-8，导致中文输出乱码或重定向落盘破损。
# 显式重配 stdout/stderr 为 UTF-8，让脚本在 Windows / Linux / macOS 上表现一致。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except AttributeError:  # 老 Python：< 3.7 无 reconfigure，跳过
    pass


# ---------------------------------------------------------------------------
# 路径与配置
# ---------------------------------------------------------------------------

SCRIPT_DIR = Path(__file__).parent
SKILL_DIR = SCRIPT_DIR.parent
DEFAULT_CONFIG = SKILL_DIR / "config" / "stock-search.json"


def load_config(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


# ---------------------------------------------------------------------------
# 搜索
# ---------------------------------------------------------------------------

def http_get_json(url: str, headers: dict[str, str], timeout: int) -> dict[str, Any]:
    request = urllib.request.Request(url, headers=headers, method="GET")
    with urllib.request.urlopen(request, timeout=timeout) as resp:
        if resp.status != 200:
            raise RuntimeError(f"Search API returned HTTP {resp.status}")
        body = resp.read().decode("utf-8", errors="replace")
    return json.loads(body)


def do_search(company_name: str, cfg: dict[str, Any]) -> list[dict[str, Any]]:
    """调东方财富 suggest 接口，返回原始（未过滤未打分）候选。"""
    s = cfg["search"]
    query = urllib.parse.urlencode({
        "input": company_name,
        "type": s["type"],
        "token": s["token"],
        "count": s["defaultSearchLimit"],
    })
    url = f'{s["url"]}?{query}'
    headers = {
        "User-Agent": s["userAgent"],
        "Referer": s["referer"],
    }
    payload = http_get_json(url, headers, s["requestTimeoutSeconds"])

    data_list = (payload.get("QuotationCodeTable") or {}).get("Data") or []
    return list(data_list)


def is_derivative(name: str, derivative_keywords: list[str]) -> bool:
    if not name:
        return False
    return any(token in name for token in derivative_keywords)


def is_chinese_adr(symbol: str, adr_tokens: list[str]) -> bool:
    if not symbol:
        return False
    return any(token in symbol for token in adr_tokens)


def resolve_exchange_group(exchange_name: str, groups: dict[str, list[str]]) -> str | None:
    """返回 exchange_name 归属的大区 key（CN/HK/US），未匹配返回 None。"""
    for group_key, names in groups.items():
        if group_key.startswith("_"):
            continue
        if exchange_name in names:
            return group_key
    return None


def apply_profile(info: dict[str, Any], profile: dict[str, str]) -> None:
    for key, value in profile.items():
        if key.startswith("_"):
            continue
        info[key] = value


def fill_stock_detail(info: dict[str, Any], cfg: dict[str, Any]) -> None:
    """把交易所名字翻成 marketRegion / companyType / 财报类型 / 监管机构。"""
    exchange_name = info.get("exchangeName") or ""
    group = resolve_exchange_group(exchange_name, cfg["exchangeGroups"])
    profiles = cfg["profiles"]

    if group == "CN":
        apply_profile(info, profiles["CN"])
    elif group == "HK":
        apply_profile(info, profiles["HK"])
    elif group == "US":
        adr_tokens = cfg["filters"]["chineseAdrTokens"]
        profile_key = "US_ADR" if is_chinese_adr(info.get("symbol", ""), adr_tokens) else "US_DOMESTIC"
        apply_profile(info, profiles[profile_key])
    else:
        apply_profile(info, profiles["OTHER"])


def normalize_raw(raw: dict[str, Any], cfg: dict[str, Any]) -> dict[str, Any] | None:
    """把东方财富返回的一条原始记录转成 StockInfo；不符合过滤规则时返回 None。"""
    security_type = raw.get("SecurityTypeName") or ""
    name = raw.get("Name") or ""
    filters = cfg["filters"]

    if not any(token in security_type for token in filters["securityTypeTokens"]):
        return None
    if is_derivative(name, filters["derivativeKeywords"]):
        return None

    try:
        market_code = int(raw.get("MktNum") or 0)
    except (ValueError, TypeError):
        market_code = 0
    exchange_name = cfg["marketMap"].get(str(market_code), "OTHER")

    return {
        "symbol": raw.get("Code") or "",
        "name": name,
        "exchange": market_code,
        "exchangeName": exchange_name,
        "marketRegion": None,
        "securityType": security_type,
        "companyType": None,
        "annualReportType": None,
        "quarterlyReportType": None,
        "semiAnnualReportType": None,
        "filingAuthority": None,
        "matchScore": 1.0,
    }


def sort_by_preferred(results: list[dict[str, Any]], preferred: list[str]) -> None:
    """稳定排序：preferred 中位置越靠前的越前；不在列表里的按原顺序追加。"""
    def key(info: dict[str, Any]) -> int:
        try:
            return preferred.index(info.get("exchangeName"))
        except ValueError:
            return sys.maxsize
    results.sort(key=key)


def score_by_rank(results: list[dict[str, Any]], cfg: dict[str, Any]) -> None:
    """匹配分随位次递减，保留原 Java 语义。"""
    s = cfg["scoring"]
    base = s["baseScore"]
    decay = s["decayPerRank"]
    digits = s["roundDigits"]
    for i, info in enumerate(results):
        score = round(base - i * decay, digits)
        info["matchScore"] = score


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------

def search_ticker(company_name: str,
                  limit: int,
                  preferred_exchanges: list[str],
                  cfg: dict[str, Any]) -> list[dict[str, Any]]:
    raw_list = do_search(company_name, cfg)

    # 过滤 & 规范化
    normalized: list[dict[str, Any]] = []
    for raw in raw_list:
        info = normalize_raw(raw, cfg)
        if info is not None:
            normalized.append(info)

    if not normalized:
        return []

    score_by_rank(normalized, cfg)

    # 按偏好排序
    preferred = preferred_exchanges or cfg["preferredExchanges"]["default"]
    sort_by_preferred(normalized, preferred)

    # 截取 + 填 profile
    actual_limit = min(limit, len(normalized))
    final_results: list[dict[str, Any]] = []
    for info in normalized[:actual_limit]:
        fill_stock_detail(info, cfg)
        final_results.append(info)
    return final_results


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="按公司名查股票代码（东方财富数据源）；输出 JSON 到 stdout。",
    )
    parser.add_argument("--company", required=True, help="公司名，例如 阿里巴巴 / 腾讯 / BABA")
    parser.add_argument("--limit", type=int, default=1, help="返回记录数，默认 1")
    parser.add_argument(
        "--preferred-exchanges",
        default="",
        help="优先交易所，逗号分隔，例如 NASDAQ,HKG。留空则用 config 里的默认列表",
    )
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG, help="配置文件路径")
    parser.add_argument("--pretty", action="store_true", help="缩进输出 JSON")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    cfg = load_config(args.config)

    preferred = [x.strip() for x in args.preferred_exchanges.split(",") if x.strip()]

    try:
        results = search_ticker(args.company, args.limit, preferred, cfg)
    except Exception as e:  # noqa: BLE001
        print(json.dumps({
            "success": False,
            "error": f"{e.__class__.__name__}: {e}",
            "company": args.company,
        }, ensure_ascii=False), file=sys.stdout)
        return 2

    output = {
        "success": True,
        "company": args.company,
        "count": len(results),
        "results": results,
    }
    indent = 2 if args.pretty else None
    print(json.dumps(output, ensure_ascii=False, indent=indent))
    return 0


if __name__ == "__main__":
    sys.exit(main())
