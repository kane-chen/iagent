#!/usr/bin/env python3
"""
获取三大表原始财务数据并输出 JSON（供 iagent Java 财务数据服务入库）。

与 generate_financial_excel.py 的区别：不生成 Excel，只取数、归一化单位（百万）、
扁平化字段，输出结构化 JSON 到文件（stdout 最后一行打印输出路径）。

用法：
    python fetch_statements_json.py <code> [--num N] [--output PATH]

参数：
    code            股票代码，带市场前缀，如 HK.00700 / US.BABA / SH.600519
    --num N         拉取的财报期数（每种报表），默认 16，单页上限 50，自动分页
    --output PATH   输出 JSON 路径；默认 workspace/temp/<safe_code>_statements_<ts>.json

依赖：futu OpenD 网关已启动并登录（经由 futuapi skill 的 get_financials_statements.py）。
"""
import argparse
import json
import os
import subprocess
import sys
import time

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
# futuapi skill 的 quote 脚本目录（与 generate_financial_excel.py 相同的相对位置）
FUTUAPI_SCRIPT = os.path.normpath(os.path.join(
    SCRIPT_DIR, "..", "..", "futuapi", "scripts", "quote", "get_financials_statements.py"))
FUTUAPI_SCRIPTS_DIR = os.path.dirname(FUTUAPI_SCRIPT)

# statement key -> futu statement_type
STATEMENT_TYPES = {"income": 1, "balance": 2, "cashflow": 3}

# 市场前缀 -> (主 financial_type, 是否年内累计口径, 补充 financial_type)
# 港股/A股 主用 11=累计季报(Q1/Q6/Q9/年报)，补充 9=单季报组合(Q1/Q2/Q3/Q4)：
# 累计报供 CUMULATIVE 口径查询与差分兜底；单季报直接作为 SINGLE_Q 入库，避免"累计-0"的错误差分。
MARKET_PROFILES = {
    "US": (10, False, None),  # 美股：单季报 + 年报（单季值直接可用）
    "HK": (11, True, 9),      # 港股：累计季报 + 单季报组合
    "SH": (11, True, 9),      # A股：累计季报 + 单季报组合
    "SZ": (11, True, 9),
}

UNIT_DIVISOR = 1_000_000  # 原始单位为元，归一化为百万


def market_of(code: str) -> str:
    return code.split(".", 1)[0].upper()


def call_futu(code: str, statement_type: int, financial_type: int, num: int, next_key=None):
    """调用 futuapi 子脚本取一页数据，返回 data dict。"""
    cmd = [sys.executable, FUTUAPI_SCRIPT, code,
           "--statement-type", str(statement_type),
           "--financial-type", str(financial_type),
           "--num", str(num), "--json"]
    if next_key:
        cmd += ["--next-key", str(next_key)]

    env = os.environ.copy()
    env["PYTHONPATH"] = FUTUAPI_SCRIPTS_DIR + os.pathsep + env.get("PYTHONPATH", "")
    env["PYTHONIOENCODING"] = "utf-8"

    result = subprocess.run(cmd, capture_output=True, timeout=120, env=env)
    stdout = result.stdout.decode("utf-8", errors="replace")
    # 子脚本在 --json 模式下打印单个 JSON 对象；截取首个 '{' 到末尾 '}' 以规避噪声
    start = stdout.find("{")
    end = stdout.rfind("}")
    if start < 0 or end <= start:
        raise RuntimeError(f"futu 子脚本无 JSON 输出: rc={result.returncode}, "
                           f"stderr={result.stderr.decode('utf-8', errors='replace')[:500]}")
    payload = json.loads(stdout[start:end + 1])
    if "error" in payload:
        raise RuntimeError(f"futu 子脚本返回错误: {payload['error']}")
    return payload.get("data") or {}


