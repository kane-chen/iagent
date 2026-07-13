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
import os
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
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
DEFAULT_CACHE_FILE = SKILL_DIR / "cache" / "ticker_cache.json"

# 缓存条目最长有效期（秒）；用户可通过 --cache-ttl-days 调整
DEFAULT_CACHE_TTL_SECONDS = 30 * 24 * 3600


def load_config(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


# ---------------------------------------------------------------------------
# 本地缓存：按公司名归档 normalize 后的候选列表
# 结构：
#   {
#       "<company_key>": {
#           "company": "<原始公司名>",
#           "timestamp": <epoch秒>,
#           "results": [ <normalize_raw 后但未 fill_profile/score 的 dict>, ... ]
#       },
#       ...
#   }
# 缓存的是"最小可复用形态"——保留 exchange/exchangeName/securityType 等原始字段，
# 交由主流程根据当前 --preferred-exchanges / --limit / 最新 config 重新排序打分。
# ---------------------------------------------------------------------------

def _cache_key(company: str) -> str:
    """归一化 company 为缓存 key：去空白 + 小写。"""
    return (company or "").strip().lower()


def load_cache(cache_file: Path) -> dict[str, Any]:
    if not cache_file.is_file():
        return {}
    try:
        with cache_file.open("r", encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except (OSError, json.JSONDecodeError):
        # 缓存损坏时不影响主流程，返回空 dict 让后续覆盖
        return {}


def save_cache(cache_file: Path, cache: dict[str, Any]) -> None:
    """写缓存文件；用同目录临时文件 + 原子 rename 避免半写。"""
    cache_file.parent.mkdir(parents=True, exist_ok=True)
    tmp = cache_file.with_suffix(cache_file.suffix + ".tmp")
    with tmp.open("w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False, indent=2)
    os.replace(tmp, cache_file)


def get_cached_normalized(cache: dict[str, Any], company: str,
                          ttl_seconds: int) -> list[dict[str, Any]] | None:
    """读取一条缓存的 normalized 候选列表；过期返回 None。"""
    entry = cache.get(_cache_key(company))
    if not isinstance(entry, dict):
        return None
    ts = entry.get("timestamp")
    if not isinstance(ts, (int, float)):
        return None
    if ttl_seconds > 0 and time.time() - ts > ttl_seconds:
        return None
    results = entry.get("results")
    if not isinstance(results, list):
        return None
    # 返回 deep copy，避免调用方回写脏数据到缓存
    return [dict(r) for r in results if isinstance(r, dict)]


def put_cached_normalized(cache: dict[str, Any], company: str,
                          normalized: list[dict[str, Any]]) -> None:
    """把 normalized 结果写入 in-memory 缓存 dict（未落盘）。"""
    cache[_cache_key(company)] = {
        "company": company,
        "timestamp": int(time.time()),
        "timestampIso": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "results": [dict(r) for r in normalized],
    }


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
                  cfg: dict[str, Any],
                  cache_file: Path | None = None,
                  cache_ttl_seconds: int = DEFAULT_CACHE_TTL_SECONDS,
                  force_refresh: bool = False) -> tuple[list[dict[str, Any]], bool]:
    """按公司名查股票信息；先查本地缓存，未命中再调接口并追加回写。

    返回 (results, cache_hit)。cache_file=None 时完全跳过缓存读写。
    """
    cache: dict[str, Any] = {}
    cache_hit = False
    normalized: list[dict[str, Any]] | None = None

    # 1) 优先读缓存
    if cache_file is not None:
        cache = load_cache(cache_file)
        if not force_refresh:
            normalized = get_cached_normalized(cache, company_name, cache_ttl_seconds)
            if normalized is not None:
                cache_hit = True

    # 2) 缓存未命中 → 调东方财富接口
    if normalized is None:
        raw_list = do_search(company_name, cfg)
        normalized = []
        for raw in raw_list:
            info = normalize_raw(raw, cfg)
            if info is not None:
                normalized.append(info)

        # 3) 只要跑过一次接口（无论有无结果）都追加/更新到缓存文件
        if cache_file is not None:
            put_cached_normalized(cache, company_name, normalized)
            try:
                save_cache(cache_file, cache)
            except OSError as e:
                # 写缓存失败不应阻断主流程，仅告警到 stderr
                print(f"[stock-ticker] 缓存写入失败（忽略）: {e}", file=sys.stderr)

    if not normalized:
        return [], cache_hit

    # 4) 打分 + 偏好排序 + 截取 + 补 profile
    #    这些步骤依赖当前 config 与 CLI 参数，不缓存中间结果。
    scored = [dict(x) for x in normalized]  # 避免污染 cache 中的对象
    score_by_rank(scored, cfg)

    preferred = preferred_exchanges or cfg["preferredExchanges"]["default"]
    sort_by_preferred(scored, preferred)

    actual_limit = min(limit, len(scored))
    final_results: list[dict[str, Any]] = []
    for info in scored[:actual_limit]:
        fill_stock_detail(info, cfg)
        final_results.append(info)
    return final_results, cache_hit


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
    parser.add_argument(
        "--cache-file",
        type=Path,
        default=DEFAULT_CACHE_FILE,
        help=f"本地缓存文件路径，默认 {DEFAULT_CACHE_FILE.relative_to(SKILL_DIR)}",
    )
    parser.add_argument(
        "--cache-ttl-days",
        type=int,
        default=DEFAULT_CACHE_TTL_SECONDS // 86400,
        help="缓存有效期（天），过期视为未命中；<=0 表示永不过期",
    )
    parser.add_argument(
        "--no-cache",
        action="store_true",
        help="完全禁用本地缓存（不读也不写）",
    )
    parser.add_argument(
        "--force-refresh",
        action="store_true",
        help="强制调用接口并回写缓存（跳过读缓存，但仍会写）",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    cfg = load_config(args.config)

    preferred = [x.strip() for x in args.preferred_exchanges.split(",") if x.strip()]

    cache_file: Path | None = None if args.no_cache else args.cache_file
    ttl_seconds = args.cache_ttl_days * 86400 if args.cache_ttl_days > 0 else 0

    try:
        results, cache_hit = search_ticker(
            args.company,
            args.limit,
            preferred,
            cfg,
            cache_file=cache_file,
            cache_ttl_seconds=ttl_seconds,
            force_refresh=args.force_refresh,
        )
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
        "cacheHit": cache_hit,
        "results": results,
    }
    indent = 2 if args.pretty else None
    print(json.dumps(output, ensure_ascii=False, indent=indent))
    return 0


if __name__ == "__main__":
    sys.exit(main())
