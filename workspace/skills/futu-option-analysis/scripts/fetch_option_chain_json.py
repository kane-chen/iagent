#!/usr/bin/env python3
"""
获取期权链快照数据并输出 JSON（供 iagent Java 期权策略服务使用）。

数据来源（futu OpenAPI，经由本地 OpenD 网关）：
1. get_option_expiration_date  正股全部期权到期日
2. get_option_chain             某到期日的全部期权合约（CALL/PUT、行权价、代码）
3. get_market_snapshot          期权合约快照（最新价/买卖价/隐含波动率/未平仓量/Greeks）
                                 一次最多 400 个代码（港股 BMP 权限单次最多 20 个，自动降批）

仅支持港美正股/ETF（A 股不支持期权链）。

用法：
    python fetch_option_chain_json.py <code> [--expiries N] [--output PATH]

参数：
    code            正股代码，带市场前缀，如 US.AAPL / HK.00700
    --expiries N    取最近的 N 个到期日，默认 4
    --output PATH   输出 JSON 路径；默认 workspace/temp/<safe_code>_options_<ts>.json

输出 JSON 结构：
    {
      "code": "US.AAPL", "market": "US", "currency": "USD", "spot": 230.45,
      "expiries": [
        {"strike_time": "2026-09-18", "days_to_expiry": 15,
         "options": [
           {"code": "...", "option_type": "CALL", "strike": 240.0,
            "last": 2.35, "bid": 2.30, "ask": 2.40, "iv": 0.285,
            "oi": 1234, "volume": 500, "delta": 0.35, "contract_size": 100}
         ]}
      ]
    }

依赖：futu OpenD 网关已启动并登录（经由 futuapi skill 的 common 模块）。
"""
import argparse
import json
import math
import os
import sys
import time

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
# futuapi skill 的 scripts 目录（common.py 所在）
FUTUAPI_SCRIPTS_DIR = os.path.normpath(os.path.join(SCRIPT_DIR, "..", "..", "futuapi", "scripts"))
sys.path.insert(0, FUTUAPI_SCRIPTS_DIR)

from common import create_quote_context, check_ret, safe_close, is_empty  # noqa: E402

# 快照分批：接口单次上限 400；港股 BMP 权限单次最多 20 个期权代码，港股统一按 20 分批
SNAPSHOT_BATCH_US = 400
SNAPSHOT_BATCH_HK = 20
# 港股分批请求间隔（接口限频 60 次/30 秒，留余量）
HK_BATCH_SLEEP_SEC = 0.55

MARKET_CURRENCY = {"US": "USD", "HK": "HKD"}


def market_of(code: str) -> str:
    return code.split(".", 1)[0].upper()


def to_float(v):
    """快照值转 float；NaN/Inf/None 统一为 None。"""
    if v is None:
        return None
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    if math.isnan(f) or math.isinf(f):
        return None
    return f


def to_int(v):
    f = to_float(v)
    return int(f) if f is not None else None


def pick(row, *keys):
    """DataFrame 行按候选字段名取第一个非空值。"""
    for k in keys:
        if k in row and row[k] is not None:
            v = row[k]
            # pandas NaN 判定
            try:
                if isinstance(v, float) and math.isnan(v):
                    continue
            except TypeError:
                pass
            return v
    return None