def fetch_statement(code: str, statement_key: str, financial_type: int, num: int):
    """分页拉取一种报表，返回 {fields, reports}。"""
    st = STATEMENT_TYPES[statement_key]
    structure = {}
    reports = []
    next_key = None
    while len(reports) < num:
        page_num = min(50, num - len(reports))
        data = call_futu(code, st, financial_type, page_num, next_key)
        for f in data.get("structure_list", []) or []:
            fid = f.get("field_id")
            if fid is not None:
                structure[fid] = f.get("display_name") or f"字段{fid}"
        page_reports = data.get("report_list", []) or []
        reports.extend(page_reports)
        next_key = data.get("next_key")
        if not next_key or next_key == "-1" or not page_reports:
            break
        time.sleep(1.0)  # 尊重 30 次/30 秒限流
    reports = reports[:num]

    flat_reports = []
    for rpt in reports:
        items = []
        for item in rpt.get("item_list", []) or []:
            fid = item.get("field_id")
            if fid is None:
                continue
            raw = item.get("data")
            value = None
            if raw is not None:
                try:
                    value = round(float(raw) / UNIT_DIVISOR, 6)
                except (TypeError, ValueError):
                    value = None
            items.append({
                "fieldId": fid,
                "value": value,
                "yoy": _pct(item.get("yoy")),
                "qoq": _pct(item.get("qoq")),
            })
        flat_reports.append({
            "period": rpt.get("period_text"),
            "periodEnd": rpt.get("date_time_str"),
            "fiscalYear": rpt.get("fiscal_year"),
            "ftype": rpt.get("financial_type"),  # 1-4=单季报 5=Q6累计 6=Q9累计 7=年报
            "currency": rpt.get("currency_code"),
            "items": items,
        })

    return {
        "fields": [{"fieldId": fid, "name": name} for fid, name in sorted(structure.items())],
        "reports": flat_reports,
    }


def merge_statements(base, extra):
    """合并两次取数结果：字段取并集，报告按 (截止日, ftype) 去重。"""
    fields = {f["fieldId"]: f["name"] for f in base.get("fields", [])}
    for f in extra.get("fields", []):
        fields.setdefault(f["fieldId"], f["name"])
    seen = {(r.get("periodEnd"), r.get("ftype")) for r in base.get("reports", [])}
    reports = list(base.get("reports", []))
    for r in extra.get("reports", []):
        key = (r.get("periodEnd"), r.get("ftype"))
        if key not in seen:
            seen.add(key)
            reports.append(r)
    return {
        "fields": [{"fieldId": fid, "name": name} for fid, name in sorted(fields.items())],
        "reports": reports,
    }


def _pct(v):
    if v is None:
        return None
    try:
        return round(float(v), 4)
    except (TypeError, ValueError):
        return None


def main():
    parser = argparse.ArgumentParser(description="获取三大表财务数据并输出 JSON")
    parser.add_argument("code", help="股票代码，如 HK.00700 / US.BABA")
    parser.add_argument("--num", type=int, default=16, help="每种报表拉取的期数，默认 16")
    parser.add_argument("--output", default=None, help="输出 JSON 路径")
    args = parser.parse_args()

    code = args.code.strip().upper()
    market = market_of(code)
    if market not in MARKET_PROFILES:
        print(json.dumps({"error": f"不支持的市场前缀: {market}（支持 US/HK/SH/SZ）"}, ensure_ascii=False))
        sys.exit(1)
    financial_type, cumulative, extra_type = MARKET_PROFILES[market]

    if not os.path.exists(FUTUAPI_SCRIPT):
        print(json.dumps({"error": f"futuapi 脚本不存在: {FUTUAPI_SCRIPT}"}, ensure_ascii=False))
        sys.exit(1)

    statements = {}
    currency = None
    errors = {}
    for key in ("income", "balance", "cashflow"):
        try:
            stmt = fetch_statement(code, key, financial_type, args.num)
            # 港股/A股：追加单季报组合（Q1/Q2/Q3/Q4 单季直取，无需累计差分）
            if cumulative and extra_type:
                try:
                    extra = fetch_statement(code, key, extra_type, args.num)
                    stmt = merge_statements(stmt, extra)
                except Exception as e:
                    errors[key + "_single"] = str(e)
            statements[key] = stmt
            if currency is None:
                for r in stmt["reports"]:
                    if rpt_currency := r.get("currency"):
                        currency = rpt_currency
                        break
        except Exception as e:
            errors[key] = str(e)
            statements[key] = {"fields": [], "reports": []}

    out = {
        "ticker": code.split(".", 1)[1],
        "code": code,
        "market": market,
        "currency": currency,
        "unit": "million",
        "financialType": financial_type,
        "cumulative": cumulative,
        "statements": statements,
        "errors": errors,
    }

    if args.output:
        output_path = args.output
    else:
        workspace = os.path.normpath(os.path.join(SCRIPT_DIR, "..", "..", ".."))
        temp_dir = os.path.join(workspace, "temp")
        os.makedirs(temp_dir, exist_ok=True)
        safe_code = code.replace(".", "_")
        output_path = os.path.join(temp_dir, f"{safe_code}_statements_{time.strftime('%Y%m%d_%H%M%S')}.json")

    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)

    # stdout 最后一行输出结果摘要（Java 侧读取 JSON 文件，不依赖 stdout）
    print(json.dumps({
        "status": "ok" if not errors else "partial",
        "code": code, "market": market, "currency": currency,
        "output": os.path.abspath(output_path),
        "periods": {k: len(v["reports"]) for k, v in statements.items()},
        "errors": errors,
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
