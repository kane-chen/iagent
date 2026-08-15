#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
industry-macro-data skill 主脚本
数据源：东方财富 datacenter (https://datacenter-web.eastmoney.com/api/data/v1/get)
只依赖 Python 标准库。
"""

import argparse
import ast
import json
import logging
import os
import re
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone, timedelta
from typing import Any, Dict, List, Optional, Tuple

# ── 基础配置 ──────────────────────────────────────────────────────────
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
SKILL_DIR = os.path.dirname(SCRIPT_DIR)
DEFAULT_CONFIG = os.path.join(SKILL_DIR, "config", "industry-mapping.json")
DEFAULT_CACHE = os.path.join(SKILL_DIR, "cache", "em_cache.json")

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    stream=sys.stderr,
)
logger = logging.getLogger("industry-macro-data")


# ── 工具 ──────────────────────────────────────────────────────────────
def _load_json(path: str) -> Dict[str, Any]:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def _atomic_write_json(path: str, data: Dict[str, Any]) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def _now_iso() -> str:
    tz = timezone(timedelta(hours=8))
    return datetime.now(tz).isoformat(timespec="seconds")


def _die(msg: str, hint: str = "", code: int = 2) -> None:
    print(json.dumps({"success": False, "error": msg, "hint": hint}, ensure_ascii=False))
    sys.exit(code)


# ── 期次归一 ──────────────────────────────────────────────────────────
_MONTH_RE = re.compile(r"^(\d{4})-(\d{2})-\d{2}")


def _report_date_to_period(report_date: str, freq: str) -> Optional[str]:
    """REPORT_DATE (形如 '2026-06-01 00:00:00') → 期次标签。
    - month: 2026-06
    - quarter: 2026Q2（仅接受 3/6/9/12 月的行；其它月份返回 None，由上层过滤）
    - year: 2026（仅接受 12 月的行）
    对于 quarter/year 频次的 EM 报表（如 GDP 已经按季度返回），照原样映射。
    """
    m = _MONTH_RE.match(report_date or "")
    if not m:
        return None
    year, month = int(m.group(1)), int(m.group(2))
    if freq == "month":
        return f"{year:04d}-{month:02d}"
    if freq == "quarter":
        if month not in (3, 6, 9, 12):
            # 允许 EM 已经按季度返回的报表（GDP：TIME 会包含"第x季度"），此时任意月份都接受，取最近季度标签。
            # 用月份就近映射：<=3 → Q1；<=6 → Q2；<=9 → Q3；否则 Q4。
            q = (month + 2) // 3
            return f"{year:04d}Q{q}"
        q = month // 3
        return f"{year:04d}Q{q}"
    if freq == "year":
        return f"{year:04d}"
    return None


def _period_step(freq: str) -> int:
    return {"year": 1, "quarter": 4, "month": 12}[freq]


def _prev_period(period: str, freq: str) -> Optional[str]:
    """给定 period 与频次，返回同频次上溯 1 个 YoY 周期的 period（月 → 12 月前；季 → 4 季前；年 → 1 年前）。"""
    try:
        if freq == "year":
            return f"{int(period) - 1:04d}"
        if freq == "quarter":
            m = re.fullmatch(r"(\d{4})Q([1-4])", period)
            if not m:
                return None
            y, q = int(m.group(1)), int(m.group(2))
            return f"{y - 1:04d}Q{q}"
        if freq == "month":
            m = re.fullmatch(r"(\d{4})-(\d{2})", period)
            if not m:
                return None
            y, mm = int(m.group(1)), int(m.group(2))
            return f"{y - 1:04d}-{mm:02d}"
    except Exception:  # noqa: BLE001
        return None
    return None


# ── 安全公式求值（保留：与旧脚本一致，供派生指标复用） ────────────────
class _FormulaEvaluator(ast.NodeVisitor):
    _ALLOWED = (ast.Expression, ast.BinOp, ast.UnaryOp, ast.Add, ast.Sub, ast.Mult,
                ast.Div, ast.USub, ast.UAdd, ast.Constant, ast.Name, ast.Load)

    def __init__(self, env: Dict[str, float]):
        self.env = env

    def visit(self, node):
        if not isinstance(node, self._ALLOWED):
            raise ValueError(f"公式不允许 AST 节点 {type(node).__name__}")
        return super().visit(node)

    def visit_Expression(self, node):
        return self.visit(node.body)

    def visit_BinOp(self, node):
        left = self.visit(node.left)
        right = self.visit(node.right)
        if isinstance(node.op, ast.Add):
            return left + right
        if isinstance(node.op, ast.Sub):
            return left - right
        if isinstance(node.op, ast.Mult):
            return left * right
        if isinstance(node.op, ast.Div):
            if right == 0:
                raise ZeroDivisionError("除零")
            return left / right
        raise ValueError(f"不支持的运算符 {type(node.op).__name__}")

    def visit_UnaryOp(self, node):
        v = self.visit(node.operand)
        if isinstance(node.op, ast.USub):
            return -v
        if isinstance(node.op, ast.UAdd):
            return +v
        raise ValueError("不支持的一元运算")

    def visit_Constant(self, node):
        if isinstance(node.value, (int, float)):
            return node.value
        raise ValueError("公式仅支持数值常量")

    def visit_Name(self, node):
        if node.id not in self.env:
            raise KeyError(node.id)
        return self.env[node.id]


def _eval_formula(formula: str, env: Dict[str, float]) -> float:
    tree = ast.parse(formula, mode="eval")
    return _FormulaEvaluator(env).visit(tree)


# ── 东方财富 HTTP 请求 ─────────────────────────────────────────────────
def _build_url(base: str, params: Dict[str, str]) -> str:
    return f"{base}?{urllib.parse.urlencode(params)}"


def _http_get(url: str, headers: Dict[str, str], timeout: int) -> Tuple[int, bytes]:
    req = urllib.request.Request(url, headers=headers, method="GET")
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
        return resp.getcode(), resp.read()


def _query_em_report(report_name: str, columns: str, page_size: int, page_number: int,
                     endpoint_cfg: Dict[str, Any], timeout: int,
                     extra_params: Optional[Dict[str, str]] = None) -> Tuple[Dict[str, Any], str]:
    base = endpoint_cfg["baseUrl"]
    ua = endpoint_cfg.get("userAgent", "Mozilla/5.0")
    referer = endpoint_cfg.get("referer", "https://data.eastmoney.com/")
    retries = int(endpoint_cfg.get("retries", 3))
    backoffs = endpoint_cfg.get("backoffSeconds", [1, 2, 4])

    params = {
        "sortColumns": "REPORT_DATE",
        "sortTypes": "-1",
        "pageSize": str(page_size),
        "pageNumber": str(page_number),
        "reportName": report_name,
        "columns": columns,
        "source": "WEB",
        "client": "WEB",
    }
    if extra_params:
        params.update(extra_params)
    url = _build_url(base, params)
    headers = {"User-Agent": ua, "Referer": referer, "Accept": "application/json, text/plain, */*"}

    last_err = None
    for attempt in range(retries):
        try:
            code, body = _http_get(url, headers, timeout)
            if code == 200:
                try:
                    data = json.loads(body.decode("utf-8"))
                except Exception as e:  # noqa: BLE001
                    raise RuntimeError(f"响应非 JSON: {e}") from e
                if not data.get("success"):
                    raise RuntimeError(f"EM 报表错误 code={data.get('code')} msg={data.get('message')}")
                return data, url
            last_err = f"HTTP {code}"
        except urllib.error.HTTPError as e:
            last_err = f"HTTPError {e.code} {e.reason}"
        except urllib.error.URLError as e:
            last_err = f"URLError {e.reason}"
        except Exception as e:  # noqa: BLE001
            last_err = f"{type(e).__name__}: {e}"
        if attempt < retries - 1:
            wait = backoffs[min(attempt, len(backoffs) - 1)]
            logger.warning(f"[em] {report_name} 第 {attempt+1} 次失败: {last_err}，{wait}s 后重试")
            time.sleep(wait)
    raise RuntimeError(f"东方财富接口调用失败: {last_err}")


# ── 缓存 ──────────────────────────────────────────────────────────────
def _cache_key(report_name: str, columns_tuple: Tuple[str, ...], page_size: int) -> str:
    return f"em|{report_name}|{','.join(columns_tuple)}|ps{page_size}"


def _load_cache(path: str) -> Dict[str, Any]:
    if not os.path.exists(path):
        return {}
    try:
        return _load_json(path)
    except Exception:  # noqa: BLE001
        return {}


def _get_cached(cache: Dict[str, Any], key: str, ttl_hours: int) -> Optional[Dict[str, Any]]:
    if key not in cache:
        return None
    entry = cache[key]
    if ttl_hours > 0:
        age = time.time() - float(entry.get("timestamp", 0))
        if age > ttl_hours * 3600:
            return None
    return entry


# ── 行业解析 ──────────────────────────────────────────────────────────
def _resolve_industry(config: Dict[str, Any], name: str) -> Tuple[str, Dict[str, Any]]:
    industries = config.get("industries", {})
    if name in industries:
        return name, industries[name]
    # 别名匹配
    for key, meta in industries.items():
        aliases = meta.get("aliases", []) or []
        if name.lower() == key.lower() or name in aliases or name.lower() in [a.lower() for a in aliases]:
            return key, meta
    # 未命中：报错并列出候选
    raise ValueError(f"未配置行业 '{name}'。已配置: {', '.join(industries.keys())}")


def _resolve_indicators(config: Dict[str, Any], industry_meta: Dict[str, Any]) -> List[Dict[str, Any]]:
    """把 industries.<行业>.indicators（字符串引用或内联对象）解析成完整的 indicator 配置列表。

    支持两种写法：
      1) 字符串引用："社零总额" —— 从顶层 indicators 目录取
      2) 内联对象：{reportName, valueColumn, ...} —— 完整配置（向后兼容旧写法）
    """
    catalog = config.get("indicators", {}) or {}
    refs = industry_meta.get("indicators", []) or []
    resolved: List[Dict[str, Any]] = []
    unknown: List[str] = []
    for ref in refs:
        if isinstance(ref, str):
            if ref not in catalog:
                unknown.append(ref)
                continue
            entry = dict(catalog[ref])  # 拷贝
            entry.setdefault("name", ref)
            entry.setdefault("key", ref)
            resolved.append(entry)
        elif isinstance(ref, dict):
            # 向后兼容：允许内联完整对象
            item = dict(ref)
            item.setdefault("name", item.get("key") or item.get("valueColumn", ""))
            item.setdefault("key", item.get("name"))
            resolved.append(item)
        else:
            raise ValueError(f"indicators 列表项类型不支持: {type(ref).__name__}")
    if unknown:
        available = ", ".join(sorted(catalog.keys())) or "(目录为空)"
        raise ValueError(
            f"以下指标名在 indicators 目录中未找到: {', '.join(unknown)}。"
            f"可用指标: {available}"
        )
    if not resolved:
        raise ValueError("该行业未配置任何指标")
    return resolved


# ── 数据抽取与聚合 ────────────────────────────────────────────────────
def _extract_series(rows: List[Dict[str, Any]], value_col: str, yoy_col: Optional[str],
                    freq: str) -> List[Dict[str, Any]]:
    """把 EM 返回的一批 row 转成按 period 的序列（新 → 旧序）。同一 period 保留最新一条。"""
    seen: Dict[str, Dict[str, Any]] = {}
    order: List[str] = []
    for r in rows:
        rd = r.get("REPORT_DATE") or ""
        period = _report_date_to_period(rd, freq)
        if not period:
            continue
        val = r.get(value_col)
        yoy = r.get(yoy_col) if yoy_col else None
        if period in seen:
            continue
        seen[period] = {
            "period": period,
            "value": float(val) if isinstance(val, (int, float)) else None,
            "yoyPct": float(yoy) if isinstance(yoy, (int, float)) else None,
            "reportDate": rd[:10] if isinstance(rd, str) else None,
        }
        order.append(period)
    # 保持 EM 返回顺序（新 → 旧）
    return [seen[p] for p in order]


def _compute_yoy_if_missing(series: List[Dict[str, Any]], freq: str) -> List[Dict[str, Any]]:
    """如果 yoyPct 缺失但历史区间够用，本地计算同比。series 按 period 升序输入。"""
    by_period = {s["period"]: s for s in series}
    for s in series:
        if s.get("yoyPct") is not None:
            continue
        prev = _prev_period(s["period"], freq)
        if not prev or prev not in by_period:
            continue
        prev_v = by_period[prev].get("value")
        cur_v = s.get("value")
        if isinstance(prev_v, (int, float)) and isinstance(cur_v, (int, float)) and prev_v != 0:
            s["yoyPct"] = round((cur_v / prev_v - 1) * 100, 4)
    return series


# ── 主流程 ────────────────────────────────────────────────────────────
def _run(args: argparse.Namespace) -> Dict[str, Any]:
    config = _load_json(args.config)
    endpoint_cfg = config.get("endpoint", {})

    industry_key, industry_meta = _resolve_industry(config, args.industry)
    ind_cfgs = _resolve_indicators(config, industry_meta)

    # freq 默认策略：CLI > industry.freq_default > indicator[0].freq > "month"
    freq = (
        args.freq
        or industry_meta.get("freq_default")
        or ind_cfgs[0].get("freq")
        or "month"
    )
    if freq not in ("year", "quarter", "month"):
        raise ValueError(f"未知频次 {freq}")

    last_n = args.last or 12
    # 为了保证 quarter/year 频次能过滤出足够期次，多拉一些
    # month → last_n；quarter → last_n * 3 + 6；year → last_n * 12 + 12
    over_fetch_factor = {"month": 1, "quarter": 3, "year": 12}[freq]
    page_size = max(30, last_n * over_fetch_factor + 6)

    cache = {} if args.no_cache else _load_cache(args.cache_file)

    indicators_out: List[Dict[str, Any]] = []
    key_to_series: Dict[str, Dict[str, float]] = {}  # for derived: key → {period: value}
    source_urls: List[str] = []

    for ind in ind_cfgs:
        report_name = ind["reportName"]
        value_col = ind["valueColumn"]
        yoy_col = ind.get("yoyColumn")
        cols = "ALL"

        cache_key = _cache_key(report_name, (value_col, yoy_col or ""), page_size)
        cached = None if (args.no_cache or args.force_refresh) else _get_cached(cache, cache_key, args.cache_ttl_hours)
        if cached:
            resp = cached["resp"]
            url = cached.get("url", "")
            logger.info(f"[cache] hit: {cache_key}")
        else:
            resp, url = _query_em_report(report_name, cols, page_size, 1, endpoint_cfg, args.timeout)
            if not args.no_cache:
                cache[cache_key] = {"timestamp": time.time(), "url": url, "resp": resp}
        source_urls.append(url)

        rows = ((resp.get("result") or {}).get("data") or [])
        # 按 period 抽取（EM 已经是新 → 旧）
        series_desc = _extract_series(rows, value_col, yoy_col, freq)
        # 升序（不再自动补 YoY，避免把已经是"同比%"的列再次做同比；由 config 的 yoyColumn 控制）
        series_asc = list(reversed(series_desc))
        # 只保留最近 N 期
        if len(series_asc) > last_n:
            series_asc = series_asc[-last_n:]

        indicators_out.append({
            "key": ind.get("key", value_col),
            "reportName": report_name,
            "valueColumn": value_col,
            "name": ind.get("name", ""),
            "unit": ind.get("unit", ""),
            "series": series_asc,
        })
        key_to_series[ind.get("key", value_col)] = {s["period"]: s["value"] for s in series_asc if s["value"] is not None}

    # 派生指标
    derived_out: List[Dict[str, Any]] = []
    for d in industry_meta.get("derived", []) or []:
        formula = d["formula"]
        # 收集所有 key 覆盖的 period
        all_periods: set = set()
        for k, sm in key_to_series.items():
            all_periods.update(sm.keys())
        derived_series = []
        for period in sorted(all_periods):
            env = {k: sm[period] for k, sm in key_to_series.items() if period in sm}
            try:
                val = _eval_formula(formula, env) if env else None
            except Exception as e:  # noqa: BLE001
                logger.warning(f"派生指标 {d.get('name')} @{period} 计算失败: {e}")
                val = None
            derived_series.append({
                "period": period,
                "value": round(val, 4) if isinstance(val, float) else val,
            })
        derived_out.append({
            "name": d.get("name", ""),
            "unit": d.get("unit", ""),
            "formula": formula,
            "description": d.get("description", ""),
            "series": derived_series,
        })

    if not args.no_cache:
        _atomic_write_json(args.cache_file, cache)

    # 期次范围
    all_periods_sorted = sorted({pt["period"] for ind in indicators_out for pt in ind["series"]})
    period_range = f"{all_periods_sorted[0]} – {all_periods_sorted[-1]}" if all_periods_sorted else ""

    return {
        "success": True,
        "industry": industry_key,
        "freq": freq,
        "period_range": period_range,
        "cacheHit": all(ck in cache for ck in [_cache_key(i["reportName"], (i["valueColumn"], i.get("yoyColumn") or ""), page_size) for i in ind_cfgs]) if not args.no_cache else False,
        "indicators": indicators_out,
        "derived": derived_out,
        "sourceUrls": source_urls,
        "notes": industry_meta.get("notes", ""),
        "queriedAt": _now_iso(),
    }


# ── CLI ───────────────────────────────────────────────────────────────
def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="行业宏观数据查询（industry-macro-data skill，数据源：东方财富）"
    )
    p.add_argument("--industry", required=True, help="行业名，如 电商 / 汽车 / 房地产 / gdp / cpi / ppi / 工业")
    p.add_argument("--freq", choices=["year", "quarter", "month"], help="频次；默认走 config 里 freq_default")
    p.add_argument("--last", type=int, default=12, help="最近 N 期（默认 12）")
    p.add_argument("--config", default=DEFAULT_CONFIG, help=f"配置文件路径，默认 {DEFAULT_CONFIG}")
    p.add_argument("--pretty", action="store_true", help="缩进输出 JSON")
    p.add_argument("--cache-file", default=DEFAULT_CACHE, help=f"缓存路径，默认 {DEFAULT_CACHE}")
    p.add_argument("--cache-ttl-hours", type=int, default=24, help="缓存 TTL 小时（<=0 永不过期）")
    p.add_argument("--no-cache", action="store_true", help="禁用缓存")
    p.add_argument("--force-refresh", action="store_true", help="强制回源")
    p.add_argument("--timeout", type=int, default=30, help="HTTP 超时秒数")
    return p


def main() -> int:
    args = build_parser().parse_args()
    try:
        result = _run(args)
    except FileNotFoundError as e:
        _die(f"配置文件缺失: {e}", hint="检查 --config 路径")
        return 2
    except ValueError as e:
        _die(str(e))
        return 2
    except RuntimeError as e:
        _die(str(e), hint="接口调用失败，可稍后重试或使用 --no-cache")
        return 2
    except Exception as e:  # noqa: BLE001
        logger.exception("未预期错误")
        _die(f"未预期错误: {type(e).__name__}: {e}")
        return 2

    print(json.dumps(result, ensure_ascii=False, indent=2 if args.pretty else None))
    return 0


if __name__ == "__main__":
    sys.exit(main())
