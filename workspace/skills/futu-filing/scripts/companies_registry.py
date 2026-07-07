#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
futu-filing 的 company 注册表。

职责：
1. 读取 workspace/skills/futu-filing/config/companies.json，以此为**权威数据源**。
2. 未命中时调 Futu 的 search-stock/predict 接口拿候选，按"美股>港股>A股"排序、
   剔除 ADR/ETF/杠杆等衍生品，构建满足 downloader 需要的 entry。
3. 新解析出的 entry 追加回 companies.json 持久化，下次直接命中。

对外只暴露 {@link CompaniesRegistry.resolve}，返回一个 dict（不是 dataclass），
让调用方直接用 downloader 现有的 CompanyEntry 语义读取字段。

Company entry 字段规则（按需求）：
- ticker             : 取 predict 接口的 stockSymbol
- stockId            : 取 predict 接口的 stockId
- displayName        : 取 predict 接口的 stockName
- market             : 依据 predict 接口的 marketTypeName —— US/HK/CN；其它 → ""
                       （需求原文按 marketType=0/1/2 分类，但真实 Futu predict 里
                        CN A 股 marketType=4 而非 0，故用稳定的 marketTypeName 字符串。）
- supportFileTypes   : market=US 且是中概股 → ["20-F","6-K"]
                       market=US 且非中概股 → ["10-K","10-Q"]
                       其它市场             → []
- titleKeyWords      : market=CN → ["季度报告","年度报告"]
                       market=HK → ["季度报告","年度报告","业绩公告","财报公告"]
                       其它市场   → []