def fetch(code: str, expiries: int):
    market = market_of(code)
    if market not in ("US", "HK"):
        raise RuntimeError(f"期权链仅支持港美正股/ETF，不支持 {market}（{code}）")

    ctx = None
    try:
        ctx = create_quote_context()

        # 1. 到期日列表（按时间升序，取最近 N 个未到期日）
        ret, data = ctx.get_option_expiration_date(code)
        check_ret(ret, data, ctx, "获取期权到期日")
        if is_empty(data):
            return {"code": code, "market": market,
                    "currency": MARKET_CURRENCY.get(market, ""), "spot": None, "expiries": []}
        dates = []
        for _, row in data.iterrows():
            d = int(row.get("option_expiry_date_distance", 0) or 0)
            if d >= 0:
                dates.append((str(row["strike_time"]), d))
        dates.sort(key=lambda x: x[1])
        dates = dates[:expiries]

        # 2. 各到期日期权链 → 合约代码
        # expiry_date -> [chain row dict]
        chain_by_expiry = {}
        for strike_time, _ in dates:
            ret, chain = ctx.get_option_chain(code, start=strike_time, end=strike_time)
            if ret != 0:
                # 单个到期日失败不影响其他到期日
                print(f"[WARN] 期权链获取失败 {strike_time}: {chain}", file=sys.stderr)
                continue
            if is_empty(chain):
                continue
            chain_by_expiry[strike_time] = chain.to_dict("records")
            time.sleep(0.2)

        all_option_codes = []
        for rows in chain_by_expiry.values():
            for r in rows:
                c = r.get("code")
                if c and c not in all_option_codes:
                    all_option_codes.append(c)

        # 3. 快照（期权 + 正股），分批
        batch_size = SNAPSHOT_BATCH_HK if market == "HK" else SNAPSHOT_BATCH_US
        snap_codes = list(all_option_codes)
        if code not in snap_codes:
            snap_codes.append(code)
        snapshots = {}
        for i in range(0, len(snap_codes), batch_size):
            batch = snap_codes[i:i + batch_size]
            ret, snap = ctx.get_market_snapshot(batch)
            check_ret(ret, snap, ctx, "获取期权快照")
            if not is_empty(snap):
                for rec in snap.to_dict("records"):
                    snapshots[rec.get("code")] = rec
            if market == "HK" and i + batch_size < len(snap_codes):
                time.sleep(HK_BATCH_SLEEP_SEC)

        spot = None
        underlying = snapshots.get(code)
        if underlying is not None:
            spot = to_float(pick(underlying, "last_price"))

        # 4. 组装输出
        out_expiries = []
        for strike_time, days in dates:
            rows = chain_by_expiry.get(strike_time)
            if not rows:
                continue
            options = []
            for r in rows:
                oc = r.get("code")
                snap = snapshots.get(oc, {})
                otype = str(pick(snap, "option_type") or r.get("option_type") or "").upper()
                if otype not in ("CALL", "PUT"):
                    continue
                strike = to_float(pick(snap, "option_strike_price", "strike_price"))
                if strike is None:
                    strike = to_float(r.get("strike_price"))
                contract_size = to_int(pick(snap, "option_contract_size", "contract_size")) or 100
                options.append({
                    "code": oc,
                    "option_type": otype,
                    "strike": strike,
                    "last": to_float(pick(snap, "last_price")),
                    "bid": to_float(pick(snap, "bid_price")),
                    "ask": to_float(pick(snap, "ask_price")),
                    "iv": to_float(pick(snap, "option_implied_volatility", "implied_volatility")),
                    "oi": to_int(pick(snap, "option_open_interest", "open_interest")) or 0,
                    "volume": to_int(pick(snap, "volume")) or 0,
                    "delta": to_float(pick(snap, "option_delta", "delta")),
                    "contract_size": contract_size,
                })
            # 按行权价排序，CALL/PUT 分组稳定输出
            options.sort(key=lambda o: (o["strike"] or 0, o["option_type"]))
            if options:
                out_expiries.append({
                    "strike_time": strike_time,
                    "days_to_expiry": days,
                    "options": options,
                })

        return {
            "code": code,
            "market": market,
            "currency": MARKET_CURRENCY.get(market, ""),
            "spot": spot,
            "expiries": out_expiries,
        }
    finally:
        safe_close(ctx)


def main():
    parser = argparse.ArgumentParser(description="获取期权链快照并输出 JSON")
    parser.add_argument("code", help="正股代码，带市场前缀，如 US.AAPL / HK.00700")
    parser.add_argument("--expiries", type=int, default=4, help="取最近 N 个到期日，默认 4")
    parser.add_argument("--output", default=None, help="输出 JSON 路径")
    args = parser.parse_args()

    code = args.code.upper()
    try:
        out = fetch(code, max(1, args.expiries))
    except Exception as e:
        print(json.dumps({"status": "error", "code": code, "error": str(e)},
                         ensure_ascii=False))
        sys.exit(1)

    if args.output:
        output_path = args.output
    else:
        workspace = os.path.normpath(os.path.join(SCRIPT_DIR, "..", "..", ".."))
        temp_dir = os.path.join(workspace, "temp")
        os.makedirs(temp_dir, exist_ok=True)
        safe_code = code.replace(".", "_")
        output_path = os.path.join(temp_dir, f"{safe_code}_options_{time.strftime('%Y%m%d_%H%M%S')}.json")

    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)

    # stdout 最后一行输出结果摘要（Java 侧读取 JSON 文件，不依赖 stdout）
    print(json.dumps({
        "status": "ok",
        "code": code,
        "market": out.get("market"),
        "currency": out.get("currency"),
        "spot": out.get("spot"),
        "output": os.path.abspath(output_path),
        "expiries": len(out.get("expiries", [])),
        "options": sum(len(e.get("options", [])) for e in out.get("expiries", [])),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