- filingSuffixNames  : 默认 []（用户可再手工加）
- supportPeriodTypes : 默认 []（用户可再手工加）
- cik                : 无法从 predict 拿到，留空
"""
from __future__ import annotations

import json
import socket
import sys
import time
from pathlib import Path
from typing import Optional
import urllib.error
import urllib.parse
import urllib.request

# ---------------------------------------------------------------------------
# 常量
# ---------------------------------------------------------------------------

PREDICT_URL = "https://www.futunn.com/search-stock/predict"
DEFAULT_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
              "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36")

# 剔除词：候选 stockName 含以下任一即视为衍生品/ADR/ETF/杠杆产品，不选。
# 仅按名称过滤，因为 predict 接口没有单独的类型字段。
_DERIVATIVE_NAME_TOKENS = (
    "ETF", "etf", "ADR", "-R", "-ADS", "Trust",
    "做多", "做空", "杠杆", "反向", "两倍", "三倍", "二倍",
    "1x", "2x", "3x", "1X", "2X", "3X",
    "1倍", "2倍", "3倍",
    "Ecosystem",  # 概念股列表
    "指数", "基金",
)

# 中概股 stockSymbol 的判断：symbol 里包含以下任一 token 即视为中概股（用于 US 分支）。
# 与 StockInfoTool.CHINESE_ADR_TOKENS 对齐，避免规则漂移。
_CHINESE_ADR_TOKENS = (
    "BABA", "PDD", "JD", "BIDU", "NIO", "LI", "XPEV",
    "-ADR", "-ADS", ".US",
)

# marketTypeName（predict 接口稳定字符串字段）→ 内部 market ID。
#
# 用户需求原文按 marketType 数值区分（0=CN, 1=HK, 2=US），但真实 Futu predict 接口
# CN A 股返回的是 marketType=4/marketTypeName="CN"（0 号并未使用）。为了不被"数值语义
# 漂移"坑，这里改按 marketTypeName（"US"/"HK"/"CN"）来分类：既满足需求里对每种市场的
# 字段规则要求，又不会因 Futu 内部编号变化而失配。
_SUPPORTED_MARKETS = {"US", "HK", "CN"}

# 排序优先级：越小越优先。US=0, HK=1, CN=2，其它=99
_MARKET_RANK = {"US": 0, "HK": 1, "CN": 2}


def _market_name(quote: dict) -> str:
    """从 quote 提取 marketTypeName 并归一化大写。"""
    return (quote.get("marketTypeName") or "").strip().upper()


def _rank(market_name: str) -> int:
    return _MARKET_RANK.get(market_name, 99)


# ---------------------------------------------------------------------------
# HTTP 拉取
# ---------------------------------------------------------------------------

def _fetch_predict(keyword: str, timeout: int = 15, retries: int = 3) -> list[dict]:
    """调用 futunn 的 predict 接口拉候选列表；返回 quote 数组（顺序即接口原顺序）。

    网络瞬时错误自动重试（指数退避）：DNS 失败、连接重置、超时、5xx。
    """
    qs = urllib.parse.urlencode({"keyword": keyword})
    url = f"{PREDICT_URL}?{qs}"

    last_error: Optional[Exception] = None
    delay = 1.5
    for attempt in range(1, retries + 1):
        try:
            req = urllib.request.Request(url, headers={
                "User-Agent": DEFAULT_UA,
                "Accept": "application/json, text/plain, */*",
                "Referer": "https://www.futunn.com/",
            })
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                body = resp.read().decode("utf-8", errors="replace")
            payload = json.loads(body)
            if payload.get("code") not in (0, "0"):
                # 业务错误（非网络错误）不重试
                raise RuntimeError(f"predict API code={payload.get('code')} "
                                   f"message={payload.get('message')}")
            return payload.get("data", {}).get("quote", []) or []
        except (urllib.error.URLError, socket.timeout, TimeoutError,
                ConnectionError, OSError) as e:
            last_error = e
            if attempt < retries:
                print(f"[companies_registry] predict fetch attempt {attempt} failed "
                      f"({type(e).__name__}: {e}), retrying in {delay:.1f}s…",
                      file=sys.stderr)
                time.sleep(delay)
                delay *= 2
                continue
            print(f"[companies_registry] predict fetch failed after {retries} attempts: {e}",
                  file=sys.stderr)
            raise
        except json.JSONDecodeError as e:
            # JSON 解析失败通常是 WAF 页面或服务端异常，可重试一次
            last_error = e
            if attempt < retries:
                time.sleep(delay)
                delay *= 2
                continue
            raise
    # unreachable
    if last_error:
        raise last_error
    return []


def _is_derivative(name: str) -> bool:
    if not name:
        return False
    return any(tok in name for tok in _DERIVATIVE_NAME_TOKENS)


def _is_chinese_adr(symbol: str) -> bool:
    if not symbol:
        return False
    up = symbol.upper()
    return any(tok in up for tok in _CHINESE_ADR_TOKENS)


def _select_best_quote(quotes: list[dict], keyword: str) -> Optional[dict]:
    """从候选列表里挑一只：先过滤衍生品，再按 (输入 == symbol 优先, marketName rank) 排序。

    排序优先级（越靠前越优先）：
      1. `stockSymbol == keyword`（用户显式输入的 ticker 精确匹配）
      2. marketName rank（US > HK > CN；用户按名称模糊查询时才走到这里）
      3. predict 接口原顺序（稳定 tie-breaker）
    """
    if not quotes:
        return None
    kw_upper = (keyword or "").strip().upper()

    filtered: list[tuple[int, int, int, dict]] = []
    for idx, q in enumerate(quotes):
        name = q.get("stockName") or ""
        symbol = (q.get("stockSymbol") or "").strip()
        market_name = _market_name(q)
        # 只保留我们支持的三种市场；其它（AU/UK/JP…）跳过
        if market_name not in _SUPPORTED_MARKETS:
            continue
        if _is_derivative(name):
            continue
        symbol_match = 0 if symbol.upper() == kw_upper else 1
        # 精确匹配优先于市场偏好：keyword 是用户显式给的 ticker，若命中就应尊重
        filtered.append((symbol_match, _rank(market_name), idx, q))

    if not filtered:
        return None
    filtered.sort(key=lambda t: (t[0], t[1], t[2]))
    return filtered[0][3]


# ---------------------------------------------------------------------------
# entry 构建
# ---------------------------------------------------------------------------

def build_entry_from_quote(quote: dict) -> dict:
    """按需求把 predict 接口返回的一条 quote 组装成 companies.json 格式的 entry。"""
    symbol = (quote.get("stockSymbol") or "").strip()
    stock_id = str(quote.get("stockId") or "")
    stock_name = (quote.get("stockName") or "").strip()
    market_name = _market_name(quote)

    market = market_name if market_name in _SUPPORTED_MARKETS else ""

    if market == "US":
        support_file_types = ["20-F", "6-K"] if _is_chinese_adr(symbol) else ["10-K", "10-Q"]
    else:
        support_file_types = []

    if market == "CN":
        title_keywords = ["季度报告", "年度报告"]
    elif market == "HK":
        title_keywords = ["季度报告", "年度报告", "业绩公告", "财报公告"]
    else:
        title_keywords = []

    return {
        "ticker": symbol,
        "market": market,
        "stockId": stock_id,
        "displayName": stock_name,
        "supportFileTypes": support_file_types,
        "titleKeyWords": title_keywords,
        "filingSuffixNames": [],
        "supportPeriodTypes": [],
    }


# ---------------------------------------------------------------------------
# 持久化
# ---------------------------------------------------------------------------

def _append_to_config(config_path: Path, entry: dict) -> None:
    """把新 entry 追加进 companies.json 的 companies 数组；相同 ticker 的旧记录先删除。"""
    if not config_path.exists():
        # 极端场景：companies.json 缺失。此时仍写出一个最小可用的骨架，
        # 保持 downloader load_config 能读到 marketProfiles 等其它字段
        # —— 但我们不主动伪造 marketProfiles，让 downloader 自己报错更清晰。
        # 因此只在 config 已存在时才写入；不存在则直接抛，避免误创建残缺配置。
        raise RuntimeError(
            f"companies.json not found at {config_path}; refusing to auto-create "
            f"(it would miss marketProfiles/secEdgarUserAgent). Restore it first.")

    raw = json.loads(config_path.read_text(encoding="utf-8"))
    companies = raw.get("companies", []) or []
    ticker = entry["ticker"].upper()
    kept = [c for c in companies if (c.get("ticker") or "").upper() != ticker]
    kept.append(entry)
    raw["companies"] = kept
    config_path.write_text(
        json.dumps(raw, ensure_ascii=False, indent=2), encoding="utf-8")


# ---------------------------------------------------------------------------
# 对外入口
# ---------------------------------------------------------------------------

class CompaniesRegistry:
    """封装"读配置 → 若缺失则联网补齐并写回"的流程。"""

    def __init__(self, config_path: Path, *, allow_network: bool = True,
                 fetch: callable = None):
        """
        Args:
            config_path: companies.json 路径
            allow_network: 是否允许联网。测试里可传 False 强制只走配置
            fetch: 允许注入自定义 fetcher，签名 `fetch(keyword: str) -> list[dict]`；
                   默认走 `_fetch_predict`
        """
        self.config_path = config_path
        self.allow_network = allow_network
        self._fetch = fetch or _fetch_predict

    def resolve(self, ticker: str) -> Optional[dict]:
        """按 ticker 拿一个 entry；命中配置直接返回，未命中且允许联网时才补齐。

        返回 None：配置里没有 & 联网也拿不到（或联网被禁）。
        """
        if not ticker:
            return None
        t = ticker.strip().upper()

        # 1) 先读配置
        cfg_entry = self._find_in_config(t)
        if cfg_entry:
            return cfg_entry

        # 2) 不允许联网则放弃
        if not self.allow_network:
            return None

        # 3) 联网查
        try:
            quotes = self._fetch(t)
        except Exception as e:
            print(f"[companies_registry] predict fetch failed for {t}: {e}",
                  file=sys.stderr)
            return None

        best = _select_best_quote(quotes, t)
        if not best:
            print(f"[companies_registry] no suitable quote for {t}",
                  file=sys.stderr)
            return None

        entry = build_entry_from_quote(best)
        try:
            _append_to_config(self.config_path, entry)
            print(f"[companies_registry] added {entry['ticker']} "
                  f"({entry['market']}, stockId={entry['stockId']}) to "
                  f"companies.json", file=sys.stderr)
        except Exception as e:
            print(f"[companies_registry] failed to persist entry: {e}",
                  file=sys.stderr)
            # 即便持久化失败，也把 entry 返回，不影响本次下载
        return entry

    def _find_in_config(self, ticker_upper: str) -> Optional[dict]:
        if not self.config_path.exists():
            return None
        raw = json.loads(self.config_path.read_text(encoding="utf-8"))
        for c in raw.get("companies", []) or []:
            if (c.get("ticker") or "").upper() == ticker_upper:
                return c
        return None


# ---------------------------------------------------------------------------
# CLI（方便手动补齐 & 排查）
# ---------------------------------------------------------------------------

def _default_config_path() -> Path:
    return Path(__file__).resolve().parent.parent / "config" / "companies.json"


def main() -> int:
    import argparse
    parser = argparse.ArgumentParser(
        description="futu-filing companies.json 注册表：命中直接查配置，"
                    "未命中调 futunn predict 接口补齐并写回配置。")
    parser.add_argument("ticker", help="股票代码，如 00700 / BABA / PDD")
    parser.add_argument("--config", default=str(_default_config_path()),
                        help="companies.json 路径")
    parser.add_argument("--no-network", action="store_true",
                        help="仅查配置，不联网")
    parser.add_argument("--print-quotes", action="store_true",
                        help="调试用：打印 predict 接口全部候选")
    args = parser.parse_args()

    if args.print_quotes:
        try:
            for q in _fetch_predict(args.ticker):
                print(json.dumps(q, ensure_ascii=False))
        except Exception as e:
            print(f"predict failed: {e}", file=sys.stderr)
            return 1
        return 0

    registry = CompaniesRegistry(Path(args.config),
                                 allow_network=not args.no_network)
    entry = registry.resolve(args.ticker)
    if not entry:
        print(f"ERROR: cannot resolve {args.ticker}", file=sys.stderr)
        return 1
    print(json.dumps(entry, ensure_ascii=False, indent=2))
    # 让主脚本能感知到"是否是新增写入"，用返回码区分：
    #   0 = 命中配置或成功写入
    return 0


if __name__ == "__main__":
    sys.exit(main())
