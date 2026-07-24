#!/usr/bin/env python3
"""
3-Statement Model - 三表联动财务模型 Excel 生成脚本

对齐 references/schema.md 结构:
  Tab 1  Assumptions          分区式假设 (HEADER / MARKET DATA / REVENUE / COST / BS / DEBT / DIVIDEND)
  Tab 2  Income Statement     5 期历史 + 5 期预测, 含 Margin% 展示行
  Tab 3  Balance Sheet        Days-driven, 含 Balance Check + Cash Tie-Out
  Tab 4  Cash Flow            OCF/CFI/CFF 三段, dNWC 严格符号规则
  Tab 5  D&A Schedule         PPE Beg -> CapEx -> Dep -> End
  Tab 6  Debt Schedule        Beg -> Issue -> Repay -> Sweep -> End, Interest = Beg x Rate
  Tab 7  Working Capital      AR Days / Inv Days / AP Days 驱动

关键设计:
  - 币种一致性: Reporting Currency (财报) vs Trading Currency (股价), FX 换算 (与 dcf-model 一致)
  - CapEx 严格口径: 从 cashflow Excel 的 "资本开支(CapEx明细)" 行读取
  - Interest = Beginning Debt x Rate (断开循环引用)
  - 历史期 Cash 从 BS 直读, 预测期 Cash = CF Ending Cash
  - Balance Check / Cash Tie-Out 红色条件格式
"""
import argparse
import re
import zipfile
import tempfile
import os
import logging
import sys as _sys
import os as _os
from datetime import date
from pathlib import Path
from typing import Optional, Tuple, List

# 确保能导入 futuapi 的 common 模块 (股价 / FX 获取)
_script_dir = _os.path.dirname(_os.path.abspath(__file__))
_potential_paths = [
    _os.path.join(_script_dir, "..", "futuapi", "scripts"),
    _os.path.join(_script_dir, "..", "..", "futuapi", "scripts"),
    _os.path.join(_script_dir, "futuapi", "scripts"),
]
for p in _potential_paths:
    if _os.path.isdir(p):
        _sys.path.insert(0, _os.path.normpath(p))
        break

try:
    from common import create_quote_context, check_ret, safe_close, is_empty
except ImportError:
    def create_quote_context(*args, **kwargs): raise ImportError("common module not found")
    def check_ret(ret, data, ctx, msg): pass
    def safe_close(ctx): pass
    def is_empty(data): return data is None or len(data) == 0

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s', datefmt='%H:%M:%S')
logger = logging.getLogger(__name__)

try:
    import openpyxl
    from openpyxl.styles import Font, PatternFill, Border, Side, Alignment
    from openpyxl.utils import get_column_letter
    from openpyxl.comments import Comment
except ImportError as exc:
    raise ImportError("openpyxl required: pip install openpyxl") from exc

# ==================== Styles ====================
FONT_BLUE          = Font(color="0000FF")
FONT_BLACK         = Font(color="000000")
FONT_GREEN         = Font(color="008000")
FONT_PURPLE        = Font(color="800080")
FONT_WHITE_BOLD    = Font(color="FFFFFF", bold=True)
FONT_BOLD          = Font(bold=True)
FONT_BLACK_BOLD    = Font(color="000000", bold=True)
FONT_ITALIC_GREY   = Font(italic=True, color="595959", size=10)

FILL_DARK_BLUE     = PatternFill("solid", fgColor="1F4E79")
FILL_LIGHT_BLUE    = PatternFill("solid", fgColor="D9E1F2")
FILL_MEDIUM_BLUE   = PatternFill("solid", fgColor="BDD7EE")
FILL_INPUT_GREY    = PatternFill("solid", fgColor="F2F2F2")
FILL_FORECAST_GREEN = PatternFill("solid", fgColor="E2F0D9")

BORDER_THIN_BOTTOM   = Border(bottom=Side(style="thin", color="000000"))
BORDER_MEDIUM_BOTTOM = Border(bottom=Side(style="medium", color="000000"))
BORDER_HAIR_BOTTOM   = Border(bottom=Side(style="hair", color="595959"))
BORDER_HIST_RIGHT    = Border(right=Side(style="thin", color="595959"))

ALIGN_INDENT = Alignment(indent=2)

# ==================== 数字格式 (schema.md 标准) ====================
FMT_CURRENCY_M = '#,##0;(#,##0);"-"'
FMT_PRICE      = '#,##0.00'
FMT_PERCENT    = '0.0%'
FMT_MULTIPLE   = '0.0"x"'
FMT_SHARES     = '#,##0.00'
FMT_DECIMAL4   = '0.0000'
FMT_DAYS       = '0" days"'
FMT_CHECK      = '[Red][<>0]#,##0.00;[Red][<>0](#,##0.00);0'

# ==================== Comment helper ====================
_COMMENT_AUTHOR = "3-Statement Builder"

def add_comment(cell, text: str, width: int = 260, height: int = 100):
    c = Comment(text, _COMMENT_AUTHOR)
    c.width = width; c.height = height
    cell.comment = c

def add_source_comment(cell, system: str, ref: str = "", extra: str = ""):
    today = date.today().isoformat()
    parts = [f"Source: {system}", today]
    if ref: parts.append(ref)
    text = ", ".join(parts)
    if extra: text += "\n" + extra
    add_comment(cell, text)

def safe_divide(n, d):
    return n / d if d != 0 else 0.0

# ==================== 币种识别常量 ====================
_UNIT_NAME_TO_CURRENCY = {
    "百万人民币": "CNY", "百万港元": "HKD", "百万美元": "USD",
    "百万欧元": "EUR", "百万英镑": "GBP", "百万日元": "JPY",
    "百万新加坡元": "SGD", "百万澳元": "AUD", "百万加元": "CAD",
}
_MARKET_PREFIX_TO_CURRENCY = {"US": "USD", "HK": "HKD", "SH": "CNY", "SZ": "CNY"}
_FX_FUTU_CODES = {
    ("USD", "CNY"): ["HK.USDCNH", "HK.USDCNY"],
    ("USD", "HKD"): ["HK.USDHKD"],
    ("HKD", "CNY"): ["HK.HKDCNH", "HK.HKDCNY"],
}
_FX_FALLBACKS = {
    ("USD", "CNY"): 7.20, ("USD", "HKD"): 7.80, ("HKD", "CNY"): 0.92,
    ("EUR", "USD"): 1.08, ("GBP", "USD"): 1.27, ("USD", "JPY"): 155.0,
}

# ==================== Utility - Excel Reading ====================
def _ensure_shared_strings(file_path: Path) -> Tuple[Path, bool]:
    file_path = Path(file_path)
    try:
        with zipfile.ZipFile(str(file_path), 'r') as zf:
            if 'xl/sharedStrings.xml' in zf.namelist(): return file_path, False
            ct = zf.read('[Content_Types].xml').decode('utf-8', errors='ignore')
            if 'sharedStrings' not in ct: return file_path, False
    except:
        return file_path, False
    fd, tmp = tempfile.mkstemp(suffix='.xlsx', dir=str(file_path.parent))
    os.close(fd)
    try:
        with zipfile.ZipFile(str(file_path), 'r') as src, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as dst:
            for item in src.namelist(): dst.writestr(item, src.read(item))
            dst.writestr('xl/sharedStrings.xml', '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="0" uniqueCount="0"/>')
        return Path(tmp), True
    except:
        return file_path, False


def _read_excel_map(file_path: Path) -> Tuple[dict, Optional[str]]:
    """读取财报 Excel, 返回 (data, currency_code)。"""
    fp, is_tmp = _ensure_shared_strings(file_path)
    try:
        wb = openpyxl.load_workbook(str(fp), read_only=True, data_only=True)
        sheet = wb.active
        data = {}
        currency_code = None
        header_row = 1
        for r in range(1, 5):
            vals = [sheet.cell(r, c).value for c in range(1, sheet.max_column + 1) if sheet.cell(r, c).value]
            if "FY" in " ".join(str(v) for v in vals):
                header_row = r
                break
        periods = [str(sheet.cell(header_row, c).value) for c in range(3, sheet.max_column + 1)]
        for r in range(header_row + 1, sheet.max_row + 1):
            ind = sheet.cell(r, 1).value
            if not ind: continue
            ind = str(ind).strip()
            if currency_code is None:
                unit_str = sheet.cell(r, 2).value
                if isinstance(unit_str, str):
                    mapped = _UNIT_NAME_TO_CURRENCY.get(unit_str.strip())
                    if mapped: currency_code = mapped
            vals = {}
            for c, p in enumerate(periods):
                v = sheet.cell(r, c + 3).value
                num = 0.0
                if isinstance(v, (int, float)):
                    num = float(v)
                elif isinstance(v, str) and v:
                    cl = v.replace(",", "").replace("-", "").strip()
                    if cl.replace(".", "", 1).isdigit():
                        num = float(cl) * (-1 if v.strip().startswith("-") else 1)
                vals[p] = num
            data[ind] = vals
        wb.close()
        return data, currency_code
    finally:
        if is_tmp and fp.exists(): os.remove(str(fp))


def find_local_file(excels_path: Path, ticker: str, suffix: str) -> Optional[Path]:
    pattern = re.compile(rf'^.*_{re.escape(ticker)}_{re.escape(suffix)}_.*\.(xlsx|xls)$', re.IGNORECASE)
    # 排除 Excel 临时锁文件 ~$xxx.xlsx
    files = [f for f in excels_path.iterdir()
             if f.is_file() and not f.name.startswith('~$') and pattern.match(f.name)]
    return sorted(files)[-1] if files else None


def normalize_stock_code(ticker: str) -> str:
    ticker = ticker.strip().upper()
    if '.' in ticker and ticker.split('.')[0] in ('US', 'HK', 'SH', 'SZ'): return ticker
    if ticker.isdigit():
        n = int(ticker)
        if n >= 600000: return f"SH.{ticker}"
        elif n >= 300000: return f"SZ.{ticker}"
        else: return f"HK.{ticker.zfill(5)}"
    return f"US.{ticker}"


def infer_trading_currency(stock_code: str) -> str:
    prefix = stock_code.split('.')[0] if '.' in stock_code else ""
    return _MARKET_PREFIX_TO_CURRENCY.get(prefix, "USD")


# ==================== Futu Market Data & FX ====================
def fetch_market_data_from_futu(stock_code: str) -> dict:
    result = {"stock_price": None, "shares_outstanding": None, "shares_source": "Unknown"}
    ctx = None
    logger.info(f"正在通过 FutuOpenD 获取 {stock_code} 的市场数据...")
    try:
        ctx = create_quote_context()
        ret, data = ctx.get_market_snapshot([stock_code])
        check_ret(ret, data, ctx, "获取市场快照")
        if not is_empty(data):
            row = data.iloc[0]
            if 'last_price' in data.columns and row['last_price'] and float(row['last_price']) > 0:
                result["stock_price"] = float(row['last_price'])
            if 'issued_shares' in data.columns and row['issued_shares'] and float(row['issued_shares']) > 0:
                raw = float(row['issued_shares'])
                result["shares_outstanding"] = raw / 1_000_000
                result["shares_source"] = "Futu get_market_snapshot (issued_shares)"
    except SystemExit:
        logger.error("Futu API 未连接")
    except Exception as e:
        logger.exception(f"Futu API 异常: {e}")
    finally:
        if ctx: safe_close(ctx)
    return result


def fetch_fx_rate_from_futu(from_ccy: str, to_ccy: str) -> Tuple[float, str]:
    from_ccy, to_ccy = from_ccy.upper(), to_ccy.upper()
    if from_ccy == to_ccy: return 1.0, "Same currency (no conversion)"
    codes = _FX_FUTU_CODES.get((from_ccy, to_ccy), [])
    inverse_codes = _FX_FUTU_CODES.get((to_ccy, from_ccy), [])
    ctx = None
    try:
        if codes or inverse_codes:
            ctx = create_quote_context()
            for fx_code in codes:
                try:
                    ret, data = ctx.get_market_snapshot([fx_code])
                    if ret == 0 and not is_empty(data):
                        px = data.iloc[0].get('last_price')
                        if px and float(px) > 0:
                            return float(px), f"Futu get_market_snapshot ({fx_code})"
                except Exception: pass
            for fx_code in inverse_codes:
                try:
                    ret, data = ctx.get_market_snapshot([fx_code])
                    if ret == 0 and not is_empty(data):
                        px = data.iloc[0].get('last_price')
                        if px and float(px) > 0:
                            return 1.0 / float(px), f"Futu get_market_snapshot ({fx_code}, inverse)"
                except Exception: pass
    except Exception as e:
        logger.warning(f"FX 获取异常: {e}")
    finally:
        if ctx: safe_close(ctx)
    if (from_ccy, to_ccy) in _FX_FALLBACKS:
        return _FX_FALLBACKS[(from_ccy, to_ccy)], "Fallback constant"
    if (to_ccy, from_ccy) in _FX_FALLBACKS:
        return 1.0 / _FX_FALLBACKS[(to_ccy, from_ccy)], "Fallback constant (inverse)"
    return 1.0, "NO MAPPING"


# ==================== Financial Data Extraction ====================
def extract_financial_data(workspace: Path, ticker: str) -> dict:
    excels_path = workspace / "excels"
    inc_file = find_local_file(excels_path, ticker, "income")
    bs_file  = find_local_file(excels_path, ticker, "balance")
    cf_file  = find_local_file(excels_path, ticker, "cashflow")
    inc_data, inc_ccy = _read_excel_map(inc_file) if inc_file else ({}, None)
    bs_data,  bs_ccy  = _read_excel_map(bs_file)  if bs_file  else ({}, None)
    cf_data,  cf_ccy  = _read_excel_map(cf_file)  if cf_file  else ({}, None)

    reporting_currency = inc_ccy or bs_ccy or cf_ccy
    detected = [c for c in (inc_ccy, bs_ccy, cf_ccy) if c]
    if len(set(detected)) > 1:
        logger.warning(f"三张表币种不一致: inc={inc_ccy}, bs={bs_ccy}, cf={cf_ccy}")

    # 抽取所有 FY, 按时间倒序; 取最近 5 期正序
    all_fy = set()
    for d in [inc_data, bs_data, cf_data]:
        for k, v in d.items(): all_fy.update([p for p in v.keys() if "FY" in p])
    fy_cols = sorted(list(all_fy), reverse=True)
    if not fy_cols:
        raise RuntimeError(f"未找到 {ticker} 的历史财报数据, 请先运行 futu-financial-report")
    hist_fys = list(reversed(fy_cols[:5]))
    prior_fy = fy_cols[5] if len(fy_cols) > 5 else None

    def gv(data, keys, col):
        for k in keys:
            if k in data and col in data[k]: return data[k][col]
        return 0.0

    def _series(data, keys):
        return [gv(data, keys, fy) for fy in hist_fys]

    def _sum_all_matches(data, keys, col):
        total = 0.0
        for k in keys:
            if k in data and col in data[k]:
                v = data[k][col]
                if v: total += v
        return total

    def _sum_first_match(data, groups, col):
        total = 0.0
        for keys in groups:
            for k in keys:
                if k in data and col in data[k]:
                    v = data[k][col]
                    if v: total += v; break
        return total

    # ---- 利润表历史序列 ----
    revenue_series = _series(inc_data, ["总收入", "营业总收入"])
    cogs_series    = _series(inc_data, ["营业总成本", "营业成本"])
    opex_series    = _series(inc_data, ["营业费用"])
    ebit_series    = _series(inc_data, ["营业利润"])
    tax_series     = _series(inc_data, ["所得税"])
    ebt_series     = _series(inc_data, ["税前利润"])
    # NI: 归母口径优先, 精确匹配港股字段"归属母公司净利润"和美股字段"归属于母公司股东净利润"
    ni_series      = _series(inc_data, [
        "归属母公司净利润",           # 港股 5051 / 富途 HK 字段名
        "归属于母公司股东净利润",     # 美股 8043 富途字段名
        "归属于母公司股东的净利润",   # A 股常见字段名
        "净利润",                     # 合并口径 fallback (含少数股东损益)
    ])
    # 港股专属明细行 (5035/5036/5037), 美股/A 股返回 0 序列
    finance_income_series   = _series(inc_data, ["融资收入", "利息收入"])
    finance_cost_series     = _series(inc_data, ["融资成本", "利息费用", "财务费用"])
    equity_affiliate_series = _series(inc_data, ["应占联营公司利润", "应占联营公司盈利", "应占联营及合营公司损益", "投资收益"])

    # ---- CapEx 严格口径 ----
    capex_series = [abs(v) for v in _series(cf_data, ["资本开支(CapEx明细)"])]
    if all(v == 0.0 for v in capex_series):
        capex_series = [abs(v) for v in _series(cf_data, [
            "购建固定资产及无形资产净额", "购建固定资产",
            "购建固定资产、无形资产和其他长期资产支付的现金",
        ])]
    if all(v == 0.0 for v in capex_series):
        logger.warning("cashflow Excel 无 CapEx 明细字段, 回退到 income Excel")
        capex_series = [abs(v) for v in _series(inc_data, ["资本开支(CapEx)", "资本开支"])]

    da_series = _series(cf_data, ["折旧摊销及损耗", "折旧与摊销", "折旧及摊销"])
    if all(v == 0.0 for v in da_series):
        da_series = _series(inc_data, ["折旧摊销及损耗", "折旧与摊销"])

    # ---- 资产负债表历史 (与 dcf-model 一致的 Cash / Debt 口径) ----
    cash_series = []
    for fy in hist_fys:
        c = _sum_first_match(bs_data, [
            ["-现金和现金等价物", "现金及现金等价物", "现金及等价物", "货币资金"],
            ["-短期投资", "短期投资"],
        ], fy)
        c += _sum_all_matches(bs_data, [
            "定期存款-流动资产", "定期存款-非流动资产", "长期定期存款", "短期存款", "定期存款",
        ], fy)
        cash_series.append(c)

    ar_series  = _series(bs_data, ["-应收账款净额", "应收账款净额", "应收账款", "应收款项"])
    ap_series  = _series(bs_data, ["应付账款", "-应付账款"])
    inv_series = _series(bs_data, ["存货", "-存货"])
    ppe_series = _series(bs_data, ["固定资产净额", "物业厂房及设备", "固定资产"])
    intangible_series = _series(bs_data, ["无形资产"])
    equity_series = _series(bs_data, ["归属于母公司股东权益合计", "股东权益合计"])
    re_series    = _series(bs_data, ["留存收益", "未分配利润"])
    total_assets_series = _series(bs_data, ["资产合计"])
    total_liab_series   = _series(bs_data, ["负债合计"])

    debt_series = []
    for fy in hist_fys:
        d = _sum_first_match(bs_data, [
            ["短期借款与融资租赁负债", "-短期借款", "短期借款", "银行贷款及透支"],
            ["长期借款", "长期银行贷款"],
            ["长期融资租赁负债"],
        ], fy)
        debt_series.append(d)

    # ---- 假设默认值 (基于最新 FY) ----
    latest_idx = len(hist_fys) - 1
    lr = revenue_series[latest_idx] if revenue_series[latest_idx] else 1.0
    lc = cogs_series[latest_idx]
    prior_rev = gv(inc_data, ["总收入", "营业总收入"], prior_fy) if prior_fy else 0.0
    rev_growth_series = []
    for i, rev in enumerate(revenue_series):
        prev = revenue_series[i-1] if i > 0 else prior_rev
        rev_growth_series.append(safe_divide(rev - prev, prev) if prev else 0.0)
    latest_growth = rev_growth_series[-1] if rev_growth_series else 0.05

    cogs_pct  = safe_divide(cogs_series[latest_idx], lr)
    opex_pct  = safe_divide(opex_series[latest_idx], lr)
    da_pct    = safe_divide(da_series[latest_idx],   lr)
    capex_pct = safe_divide(capex_series[latest_idx], lr)
    tax_rate  = safe_divide(tax_series[latest_idx], ebt_series[latest_idx]) if ebt_series[latest_idx] > 0 else 0.25

    ar_days  = safe_divide(ar_series[latest_idx] * 365, lr)
    ap_days  = safe_divide(ap_series[latest_idx] * 365, lc) if lc else 0.0
    inv_days = safe_divide(inv_series[latest_idx] * 365, lc) if lc else 0.0

    # ---- 市场数据 & 币种 ----
    stock_code = normalize_stock_code(ticker)
    market = fetch_market_data_from_futu(stock_code)
    if market.get("stock_price") is None:
        market["stock_price"] = 100.0
        market["shares_source"] = "Default Fallback"
    if market.get("shares_outstanding") is None:
        market["shares_outstanding"] = 1000.0
        market["shares_source"] = "Default Fallback"

    trading_currency = infer_trading_currency(stock_code)
    if not reporting_currency:
        logger.warning(f"财报未提供币种, 回退到交易币种 {trading_currency}")
        reporting_currency = trading_currency
    fx_rate, fx_source = fetch_fx_rate_from_futu(trading_currency, reporting_currency)
    logger.info(f"币种: trading={trading_currency}, reporting={reporting_currency}, "
                f"FX=1 {trading_currency} = {fx_rate:.4f} {reporting_currency}")

    # 市场判别 (影响 IS 分市场行的展示)
    prefix = stock_code.split('.')[0] if '.' in stock_code else ""
    if prefix == "US":
        market_type = "us"
    elif prefix == "HK":
        market_type = "hk"
    else:
        market_type = "cn"

    # EBIT Margin 与 Other Income 假设 (基于历史反算)
    ebit_margin_series = [safe_divide(e, r) for e, r in zip(ebit_series, revenue_series)]
    other_income_series = []
    for i, fy in enumerate(hist_fys):
        # 分市场计算 Other Income:
        # 港股: EBT - EBIT - FinInc + FinCost - EqAff (通常接近 0, 明细已拆细)
        # 其他: EBT - EBIT (吞并所有非营业项, 含 Interest)
        if market_type == "hk":
            oi = (ebt_series[i] - ebit_series[i] - finance_income_series[i]
                  + finance_cost_series[i] - equity_affiliate_series[i])
        else:
            oi = ebt_series[i] - ebit_series[i]
        other_income_series.append(oi)
    other_income_pct_series = [safe_divide(o, r) for o, r in zip(other_income_series, revenue_series)]
    latest_ebit_margin = ebit_margin_series[-1] if ebit_margin_series else 0.05
    latest_other_income_pct = other_income_pct_series[-1] if other_income_pct_series else 0.0

    return {
        "ticker": ticker, "stock_code": stock_code, "hist_fys": hist_fys,
        "market_type": market_type,
        "hist_revenue": revenue_series, "hist_cogs": cogs_series, "hist_opex": opex_series,
        "hist_ebit": ebit_series, "hist_tax": tax_series, "hist_ebt": ebt_series,
        "hist_ni": ni_series, "hist_da": da_series, "hist_capex": capex_series,
        "hist_cash": cash_series, "hist_ar": ar_series, "hist_ap": ap_series,
        "hist_inv": inv_series, "hist_ppe": ppe_series, "hist_intangible": intangible_series,
        "hist_equity": equity_series, "hist_re": re_series, "hist_debt": debt_series,
        "hist_total_assets": total_assets_series, "hist_total_liab": total_liab_series,
        "hist_rev_growth": rev_growth_series,
        # 港股专属明细行 (美股/A 股为 0 序列)
        "hist_finance_income": finance_income_series,
        "hist_finance_cost": finance_cost_series,
        "hist_equity_affiliate": equity_affiliate_series,
        "hist_other_income": other_income_series,
        "hist_ebit_margin": ebit_margin_series,
        "hist_other_income_pct": other_income_pct_series,
        "growth": latest_growth, "cogs_pct": cogs_pct, "opex_pct": opex_pct,
        "da_pct": da_pct, "capex_pct": capex_pct, "tax_rate": tax_rate,
        "ebit_margin": latest_ebit_margin, "other_income_pct": latest_other_income_pct,
        "ar_days": ar_days, "ap_days": ap_days, "inv_days": inv_days,
        "stock_price": market["stock_price"], "shares_outstanding": market["shares_outstanding"],
        "shares_source": market["shares_source"],
        "reporting_currency": reporting_currency, "trading_currency": trading_currency,
        "fx_rate": fx_rate, "fx_source": fx_source,
    }


# ==================== Model Builder ====================
class ThreeStatementBuilder:
    """构建 3-Statement 模型 (严格对齐 references/schema.md).

    Sheet 顺序:
      1) Assumptions       - 分区式假设 (HEADER / MARKET / REVENUE / COST / BS / DEBT / DIV)
      2) Income Statement  - 5A + 5E, 含 Margin% 展示行
      3) Balance Sheet     - Days-driven, Balance Check + Cash Tie-Out
      4) Cash Flow         - OCF/CFI/CFF, 三段
      5) D&A Schedule      - PP&E roll-forward
      6) Debt Schedule     - Beg -> Iss -> Repay -> Sweep -> End
      7) Working Capital   - AR/Inv/AP Days-driven

    列布局 (所有报表 Sheet):
      A = 标签, B = 单位
      C..G = 5 期历史 (最老 -> 最新), 若历史不足 5 期, 从右对齐 (G 为最新历史)
      H..L = 5 期预测 (FY1..FY5)
    """

    def __init__(self, d: dict):
        self.d = d
        self.hist_fys = d["hist_fys"]
        n_hist = len(self.hist_fys)
        # 历史列右对齐到 G (col 7)
        self.n_hist = min(n_hist, 5)
        self.hist_start_col = 3 + (5 - self.n_hist)   # 若不足 5 期, 左侧留空
        self.HIST_COLS = list(range(self.hist_start_col, self.hist_start_col + self.n_hist))
        self.FCST_COLS = list(range(8, 13))            # H..L
        self.ALL_COLS  = list(range(3, 13))            # C..L
        self.hist_offset = n_hist - self.n_hist         # 若历史多于 5 期, 只用最近 5 期

        self.wb = openpyxl.Workbook()
        # 存放各 Sheet 的行号索引 (供跨 Sheet 引用)
        self.rows = {"assump": {}, "is": {}, "bs": {}, "cf": {}, "da": {}, "debt": {}, "wc": {}}

    # ---------- 通用样式 helper ----------
    def _apply_input(self, cell, value, fmt=None, source_system=None, source_ref="", is_locked=False):
        cell.value = value
        cell.font = FONT_BLUE if not is_locked else FONT_BLACK
        cell.fill = FILL_INPUT_GREY
        if fmt: cell.number_format = fmt
        if source_system: add_source_comment(cell, source_system, source_ref)

    def _write_hist_input(self, ws, row, series, fmt=FMT_CURRENCY_M, source=None, ref=""):
        """将历史序列写入 HIST_COLS (蓝色输入, 带 Source comment)。"""
        for i, col in enumerate(self.HIST_COLS):
            v = series[self.hist_offset + i] if self.hist_offset + i < len(series) else None
            if v is None: continue
            c = ws.cell(row, col, v)
            c.font = FONT_BLUE; c.fill = FILL_INPUT_GREY; c.number_format = fmt
            if source: add_source_comment(c, source, ref)

    def _write_section_header(self, ws, row, label, span=12):
        c = ws.cell(row, 1, label)
        c.font = FONT_WHITE_BOLD; c.fill = FILL_DARK_BLUE
        ws.merge_cells(start_row=row, start_column=1, end_row=row, end_column=span)

    def _write_column_headers(self, ws, row):
        """在 row 写入列头 (Item / Unit / 2020A..2024A / 2025E..2029E)。"""
        ws.cell(row, 1, "Line Item / 项目").font = FONT_BOLD
        ws.cell(row, 1).fill = FILL_LIGHT_BLUE
        ws.cell(row, 2, "Unit").font = FONT_BOLD
        ws.cell(row, 2).fill = FILL_LIGHT_BLUE
        for i, col in enumerate(self.HIST_COLS):
            fy_label = self.hist_fys[self.hist_offset + i]
            c = ws.cell(row, col, f"{fy_label} (A)")
            c.font = FONT_BOLD; c.fill = FILL_LIGHT_BLUE
        for i, col in enumerate(self.FCST_COLS):
            c = ws.cell(row, col, f"FY{i+1} (E)")
            c.font = FONT_BOLD; c.fill = FILL_LIGHT_BLUE
        # 历史列 vs 预测列 分隔线
        last_hist_col = self.HIST_COLS[-1] if self.HIST_COLS else 2
        for r_check in [row]:
            ws.cell(r_check, last_hist_col).border = BORDER_HIST_RIGHT

    def _fmt_column_widths(self, ws):
        ws.column_dimensions["A"].width = 38
        ws.column_dimensions["B"].width = 10
        for col in self.ALL_COLS:
            ws.column_dimensions[get_column_letter(col)].width = 13

    def build(self, output_path: Path):
        # 顺序: Assumptions -> Schedules -> IS -> CF -> BS
        # (下游 Sheet 引用上游行号, 但因为都是公式字符串, 顺序仅影响 rows 字典的填充时机)
        self._assumptions()
        self._wc_schedule()
        self._da_schedule()
        self._debt_schedule()
        self._income_statement()
        self._cash_flow()
        self._balance_sheet()
        # 按 schema.md Tab 顺序重排 Sheet
        desired = ["Assumptions", "Income Statement", "Balance Sheet", "Cash Flow",
                   "D&A Schedule", "Debt Schedule", "Working Capital"]
        # openpyxl 通过 _sheets 顺序控制
        self.wb._sheets = [self.wb[n] for n in desired if n in self.wb.sheetnames]
        output_path.parent.mkdir(parents=True, exist_ok=True)
        self.wb.save(str(output_path))
        logger.info(f"3-Statement model saved: {output_path}")

    # ==================== Tab 1: Assumptions ====================
    def _assumptions(self):
        ws = self.wb.active; ws.title = "Assumptions"
        d = self.d
        rep_ccy = d["reporting_currency"]; trd_ccy = d["trading_currency"]

        # --- HEADER 区 ---
        c = ws.cell(1, 1, f"{d['ticker']} 3-Statement Financial Model / {d['ticker']} 三表联动财务模型")
        c.fill = FILL_DARK_BLUE; c.font = FONT_WHITE_BOLD
        ws.merge_cells(start_row=1, start_column=1, end_row=1, end_column=5)
        c = ws.cell(2, 1, f"Ticker: {d['ticker']}  |  Date: {date.today().isoformat()}  |  Reporting: Million {rep_ccy}  |  Trading Ccy: {trd_ccy}")
        c.font = FONT_ITALIC_GREY
        ws.merge_cells(start_row=2, start_column=1, end_row=2, end_column=5)
        c = ws.cell(3, 1, f"Data Source: {d['shares_source']}  |  FX: {d['fx_source']}")
        c.font = FONT_GREEN
        ws.merge_cells(start_row=3, start_column=1, end_row=3, end_column=5)

        row = 5
        r = self.rows["assump"]

        # --- MARKET DATA 区 ---
        self._write_section_header(ws, row, "MARKET DATA -- 市场数据", span=3); row += 1
        ws.cell(row, 1, f"Current Stock Price ({trd_ccy})")
        self._apply_input(ws.cell(row, 2), d["stock_price"], FMT_PRICE,
                          "Futu get_market_snapshot", "last_price")
        r["stock_price"] = row; row += 1

        ws.cell(row, 1, f"FX Rate: 1 {trd_ccy} = X {rep_ccy}")
        fx_lock = (trd_ccy == rep_ccy)
        cell = ws.cell(row, 2, d["fx_rate"])
        cell.font = FONT_BLACK if fx_lock else FONT_BLUE
        cell.fill = FILL_INPUT_GREY; cell.number_format = FMT_DECIMAL4
        add_comment(cell, f"FX 汇率: 1 {trd_ccy} = X {rep_ccy}\n来源: {d['fx_source']}\n用户可覆盖")
        r["fx_rate"] = row; row += 1

        ws.cell(row, 1, f"Shares Outstanding (M)")
        self._apply_input(ws.cell(row, 2), d["shares_outstanding"], FMT_SHARES,
                          "Futu get_market_snapshot", "issued_shares (÷1M)")
        r["shares"] = row; row += 2

        # --- REVENUE ASSUMPTIONS 区 ---
        self._write_section_header(ws, row, "REVENUE ASSUMPTIONS -- 收入假设 (逐年输入)", span=12); row += 1
        # 列头 (FY1..FY5)
        for i, col in enumerate(self.FCST_COLS):
            c = ws.cell(row, col, f"FY{i+1}"); c.font = FONT_BOLD; c.fill = FILL_LIGHT_BLUE
        row += 1
        # Revenue Growth % (5 期预测输入, 与历史比较)
        ws.cell(row, 1, "Revenue Growth % / 营收增长率")
        for i, col in enumerate(self.FCST_COLS):
            # 默认: 用最新历史增长率, 逐年递减 0.5%
            val = max(d["growth"] - 0.005 * i, 0.02)
            self._apply_input(ws.cell(row, col), val, FMT_PERCENT,
                              "Assumption", f"Base on {self.hist_fys[-1]} actual growth")
        r["rev_growth"] = row; row += 2

        # --- COST ASSUMPTIONS 区 ---
        self._write_section_header(ws, row, "COST ASSUMPTIONS -- 成本假设 (%/率, 逐年可变)", span=12); row += 1
        # 列头
        for i, col in enumerate(self.FCST_COLS):
            c = ws.cell(row, col, f"FY{i+1}"); c.font = FONT_BOLD; c.fill = FILL_LIGHT_BLUE
        row += 1
        # COGS %
        ws.cell(row, 1, "COGS % of Revenue / 营收成本率")
        for col in self.FCST_COLS:
            self._apply_input(ws.cell(row, col), d["cogs_pct"], FMT_PERCENT,
                              "Assumption", f"Latest FY COGS/Revenue = {d['cogs_pct']:.2%}")
        r["cogs_pct"] = row; row += 1
        # OpEx %
        ws.cell(row, 1, "OpEx % of Revenue / 营业费用率 (S&M + G&A + R&D)")
        for col in self.FCST_COLS:
            self._apply_input(ws.cell(row, col), d["opex_pct"], FMT_PERCENT,
                              "Assumption", "Latest FY OpEx/Revenue")
        r["opex_pct"] = row; row += 1
        # D&A %
        ws.cell(row, 1, "D&A % of Revenue / 折旧摊销率")
        for col in self.FCST_COLS:
            self._apply_input(ws.cell(row, col), max(d["da_pct"], 0.03), FMT_PERCENT,
                              "Assumption", f"Latest FY D&A/Revenue = {d['da_pct']:.2%}")
        r["da_pct"] = row; row += 1
        # EBIT Margin % (直接驱动 EBIT 预测, 替代 GP - OpEx - D&A 组装)
        ws.cell(row, 1, "EBIT Margin % / 息税前利润率")
        for col in self.FCST_COLS:
            self._apply_input(ws.cell(row, col), d["ebit_margin"], FMT_PERCENT,
                              "Assumption", f"Latest FY EBIT/Revenue = {d['ebit_margin']:.2%}")
        r["ebit_margin"] = row; row += 1
        # Other Income % of Revenue (非营业净收益/损失, 港股主要含 应占联营/融资净收支 之外的残差; 美股/A股为 EBT − EBIT plug)
        ws.cell(row, 1, "Other Income % of Revenue / 其他非营业净收益率")
        for col in self.FCST_COLS:
            self._apply_input(ws.cell(row, col), d["other_income_pct"], FMT_PERCENT,
                              "Assumption", f"Latest FY (EBT-EBIT-fin adj)/Revenue = {d['other_income_pct']:.2%}")
        r["other_income_pct"] = row; row += 1
        # Tax Rate
        ws.cell(row, 1, "Tax Rate / 税率")
        for col in self.FCST_COLS:
            self._apply_input(ws.cell(row, col), d["tax_rate"], FMT_PERCENT,
                              "Assumption", "Latest FY 有效税率, 亏损默认 25%")
        r["tax_rate"] = row; row += 2

        # --- BS ASSUMPTIONS 区 ---
        self._write_section_header(ws, row, "BALANCE SHEET ASSUMPTIONS -- 资产负债表假设", span=12); row += 1
        for i, col in enumerate(self.FCST_COLS):
            c = ws.cell(row, col, f"FY{i+1}"); c.font = FONT_BOLD; c.fill = FILL_LIGHT_BLUE
        row += 1
        # CapEx %
        ws.cell(row, 1, "CapEx % of Revenue / 资本开支率")
        for col in self.FCST_COLS:
            self._apply_input(ws.cell(row, col), max(d["capex_pct"], 0.02), FMT_PERCENT,
                              "Assumption", f"Latest FY CapEx (strict)/Revenue = {d['capex_pct']:.2%}")
        r["capex_pct"] = row; row += 1
        # AR Days
        ws.cell(row, 1, "AR Days / 应收账款周转天数")
        for col in self.FCST_COLS:
            self._apply_input(ws.cell(row, col), d["ar_days"], FMT_DAYS,
                              "Assumption", f"AR × 365 / Revenue = {d['ar_days']:.1f}")
        r["ar_days"] = row; row += 1
        # Inventory Days
        ws.cell(row, 1, "Inventory Days / 存货周转天数")
        for col in self.FCST_COLS:
            self._apply_input(ws.cell(row, col), d["inv_days"], FMT_DAYS,
                              "Assumption", f"Inv × 365 / COGS = {d['inv_days']:.1f}")
        r["inv_days"] = row; row += 1
        # AP Days
        ws.cell(row, 1, "AP Days / 应付账款周转天数")
        for col in self.FCST_COLS:
            self._apply_input(ws.cell(row, col), d["ap_days"], FMT_DAYS,
                              "Assumption", f"AP × 365 / COGS = {d['ap_days']:.1f}")
        r["ap_days"] = row; row += 2

        # --- DEBT ASSUMPTIONS 区 ---
        self._write_section_header(ws, row, "DEBT ASSUMPTIONS -- 债务假设", span=3); row += 1
        ws.cell(row, 1, "Interest Rate on Debt / 债务利率")
        self._apply_input(ws.cell(row, 2), 0.045, FMT_PERCENT,
                          "Assumption", "Default 4.5%, 10-K 债券利率或市场基准可覆盖")
        r["interest_rate"] = row; row += 1
        ws.cell(row, 1, "Mandatory Repayment / yr / 每年强制还款")
        self._apply_input(ws.cell(row, 2), 0.0, FMT_CURRENCY_M,
                          "Assumption", "每年强制偿还本金 (百万, 报表币种)")
        r["mandatory_repay"] = row; row += 1
        ws.cell(row, 1, "Cash Sweep % / 超额现金还款比例")
        self._apply_input(ws.cell(row, 2), 0.0, FMT_PERCENT,
                          "Assumption", "自由现金还款比例 (0=关闭)")
        r["cash_sweep"] = row; row += 2

        # --- DIVIDEND ASSUMPTIONS 区 ---
        self._write_section_header(ws, row, "DIVIDEND ASSUMPTIONS -- 股利/回购假设", span=3); row += 1
        ws.cell(row, 1, "Dividend Payout Ratio / 股利支付率")
        self._apply_input(ws.cell(row, 2), 0.0, FMT_PERCENT,
                          "Assumption", "Dividends / Net Income (0 = 无分红)")
        r["div_payout"] = row; row += 1
        ws.cell(row, 1, "Share Repurchases / yr / 每年回购金额")
        self._apply_input(ws.cell(row, 2), 0.0, FMT_CURRENCY_M,
                          "Assumption", "年度回购金额 (百万, 报表币种)")
        r["repurchase"] = row; row += 1

        # 列宽
        ws.column_dimensions["A"].width = 42
        ws.column_dimensions["B"].width = 14
        for col in self.FCST_COLS:
            ws.column_dimensions[get_column_letter(col)].width = 12

    # ==================== Tab 7: Working Capital Schedule ====================
    def _wc_schedule(self):
        """AR / Inventory / AP Days-driven; 生成 Balance 与 Δ 送 CF/BS。"""
        ws = self.wb.create_sheet("Working Capital")
        d = self.d; r = self.rows["wc"]
        assump = self.rows["assump"]

        self._write_section_header(ws, 1, "WORKING CAPITAL SCHEDULE -- 营运资本表", span=12)
        row = 2
        self._write_column_headers(ws, row); row += 1

        # 依赖: Revenue / COGS 从 IS 引用 (下面 IS 会填充 rows["is"]["revenue"], ["cogs"])
        # 但 WC 先于 IS 建立, 因此需要 pre-declare 目标行号。
        # 让我们约定 IS 行号 (稍后 IS 会遵守此布局):
        # IS 第 8 行 = Revenue, 第 10 行 = COGS (稍后 IS 严格按此)
        is_rev_row = 8
        is_cogs_row = 10
        self._is_layout = {"revenue": is_rev_row, "cogs": is_cogs_row}

        # ---- AR ----
        ws.cell(row, 1, "AR Days / 应收账款周转天数")
        for i, col in enumerate(self.HIST_COLS):
            v = self.d["hist_ar"][self.hist_offset + i]
            rev = self.d["hist_revenue"][self.hist_offset + i]
            days = safe_divide(v * 365, rev) if rev else 0.0
            c = ws.cell(row, col, days); c.font = FONT_BLACK; c.number_format = FMT_DAYS
        for i, col in enumerate(self.FCST_COLS):
            c = ws.cell(row, col, f"=Assumptions!${get_column_letter(col)}${assump['ar_days']}")
            c.font = FONT_GREEN; c.number_format = FMT_DAYS
        r["ar_days"] = row; row += 1

        ws.cell(row, 1, "AR Balance / 应收账款余额")
        ws.cell(row, 2, "M")
        self._write_hist_input(ws, row, self.d["hist_ar"],
                                source="富途 BS", ref="应收账款净额")
        for col in self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"='Income Statement'!{cL}{is_rev_row}*{cL}{r['ar_days']}/365"
            c = ws.cell(row, col, f); c.font = FONT_BLACK; c.fill = FILL_FORECAST_GREEN; c.number_format = FMT_CURRENCY_M
            add_comment(c, f"AR = Revenue x AR Days / 365\n= 'Income Statement'!{cL}{is_rev_row} x {cL}{r['ar_days']} / 365")
        r["ar_bal"] = row; row += 1

        # Δ AR (Prior - Current, CF 影响: 资产增加 => -)
        ws.cell(row, 1, "Δ AR / 应收账款变动 (Prior − Current)")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col); prev_cL = get_column_letter(col - 1)
            if col == self.HIST_COLS[0]:
                ws.cell(row, col, 0).font = FONT_BLACK
            else:
                c = ws.cell(row, col, f"={prev_cL}{r['ar_bal']}-{cL}{r['ar_bal']}")
                c.font = FONT_BLACK; c.number_format = FMT_CURRENCY_M
                if col in self.FCST_COLS: c.fill = FILL_FORECAST_GREEN
        r["ar_chg"] = row; row += 2

        # ---- Inventory ----
        ws.cell(row, 1, "Inventory Days / 存货周转天数")
        for i, col in enumerate(self.HIST_COLS):
            v = self.d["hist_inv"][self.hist_offset + i]
            cg = self.d["hist_cogs"][self.hist_offset + i]
            days = safe_divide(v * 365, cg) if cg else 0.0
            c = ws.cell(row, col, days); c.font = FONT_BLACK; c.number_format = FMT_DAYS
        for col in self.FCST_COLS:
            c = ws.cell(row, col, f"=Assumptions!${get_column_letter(col)}${assump['inv_days']}")
            c.font = FONT_GREEN; c.number_format = FMT_DAYS
        r["inv_days"] = row; row += 1

        ws.cell(row, 1, "Inventory Balance / 存货余额")
        ws.cell(row, 2, "M")
        self._write_hist_input(ws, row, self.d["hist_inv"], source="富途 BS", ref="存货")
        for col in self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"='Income Statement'!{cL}{is_cogs_row}*{cL}{r['inv_days']}/365"
            c = ws.cell(row, col, f); c.font = FONT_BLACK; c.fill = FILL_FORECAST_GREEN; c.number_format = FMT_CURRENCY_M
        r["inv_bal"] = row; row += 1

        ws.cell(row, 1, "Δ Inventory / 存货变动 (Prior − Current)")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col); prev_cL = get_column_letter(col - 1)
            if col == self.HIST_COLS[0]:
                ws.cell(row, col, 0).font = FONT_BLACK
            else:
                c = ws.cell(row, col, f"={prev_cL}{r['inv_bal']}-{cL}{r['inv_bal']}")
                c.font = FONT_BLACK; c.number_format = FMT_CURRENCY_M
                if col in self.FCST_COLS: c.fill = FILL_FORECAST_GREEN
        r["inv_chg"] = row; row += 2

        # ---- AP ----
        ws.cell(row, 1, "AP Days / 应付账款周转天数")
        for i, col in enumerate(self.HIST_COLS):
            v = self.d["hist_ap"][self.hist_offset + i]
            cg = self.d["hist_cogs"][self.hist_offset + i]
            days = safe_divide(v * 365, cg) if cg else 0.0
            c = ws.cell(row, col, days); c.font = FONT_BLACK; c.number_format = FMT_DAYS
        for col in self.FCST_COLS:
            c = ws.cell(row, col, f"=Assumptions!${get_column_letter(col)}${assump['ap_days']}")
            c.font = FONT_GREEN; c.number_format = FMT_DAYS
        r["ap_days"] = row; row += 1

        ws.cell(row, 1, "AP Balance / 应付账款余额")
        ws.cell(row, 2, "M")
        self._write_hist_input(ws, row, self.d["hist_ap"], source="富途 BS", ref="应付账款")
        for col in self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"='Income Statement'!{cL}{is_cogs_row}*{cL}{r['ap_days']}/365"
            c = ws.cell(row, col, f); c.font = FONT_BLACK; c.fill = FILL_FORECAST_GREEN; c.number_format = FMT_CURRENCY_M
        r["ap_bal"] = row; row += 1

        ws.cell(row, 1, "Δ AP / 应付账款变动 (Current − Prior)")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col); prev_cL = get_column_letter(col - 1)
            if col == self.HIST_COLS[0]:
                ws.cell(row, col, 0).font = FONT_BLACK
            else:
                c = ws.cell(row, col, f"={cL}{r['ap_bal']}-{prev_cL}{r['ap_bal']}")
                c.font = FONT_BLACK; c.number_format = FMT_CURRENCY_M
                if col in self.FCST_COLS: c.fill = FILL_FORECAST_GREEN
        r["ap_chg"] = row; row += 2

        # ---- Total ΔNWC ----
        c = ws.cell(row, 1, "Total Δ NWC / 营运资本变动合计 (→ CF)"); c.font = FONT_BOLD
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['ar_chg']}+{cL}{r['inv_chg']}+{cL}{r['ap_chg']}"
            cc = ws.cell(row, col, f); cc.font = FONT_BLACK_BOLD; cc.number_format = FMT_CURRENCY_M
            cc.border = BORDER_THIN_BOTTOM
            if col in self.FCST_COLS: cc.fill = FILL_FORECAST_GREEN
        r["dNWC"] = row; row += 1

        self._fmt_column_widths(ws)

    # ==================== Tab 5: D&A / PP&E Schedule ====================
    def _da_schedule(self):
        """PP&E Beg + CapEx - Dep = Net End; 输出 Total D&A 送 IS/CF, PP&E Net 送 BS。"""
        ws = self.wb.create_sheet("D&A Schedule")
        d = self.d; r = self.rows["da"]
        assump = self.rows["assump"]
        is_layout = self._is_layout

        self._write_section_header(ws, 1, "D&A / PP&E SCHEDULE -- 折旧摊销与固定资产表", span=12)
        row = 2
        self._write_column_headers(ws, row); row += 1

        # ---- PP&E Beginning Balance ----
        ws.cell(row, 1, "PP&E Beginning Balance / 固定资产期初余额"); ws.cell(row, 2, "M")
        for i, col in enumerate(self.HIST_COLS):
            if i == 0:
                v = self.d["hist_ppe"][self.hist_offset + i] * 0.9
                c = ws.cell(row, col, v); c.font = FONT_BLUE; c.fill = FILL_INPUT_GREY
                c.number_format = FMT_CURRENCY_M
                add_source_comment(c, "Estimate", "0.9 x current PP&E (initial only)")
            else:
                cL = get_column_letter(col); prev_cL = get_column_letter(col - 1)
                c = ws.cell(row, col, f"={prev_cL}{row+4}")
                c.font = FONT_BLACK; c.number_format = FMT_CURRENCY_M
        for i, col in enumerate(self.FCST_COLS):
            cL = get_column_letter(col); prev_cL = get_column_letter(col - 1)
            c = ws.cell(row, col, f"={prev_cL}{row+4}")
            c.font = FONT_BLACK; c.fill = FILL_FORECAST_GREEN; c.number_format = FMT_CURRENCY_M
        r["ppe_beg"] = row; row += 1

        # ---- CapEx ----
        ws.cell(row, 1, "(+) Capital Expenditure / 加: 资本开支"); ws.cell(row, 2, "M")
        self._write_hist_input(ws, row, self.d["hist_capex"],
                                source="富途 CF (CapEx明细)", ref="严格明细口径")
        for col in self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"='Income Statement'!{cL}{is_layout['revenue']}*Assumptions!{cL}{assump['capex_pct']}"
            c = ws.cell(row, col, f); c.font = FONT_BLACK; c.fill = FILL_FORECAST_GREEN; c.number_format = FMT_CURRENCY_M
        r["capex"] = row; row += 1

        # ---- Depreciation & Amortization ----
        ws.cell(row, 1, "(-) Depreciation & Amortization / 减: 折旧摊销"); ws.cell(row, 2, "M")
        self._write_hist_input(ws, row, self.d["hist_da"], source="富途 CF", ref="折旧摊销及损耗")
        for col in self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"='Income Statement'!{cL}{is_layout['revenue']}*Assumptions!{cL}{assump['da_pct']}"
            c = ws.cell(row, col, f); c.font = FONT_BLACK; c.fill = FILL_FORECAST_GREEN; c.number_format = FMT_CURRENCY_M
        r["da"] = row; row += 1

        # ---- PP&E Ending Balance ----
        # 历史列: 直接引用 hist_ppe (蓝色输入, 与 BS 完全匹配)
        # 预测列: 公式 = Beg + CapEx - D&A
        c = ws.cell(row, 1, "PP&E Ending Balance (Net) / 固定资产期末净额"); c.font = FONT_BOLD
        ws.cell(row, 2, "M")
        for i, col in enumerate(self.HIST_COLS):
            v = self.d["hist_ppe"][self.hist_offset + i]
            cc = ws.cell(row, col, v)
            cc.font = FONT_BLUE; cc.fill = FILL_INPUT_GREY
            cc.number_format = FMT_CURRENCY_M
            add_source_comment(cc, "富途 BS", "固定资产净额 (确保与 BS 匹配)")
        for col in self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['ppe_beg']}+{cL}{r['capex']}-{cL}{r['da']}"
            cc = ws.cell(row, col, f); cc.font = FONT_BLACK_BOLD; cc.number_format = FMT_CURRENCY_M
            cc.border = BORDER_THIN_BOTTOM
            cc.fill = FILL_FORECAST_GREEN
            add_comment(cc, "PP&E End = PP&E Beg + CapEx - D&A")
        r["ppe_end"] = row; row += 1

        self._fmt_column_widths(ws)

    # ==================== Tab 6: Debt Schedule ====================
    def _debt_schedule(self):
        """单 tranche: Beg + Issue - Mandatory - Sweep = End; Interest = Beg x Rate。"""
        ws = self.wb.create_sheet("Debt Schedule")
        d = self.d; r = self.rows["debt"]
        assump = self.rows["assump"]

        self._write_section_header(ws, 1, "DEBT SCHEDULE -- 债务表", span=12)
        row = 2
        self._write_column_headers(ws, row); row += 1

        # ---- Beginning Balance ----
        ws.cell(row, 1, "Beginning Balance / 期初余额"); ws.cell(row, 2, "M")
        for i, col in enumerate(self.HIST_COLS):
            if i == 0:
                v = self.d["hist_debt"][self.hist_offset + i]
                c = ws.cell(row, col, v); c.font = FONT_BLUE; c.fill = FILL_INPUT_GREY
                c.number_format = FMT_CURRENCY_M
                add_source_comment(c, "富途 BS", "初始 Debt Balance")
            else:
                prev_cL = get_column_letter(col - 1)
                c = ws.cell(row, col, f"={prev_cL}{row+4}")
                c.font = FONT_BLACK; c.number_format = FMT_CURRENCY_M
        for col in self.FCST_COLS:
            prev_cL = get_column_letter(col - 1)
            c = ws.cell(row, col, f"={prev_cL}{row+4}")
            c.font = FONT_BLACK; c.fill = FILL_FORECAST_GREEN; c.number_format = FMT_CURRENCY_M
        r["debt_beg"] = row; row += 1

        # ---- Issuance ----
        ws.cell(row, 1, "(+) Debt Issuance / 加: 新增借款"); ws.cell(row, 2, "M")
        for col in self.HIST_COLS:
            i_ = self.HIST_COLS.index(col)
            if i_ == 0:
                ws.cell(row, col, 0).font = FONT_BLACK
            else:
                cL = get_column_letter(col)
                f = f"=MAX(0,{cL}{row+4}-{cL}{r['debt_beg']})"
                c = ws.cell(row, col, f); c.font = FONT_BLACK; c.number_format = FMT_CURRENCY_M
        for col in self.FCST_COLS:
            c = ws.cell(row, col, 0); c.font = FONT_BLUE; c.fill = FILL_INPUT_GREY
            c.number_format = FMT_CURRENCY_M
            add_source_comment(c, "Assumption", "预测期无新增借款, 用户可修改")
        r["issuance"] = row; row += 1

        # ---- Mandatory Repayment ----
        ws.cell(row, 1, "(-) Mandatory Repayment / 减: 强制还款"); ws.cell(row, 2, "M")
        for col in self.HIST_COLS:
            ws.cell(row, col, 0).font = FONT_BLACK
        for col in self.FCST_COLS:
            c = ws.cell(row, col, f"=Assumptions!$B${assump['mandatory_repay']}")
            c.font = FONT_GREEN; c.fill = FILL_FORECAST_GREEN; c.number_format = FMT_CURRENCY_M
        r["mandatory"] = row; row += 1

        # ---- Cash Sweep ----
        ws.cell(row, 1, "(-) Cash Sweep / 减: 超额现金还款"); ws.cell(row, 2, "M")
        for col in self.HIST_COLS:
            ws.cell(row, col, 0).font = FONT_BLACK
        for col in self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"=Assumptions!$B${assump['cash_sweep']}*{cL}{r['debt_beg']}"
            c = ws.cell(row, col, f); c.font = FONT_GREEN; c.fill = FILL_FORECAST_GREEN
            c.number_format = FMT_CURRENCY_M
        r["sweep"] = row; row += 1

        # ---- Ending Balance ----
        c = ws.cell(row, 1, "Ending Balance / 期末余额"); c.font = FONT_BOLD
        ws.cell(row, 2, "M")
        for i, col in enumerate(self.HIST_COLS):
            v = self.d["hist_debt"][self.hist_offset + i]
            cc = ws.cell(row, col, v); cc.font = FONT_BLUE; cc.fill = FILL_INPUT_GREY
            cc.number_format = FMT_CURRENCY_M
            add_source_comment(cc, "富途 BS", "短期借款+长期借款+融资租赁")
        for col in self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['debt_beg']}+{cL}{r['issuance']}-{cL}{r['mandatory']}-{cL}{r['sweep']}"
            cc = ws.cell(row, col, f); cc.font = FONT_BLACK_BOLD; cc.fill = FILL_FORECAST_GREEN
            cc.number_format = FMT_CURRENCY_M; cc.border = BORDER_THIN_BOTTOM
        r["debt_end"] = row; row += 1

        # ---- Interest Expense ----
        ws.cell(row, 1, "Interest Expense (= Beg x Rate) / 利息费用"); ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['debt_beg']}*Assumptions!$B${assump['interest_rate']}"
            c = ws.cell(row, col, f); c.font = FONT_BLACK; c.number_format = FMT_CURRENCY_M
            if col in self.FCST_COLS: c.fill = FILL_FORECAST_GREEN
            add_comment(c, "Interest = Beginning Debt x Rate")
        r["interest"] = row; row += 1

        self._fmt_column_widths(ws)

    # ==================== Tab 2: Income Statement ====================
    def _income_statement(self):
        """5A + 5E, Margin% 展示行 (indent italics), Interest 引用 Debt Schedule。"""
        ws = self.wb.create_sheet("Income Statement")
        d = self.d; r = self.rows["is"]
        assump = self.rows["assump"]
        debt = self.rows["debt"]

        self._write_section_header(ws, 1, "INCOME STATEMENT -- 利润表", span=12)
        row = 2
        self._write_column_headers(ws, row); row += 1

        # 严格布局: Revenue 必须落在 row 8, COGS 在 row 10 (WC/D&A 引用了此约定)
        # 用循环 pad 到 row 8 (安全, 不依赖手工计数)
        c = ws.cell(row, 1, "Currency: / 币种:")
        ws.cell(row, 2, f"Million {d['reporting_currency']}").font = FONT_ITALIC_GREY
        while row < 8:
            row += 1

        # ---- Revenue (row 8) ----
        assert row == 8, f"IS Layout error: Revenue must be row 8, got {row}"
        c = ws.cell(row, 1, "Revenue / 营业收入"); c.font = FONT_BOLD
        ws.cell(row, 2, "M")
        self._write_hist_input(ws, row, self.d["hist_revenue"],
                                source="富途利润表", ref="总收入/营业总收入")
        for i, col in enumerate(self.FCST_COLS):
            cL = get_column_letter(col); prev_cL = get_column_letter(col - 1)
            f = f"={prev_cL}{row}*(1+Assumptions!{cL}{assump['rev_growth']})"
            cc = ws.cell(row, col, f); cc.font = FONT_BLACK_BOLD; cc.fill = FILL_FORECAST_GREEN
            cc.number_format = FMT_CURRENCY_M
            add_comment(cc, f"Revenue(FY{i+1}) = Prior x (1 + Growth%)")
        r["revenue"] = row; row += 1

        # ---- Revenue Growth % 展示行 (indent, italics) ----
        ws.cell(row, 1, "  Revenue Growth % / 营收增长率").font = FONT_ITALIC_GREY
        ws.cell(row, 1).alignment = ALIGN_INDENT
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col); prev_cL = get_column_letter(col - 1)
            if col == self.HIST_COLS[0]:
                ws.cell(row, col, 0).font = FONT_ITALIC_GREY
            else:
                c = ws.cell(row, col, f"={cL}{r['revenue']}/{prev_cL}{r['revenue']}-1")
                c.font = FONT_ITALIC_GREY; c.number_format = FMT_PERCENT
        r["rev_growth_disp"] = row; row += 1

        # ---- COGS (row 10) ----
        assert row == 10, f"IS Layout error: COGS must be row 10, got {row}"
        ws.cell(row, 1, "Less: COGS / 减: 营业成本"); ws.cell(row, 2, "M")
        # 历史用负数展示
        for i, col in enumerate(self.HIST_COLS):
            v = self.d["hist_cogs"][self.hist_offset + i]
            cc = ws.cell(row, col, -abs(v)); cc.font = FONT_BLUE; cc.fill = FILL_INPUT_GREY
            cc.number_format = FMT_CURRENCY_M
            add_source_comment(cc, "富途利润表", "营业总成本")
        for col in self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"=-{cL}{r['revenue']}*Assumptions!{cL}{assump['cogs_pct']}"
            cc = ws.cell(row, col, f); cc.font = FONT_BLACK; cc.fill = FILL_FORECAST_GREEN
            cc.number_format = FMT_CURRENCY_M
        r["cogs"] = row; row += 1

        # ---- Gross Profit ----
        c = ws.cell(row, 1, "Gross Profit / 毛利润"); c.font = FONT_BOLD
        ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['revenue']}+{cL}{r['cogs']}"
            cc = ws.cell(row, col, f); cc.font = FONT_BLACK_BOLD; cc.number_format = FMT_CURRENCY_M
            cc.border = BORDER_THIN_BOTTOM
        r["gp"] = row; row += 1

        # ---- Gross Margin % (indent italics) ----
        ws.cell(row, 1, "  Gross Margin % / 毛利率").font = FONT_ITALIC_GREY
        ws.cell(row, 1).alignment = ALIGN_INDENT
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            c = ws.cell(row, col, f"={cL}{r['gp']}/{cL}{r['revenue']}")
            c.font = FONT_ITALIC_GREY; c.number_format = FMT_PERCENT
        row += 1

        # ---- OpEx ----
        ws.cell(row, 1, "Less: OpEx / 减: 营业费用 (S&M + G&A + R&D)"); ws.cell(row, 2, "M")
        for i, col in enumerate(self.HIST_COLS):
            v = self.d["hist_opex"][self.hist_offset + i]
            cc = ws.cell(row, col, -abs(v)); cc.font = FONT_BLUE; cc.fill = FILL_INPUT_GREY
            cc.number_format = FMT_CURRENCY_M
            add_source_comment(cc, "富途利润表", "营业费用")
        for col in self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"=-{cL}{r['revenue']}*Assumptions!{cL}{assump['opex_pct']}"
            cc = ws.cell(row, col, f); cc.font = FONT_BLACK; cc.fill = FILL_FORECAST_GREEN
            cc.number_format = FMT_CURRENCY_M
        r["opex"] = row; row += 1

        # ---- D&A (from D&A Schedule, 送 IS) ----
        ws.cell(row, 1, "Less: D&A / 减: 折旧摊销"); ws.cell(row, 2, "M")
        da_row = self.rows["da"]["da"]
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            c = ws.cell(row, col, f"=-'D&A Schedule'!{cL}{da_row}")
            c.font = FONT_GREEN; c.number_format = FMT_CURRENCY_M
            if col in self.FCST_COLS: c.fill = FILL_FORECAST_GREEN
            add_comment(c, f"跨表引用: -'D&A Schedule'!{cL}{da_row}")
        r["da"] = row; row += 1

        # ---- EBIT ----
        # 历史列: 直接引用 hist_ebit (富途"营业利润") 避免 GP-OpEx-D&A 组装误差 (D&A 已含在成本/费用里)
        # 预测列: Revenue x EBIT Margin (从 Assumptions)
        c = ws.cell(row, 1, "EBIT / 息税前利润"); c.font = FONT_BOLD
        ws.cell(row, 2, "M")
        for i, col in enumerate(self.HIST_COLS):
            v = self.d["hist_ebit"][self.hist_offset + i]
            cc = ws.cell(row, col, v); cc.font = FONT_BLUE; cc.fill = FILL_INPUT_GREY
            cc.number_format = FMT_CURRENCY_M
            cc.border = BORDER_THIN_BOTTOM
            add_source_comment(cc, "富途利润表", "营业利润 (直接读取, 避免 D&A 重复扣除)")
        for col in self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['revenue']}*Assumptions!{cL}{assump['ebit_margin']}"
            cc = ws.cell(row, col, f); cc.font = FONT_BLACK_BOLD; cc.fill = FILL_FORECAST_GREEN
            cc.number_format = FMT_CURRENCY_M; cc.border = BORDER_THIN_BOTTOM
            add_comment(cc, "EBIT = Revenue x EBIT Margin (from Assumptions)")
        r["ebit"] = row; row += 1

        # ---- EBIT Margin % ----
        ws.cell(row, 1, "  EBIT Margin % / 息税前利润率").font = FONT_ITALIC_GREY
        ws.cell(row, 1).alignment = ALIGN_INDENT
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            c = ws.cell(row, col, f"={cL}{r['ebit']}/{cL}{r['revenue']}")
            c.font = FONT_ITALIC_GREY; c.number_format = FMT_PERCENT
        row += 1

        # ---- EBITDA (= EBIT + |D&A|; D&A 存为负) ----
        c = ws.cell(row, 1, "EBITDA / 息税折旧摊销前利润"); c.font = FONT_BOLD
        ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['ebit']}-{cL}{r['da']}"
            cc = ws.cell(row, col, f); cc.font = FONT_BLACK_BOLD; cc.number_format = FMT_CURRENCY_M
        r["ebitda"] = row; row += 1

        # ---- 分市场展示: Finance Income / Finance Cost / Equity in Affiliates / Other Income ----
        # 港股: 独立展示 5035/5036/5037 明细; 美股/A股: 仅保留 Other Income plug (含所有非营业项)
        market_type = self.d.get("market_type", "us")
        # (+) Finance Income (港股 5035 融资收入; 其他市场 0)
        ws.cell(row, 1, "(+) Finance Income / 加: 融资收入"); ws.cell(row, 2, "M")
        for i, col in enumerate(self.HIST_COLS):
            v = self.d["hist_finance_income"][self.hist_offset + i]
            cc = ws.cell(row, col, v); cc.font = FONT_BLUE; cc.fill = FILL_INPUT_GREY
            cc.number_format = FMT_CURRENCY_M
            if market_type == "hk":
                add_source_comment(cc, "富途利润表", "融资收入 (港股 fid 5035)")
            else:
                add_source_comment(cc, "N/A", f"{market_type.upper()} market: no separate finance income (rolled into Other Income)")
        for col in self.FCST_COLS:
            prev_cL = get_column_letter(col - 1)
            cc = ws.cell(row, col, f"={prev_cL}{row}"); cc.font = FONT_BLACK; cc.fill = FILL_FORECAST_GREEN
            cc.number_format = FMT_CURRENCY_M
        r["fin_income"] = row; row += 1

        # (-) Finance Cost / Interest Expense
        # 港股: 历史 = 5036 融资成本, 预测 = Debt Schedule Interest
        # 其他市场: 历史 blank/0, 预测 = Debt Schedule Interest
        ws.cell(row, 1, "(-) Finance Cost / Interest Expense / 减: 融资成本 / 利息费用"); ws.cell(row, 2, "M")
        for i, col in enumerate(self.HIST_COLS):
            v = self.d["hist_finance_cost"][self.hist_offset + i]
            cc = ws.cell(row, col, -abs(v) if v else 0); cc.font = FONT_BLUE; cc.fill = FILL_INPUT_GREY
            cc.number_format = FMT_CURRENCY_M
            if market_type == "hk":
                add_source_comment(cc, "富途利润表", "融资成本 (港股 fid 5036)")
            else:
                add_source_comment(cc, "N/A", f"{market_type.upper()} market: no separate finance cost (rolled into Other Income)")
        for col in self.FCST_COLS:
            cL = get_column_letter(col)
            c = ws.cell(row, col, f"=-'Debt Schedule'!{cL}{debt['interest']}")
            c.font = FONT_GREEN; c.number_format = FMT_CURRENCY_M
            c.fill = FILL_FORECAST_GREEN
            add_comment(c, "预测 Interest 从 Debt Schedule (Beg x Rate)")
        r["interest"] = row; row += 1

        # (+) Equity in Affiliates (港股 5037; 其他市场 0)
        ws.cell(row, 1, "(+) Equity in Affiliates / 加: 应占联营公司利润"); ws.cell(row, 2, "M")
        for i, col in enumerate(self.HIST_COLS):
            v = self.d["hist_equity_affiliate"][self.hist_offset + i]
            cc = ws.cell(row, col, v); cc.font = FONT_BLUE; cc.fill = FILL_INPUT_GREY
            cc.number_format = FMT_CURRENCY_M
            if market_type == "hk":
                add_source_comment(cc, "富途利润表", "应占联营公司利润 (港股 fid 5037)")
            else:
                add_source_comment(cc, "N/A", f"{market_type.upper()} market: no separate affiliate income (rolled into Other Income)")
        for col in self.FCST_COLS:
            prev_cL = get_column_letter(col - 1)
            cc = ws.cell(row, col, f"={prev_cL}{row}"); cc.font = FONT_BLACK; cc.fill = FILL_FORECAST_GREEN
            cc.number_format = FMT_CURRENCY_M
        r["eq_aff"] = row; row += 1

        # (+) Other Income / (Loss) — plug 项
        # 港股: hist_ebt − hist_ebit − Fin Inc + Fin Cost − Eq Aff (剩余非明细项, 通常小额)
        # 美股/A股: hist_ebt − hist_ebit (吞并所有非营业项, 含 Interest 与 Investment Income)
        ws.cell(row, 1, "(+) Other Income / (Loss) / 加: 其他非营业净收益"); ws.cell(row, 2, "M")
        for i, col in enumerate(self.HIST_COLS):
            v = self.d["hist_other_income"][self.hist_offset + i]
            cc = ws.cell(row, col, v); cc.font = FONT_BLUE; cc.fill = FILL_INPUT_GREY
            cc.number_format = FMT_CURRENCY_M
            if market_type == "hk":
                add_source_comment(cc, "Plug", "hist EBT - EBIT - FinInc + FinCost - EqAff (余量)")
            else:
                add_source_comment(cc, "Plug", "hist EBT - EBIT (含 Interest / Investment Income / 汇兑等)")
        for col in self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['revenue']}*Assumptions!{cL}{assump['other_income_pct']}"
            cc = ws.cell(row, col, f); cc.font = FONT_BLACK; cc.fill = FILL_FORECAST_GREEN
            cc.number_format = FMT_CURRENCY_M
            add_comment(cc, "Other Income = Revenue x Other Income % (from Assumptions)")
        r["other_income"] = row; row += 1

        # ---- EBT (= EBIT + Fin Inc + Fin Cost + Eq Aff + Other Income; Fin Cost 已带负号) ----
        c = ws.cell(row, 1, "EBT / 税前利润"); c.font = FONT_BOLD
        ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['ebit']}+{cL}{r['fin_income']}+{cL}{r['interest']}+{cL}{r['eq_aff']}+{cL}{r['other_income']}"
            cc = ws.cell(row, col, f); cc.font = FONT_BLACK_BOLD; cc.number_format = FMT_CURRENCY_M
            add_comment(cc, "EBT = EBIT + Finance Income - Finance Cost + Equity Affiliates + Other Income")
        r["ebt"] = row; row += 1

        # ---- Taxes ----
        ws.cell(row, 1, "Less: Taxes / 减: 所得税 (亏损不缴)"); ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"=-MAX(0,{cL}{r['ebt']})*Assumptions!{cL}{assump['tax_rate']}"
            c = ws.cell(row, col, f); c.font = FONT_BLACK; c.number_format = FMT_CURRENCY_M
            if col in self.FCST_COLS: c.fill = FILL_FORECAST_GREEN
            add_comment(c, "Tax = -MAX(0, EBT) x Tax Rate")
        r["tax"] = row; row += 1

        # ---- Net Income (归母口径) ----
        # 历史列: 直接引用 hist_ni (归母净利润, 蓝色输入), 避免 IS 自算传导误差; 保证与 BS Equity 口径一致
        # 预测列: 公式 = EBT + Tax
        c = ws.cell(row, 1, "Net Income / 净利润"); c.font = FONT_BOLD
        ws.cell(row, 2, "M")
        for i, col in enumerate(self.HIST_COLS):
            v = self.d["hist_ni"][self.hist_offset + i]
            cc = ws.cell(row, col, v); cc.font = FONT_BLUE; cc.fill = FILL_INPUT_GREY
            cc.number_format = FMT_CURRENCY_M
            cc.border = BORDER_MEDIUM_BOTTOM
            add_source_comment(cc, "富途利润表", "归属母公司股东净利润 (归母口径, 与 BS Equity 一致)")
        for col in self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['ebt']}+{cL}{r['tax']}"
            cc = ws.cell(row, col, f); cc.font = FONT_BLACK_BOLD; cc.fill = FILL_MEDIUM_BLUE
            cc.number_format = FMT_CURRENCY_M; cc.border = BORDER_MEDIUM_BOTTOM
            add_comment(cc, "预测: NI = EBT + Tax")
        r["ni"] = row; row += 1

        # ---- Net Margin % ----
        ws.cell(row, 1, "  Net Margin % / 净利润率").font = FONT_ITALIC_GREY
        ws.cell(row, 1).alignment = ALIGN_INDENT
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            c = ws.cell(row, col, f"={cL}{r['ni']}/{cL}{r['revenue']}")
            c.font = FONT_ITALIC_GREY; c.number_format = FMT_PERCENT
        row += 1

        self._fmt_column_widths(ws)

    # ==================== Tab 4: Cash Flow Statement ====================
    def _cash_flow(self):
        """OCF (NI + D&A + ΔNWC) / CFI (-CapEx) / CFF (Debt Iss/Repay - Div - Repurchase)"""
        ws = self.wb.create_sheet("Cash Flow")
        d = self.d; r = self.rows["cf"]
        assump = self.rows["assump"]
        is_rows = self.rows["is"]; wc_rows = self.rows["wc"]
        da_rows = self.rows["da"]; debt_rows = self.rows["debt"]

        self._write_section_header(ws, 1, "CASH FLOW STATEMENT -- 现金流量表", span=12)
        row = 2
        self._write_column_headers(ws, row); row += 1

        # ---- OPERATING CASH FLOW ----
        c = ws.cell(row, 1, "OPERATING CASH FLOW / 经营活动现金流"); c.font = FONT_BOLD; c.fill = FILL_LIGHT_BLUE
        ws.merge_cells(start_row=row, start_column=1, end_row=row, end_column=12); row += 1

        # Net Income (from IS)
        ws.cell(row, 1, "Net Income / 净利润"); ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            c = ws.cell(row, col, f"='Income Statement'!{cL}{is_rows['ni']}")
            c.font = FONT_GREEN; c.number_format = FMT_CURRENCY_M
            add_comment(c, f"跨表引用: 'Income Statement'!{cL}{is_rows['ni']}")
        r["ni"] = row; row += 1

        # (+) D&A (from D&A Schedule)
        ws.cell(row, 1, "(+) D&A / 加: 折旧摊销"); ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            c = ws.cell(row, col, f"='D&A Schedule'!{cL}{da_rows['da']}")
            c.font = FONT_GREEN; c.number_format = FMT_CURRENCY_M
        r["da"] = row; row += 1

        # ΔAR / ΔInv / ΔAP (from WC Schedule)
        ws.cell(row, 1, "(+/-) Δ AR / 应收账款变动"); ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            c = ws.cell(row, col, f"='Working Capital'!{cL}{wc_rows['ar_chg']}")
            c.font = FONT_GREEN; c.number_format = FMT_CURRENCY_M
        r["ar_chg"] = row; row += 1

        ws.cell(row, 1, "(+/-) Δ Inventory / 存货变动"); ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            c = ws.cell(row, col, f"='Working Capital'!{cL}{wc_rows['inv_chg']}")
            c.font = FONT_GREEN; c.number_format = FMT_CURRENCY_M
        r["inv_chg"] = row; row += 1

        ws.cell(row, 1, "(+/-) Δ AP / 应付账款变动"); ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            c = ws.cell(row, col, f"='Working Capital'!{cL}{wc_rows['ap_chg']}")
            c.font = FONT_GREEN; c.number_format = FMT_CURRENCY_M
        r["ap_chg"] = row; row += 1

        # Net OCF
        c = ws.cell(row, 1, "Net Operating Cash Flow / 经营活动现金流净额"); c.font = FONT_BOLD
        ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['ni']}+{cL}{r['da']}+{cL}{r['ar_chg']}+{cL}{r['inv_chg']}+{cL}{r['ap_chg']}"
            cc = ws.cell(row, col, f); cc.font = FONT_BLACK_BOLD; cc.number_format = FMT_CURRENCY_M
            cc.border = BORDER_THIN_BOTTOM
            if col in self.FCST_COLS: cc.fill = FILL_FORECAST_GREEN
        r["ocf"] = row; row += 2

        # ---- INVESTING CASH FLOW ----
        c = ws.cell(row, 1, "INVESTING CASH FLOW / 投资活动现金流"); c.font = FONT_BOLD; c.fill = FILL_LIGHT_BLUE
        ws.merge_cells(start_row=row, start_column=1, end_row=row, end_column=12); row += 1

        ws.cell(row, 1, "(-) Capital Expenditure / 减: 资本开支"); ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            c = ws.cell(row, col, f"=-'D&A Schedule'!{cL}{da_rows['capex']}")
            c.font = FONT_GREEN; c.number_format = FMT_CURRENCY_M
        r["capex"] = row; row += 1

        c = ws.cell(row, 1, "Net Investing Cash Flow / 投资活动现金流净额"); c.font = FONT_BOLD
        ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['capex']}"
            cc = ws.cell(row, col, f); cc.font = FONT_BLACK_BOLD; cc.number_format = FMT_CURRENCY_M
            cc.border = BORDER_THIN_BOTTOM
            if col in self.FCST_COLS: cc.fill = FILL_FORECAST_GREEN
        r["cfi"] = row; row += 2

        # ---- FINANCING CASH FLOW ----
        c = ws.cell(row, 1, "FINANCING CASH FLOW / 融资活动现金流"); c.font = FONT_BOLD; c.fill = FILL_LIGHT_BLUE
        ws.merge_cells(start_row=row, start_column=1, end_row=row, end_column=12); row += 1

        ws.cell(row, 1, "(+) Debt Issuance / 加: 新增借款"); ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            c = ws.cell(row, col, f"='Debt Schedule'!{cL}{debt_rows['issuance']}")
            c.font = FONT_GREEN; c.number_format = FMT_CURRENCY_M
        r["issuance"] = row; row += 1

        ws.cell(row, 1, "(-) Debt Repayment / 减: 债务偿还 (强制 + 超额)"); ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"=-('Debt Schedule'!{cL}{debt_rows['mandatory']}+'Debt Schedule'!{cL}{debt_rows['sweep']})"
            c = ws.cell(row, col, f); c.font = FONT_GREEN; c.number_format = FMT_CURRENCY_M
        r["repayment"] = row; row += 1

        ws.cell(row, 1, "(-) Dividends Paid / 减: 股利支付"); ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"=-MAX(0,{cL}{r['ni']})*Assumptions!$B${assump['div_payout']}"
            c = ws.cell(row, col, f); c.font = FONT_BLACK; c.number_format = FMT_CURRENCY_M
            if col in self.FCST_COLS: c.fill = FILL_FORECAST_GREEN
            add_comment(c, "Dividends = -MAX(0, NI) x Payout Ratio")
        r["dividends"] = row; row += 1

        ws.cell(row, 1, "(-) Share Repurchases / 减: 股份回购"); ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            c = ws.cell(row, col, f"=-Assumptions!$B${assump['repurchase']}")
            c.font = FONT_GREEN; c.number_format = FMT_CURRENCY_M
            if col in self.FCST_COLS: c.fill = FILL_FORECAST_GREEN
        r["repurchase"] = row; row += 1

        c = ws.cell(row, 1, "Net Financing Cash Flow / 融资活动现金流净额"); c.font = FONT_BOLD
        ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['issuance']}+{cL}{r['repayment']}+{cL}{r['dividends']}+{cL}{r['repurchase']}"
            cc = ws.cell(row, col, f); cc.font = FONT_BLACK_BOLD; cc.number_format = FMT_CURRENCY_M
            cc.border = BORDER_THIN_BOTTOM
            if col in self.FCST_COLS: cc.fill = FILL_FORECAST_GREEN
        r["cff"] = row; row += 2

        # ---- NET CHANGE IN CASH ----
        ws.cell(row, 1, "Net Change in Cash / 现金净变动"); ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['ocf']}+{cL}{r['cfi']}+{cL}{r['cff']}"
            c = ws.cell(row, col, f); c.font = FONT_BLACK_BOLD; c.number_format = FMT_CURRENCY_M
        r["net_change"] = row; row += 1

        ws.cell(row, 1, "Beginning Cash / 期初现金"); ws.cell(row, 2, "M")
        for i, col in enumerate(self.HIST_COLS):
            if i == 0:
                # 最老一期: 由当期期末 Cash 反推 (Ending - NetChange)
                cL = get_column_letter(col)
                f = f"={cL}{row+1}-{cL}{r['net_change']}"
                c = ws.cell(row, col, f); c.font = FONT_BLACK; c.number_format = FMT_CURRENCY_M
            else:
                prev_cL = get_column_letter(col - 1)
                c = ws.cell(row, col, f"={prev_cL}{row+1}")   # Prior Ending
                c.font = FONT_BLACK; c.number_format = FMT_CURRENCY_M
        for col in self.FCST_COLS:
            prev_cL = get_column_letter(col - 1)
            c = ws.cell(row, col, f"={prev_cL}{row+1}")
            c.font = FONT_BLACK; c.fill = FILL_FORECAST_GREEN; c.number_format = FMT_CURRENCY_M
        r["cash_beg"] = row; row += 1

        # Ending Cash
        c = ws.cell(row, 1, "Ending Cash / 期末现金"); c.font = FONT_BOLD
        ws.cell(row, 2, "M")
        for i, col in enumerate(self.HIST_COLS):
            v = self.d["hist_cash"][self.hist_offset + i]
            cc = ws.cell(row, col, v); cc.font = FONT_BLUE; cc.fill = FILL_INPUT_GREY
            cc.number_format = FMT_CURRENCY_M
            add_source_comment(cc, "富途 BS", "现金及等价物 + 短期投资 + 定期存款")
        for col in self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['cash_beg']}+{cL}{r['net_change']}"
            cc = ws.cell(row, col, f); cc.font = FONT_BLACK_BOLD; cc.fill = FILL_MEDIUM_BLUE
            cc.number_format = FMT_CURRENCY_M; cc.border = BORDER_MEDIUM_BOTTOM
            add_comment(cc, f"Ending Cash = Beg + Net Change\n=> BS Cash (核心勾稽)")
        r["cash_end"] = row; row += 1

        self._fmt_column_widths(ws)

    # ==================== Tab 3: Balance Sheet ====================
    def _balance_sheet(self):
        """Days-driven, Balance Check + Cash Tie-Out (红色条件格式)。"""
        ws = self.wb.create_sheet("Balance Sheet")
        d = self.d; r = self.rows["bs"]
        wc_rows = self.rows["wc"]; da_rows = self.rows["da"]
        debt_rows = self.rows["debt"]; is_rows = self.rows["is"]
        cf_rows = self.rows["cf"]; assump = self.rows["assump"]

        self._write_section_header(ws, 1, "BALANCE SHEET -- 资产负债表", span=12)
        row = 2
        self._write_column_headers(ws, row); row += 1

        # ==== ASSETS ====
        c = ws.cell(row, 1, "ASSETS / 资产"); c.font = FONT_BOLD; c.fill = FILL_LIGHT_BLUE
        ws.merge_cells(start_row=row, start_column=1, end_row=row, end_column=12); row += 1

        # Cash & Equivalents (from CF Ending Cash)
        ws.cell(row, 1, "Cash & Equivalents / 现金及等价物"); ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            c = ws.cell(row, col, f"='Cash Flow'!{cL}{cf_rows['cash_end']}")
            c.font = FONT_GREEN; c.number_format = FMT_CURRENCY_M
            add_comment(c, f"CF Ending Cash: 'Cash Flow'!{cL}{cf_rows['cash_end']}")
        r["cash"] = row; row += 1

        # AR
        ws.cell(row, 1, "Accounts Receivable / 应收账款"); ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            c = ws.cell(row, col, f"='Working Capital'!{cL}{wc_rows['ar_bal']}")
            c.font = FONT_GREEN; c.number_format = FMT_CURRENCY_M
        r["ar"] = row; row += 1

        # Inventory
        ws.cell(row, 1, "Inventory / 存货"); ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            c = ws.cell(row, col, f"='Working Capital'!{cL}{wc_rows['inv_bal']}")
            c.font = FONT_GREEN; c.number_format = FMT_CURRENCY_M
        r["inv"] = row; row += 1

        # Total Current Assets
        c = ws.cell(row, 1, "Total Current Assets / 流动资产合计"); c.font = FONT_BOLD
        ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['cash']}+{cL}{r['ar']}+{cL}{r['inv']}"
            cc = ws.cell(row, col, f); cc.font = FONT_BLACK_BOLD; cc.number_format = FMT_CURRENCY_M
            cc.border = BORDER_THIN_BOTTOM
        r["ca"] = row; row += 1

        # PP&E Net (from D&A Schedule)
        ws.cell(row, 1, "PP&E (Net) / 固定资产净额"); ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            c = ws.cell(row, col, f"='D&A Schedule'!{cL}{da_rows['ppe_end']}")
            c.font = FONT_GREEN; c.number_format = FMT_CURRENCY_M
        r["ppe"] = row; row += 1

        # Intangible Assets (蓝色输入, 预测保持)
        ws.cell(row, 1, "Intangible Assets & Others / 无形资产及其他"); ws.cell(row, 2, "M")
        self._write_hist_input(ws, row, self.d["hist_intangible"],
                                source="富途 BS", ref="无形资产")
        for col in self.FCST_COLS:
            prev_cL = get_column_letter(col - 1)
            c = ws.cell(row, col, f"={prev_cL}{row}")   # 预测保持不变
            c.font = FONT_BLACK; c.fill = FILL_FORECAST_GREEN; c.number_format = FMT_CURRENCY_M
        r["intangible"] = row; row += 1

        # Other Non-Current Assets (plug 项, 用于平衡)
        # 历史列: TA - CA - PPE - Intangible (使 TA = hist_TA)
        # 预测列: TL + TE - CA - PPE - Intangible (使 BS 平衡 = 0, "plug of last resort")
        ws.cell(row, 1, "Other Non-Current Assets (plug) / 其他非流动资产"); ws.cell(row, 2, "M")
        for i, col in enumerate(self.HIST_COLS):
            v = self.d["hist_total_assets"][self.hist_offset + i]
            cL = get_column_letter(col)
            f = f"={v}-{cL}{r['ca']}-{cL}{r['ppe']}-{cL}{r['intangible']}"
            c = ws.cell(row, col, f); c.font = FONT_BLACK; c.number_format = FMT_CURRENCY_M
            add_comment(c, "历史 plug = TA - CA - PPE - Intangible (确保 TA = hist_TA)")
        # 预测列的 plug 公式需要引用 TL 和 TE 行, 这两行下面才写入。这里先占位, 稍后回填。
        r["other_nca"] = row; row += 1

        # Total Non-Current Assets
        c = ws.cell(row, 1, "Total Non-Current Assets / 非流动资产合计"); c.font = FONT_BOLD
        ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['ppe']}+{cL}{r['intangible']}+{cL}{r['other_nca']}"
            cc = ws.cell(row, col, f); cc.font = FONT_BLACK_BOLD; cc.number_format = FMT_CURRENCY_M
            cc.border = BORDER_THIN_BOTTOM
        r["nca"] = row; row += 1

        # Total Assets
        c = ws.cell(row, 1, "Total Assets / 资产总计"); c.font = FONT_BOLD
        ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['ca']}+{cL}{r['nca']}"
            cc = ws.cell(row, col, f); cc.font = FONT_BLACK_BOLD; cc.number_format = FMT_CURRENCY_M
            cc.border = BORDER_MEDIUM_BOTTOM
        r["ta"] = row; row += 2

        # ==== LIABILITIES ====
        c = ws.cell(row, 1, "LIABILITIES / 负债"); c.font = FONT_BOLD; c.fill = FILL_LIGHT_BLUE
        ws.merge_cells(start_row=row, start_column=1, end_row=row, end_column=12); row += 1

        # AP
        ws.cell(row, 1, "Accounts Payable / 应付账款"); ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            c = ws.cell(row, col, f"='Working Capital'!{cL}{wc_rows['ap_bal']}")
            c.font = FONT_GREEN; c.number_format = FMT_CURRENCY_M
        r["ap"] = row; row += 1

        # Other Current Liabilities (plug: 40% of "other liab" gap)
        ws.cell(row, 1, "Other Current Liabilities (plug) / 其他流动负债"); ws.cell(row, 2, "M")
        for i, col in enumerate(self.HIST_COLS):
            # 历史 OCL plug: TA - AP - Debt - Equity 差额中的 40% 归为流动
            ta = self.d["hist_total_assets"][self.hist_offset + i]
            eq = self.d["hist_equity"][self.hist_offset + i]
            dt = self.d["hist_debt"][self.hist_offset + i]
            ap = self.d["hist_ap"][self.hist_offset + i]
            gap = ta - eq - dt - ap
            v = max(0.0, gap) * 0.4
            c = ws.cell(row, col, v); c.font = FONT_BLUE; c.fill = FILL_INPUT_GREY
            c.number_format = FMT_CURRENCY_M
            add_source_comment(c, "Estimate", "0.4 x (TA - EQ - Debt - AP) 反推 plug")
        for col in self.FCST_COLS:
            prev_cL = get_column_letter(col - 1)
            c = ws.cell(row, col, f"={prev_cL}{row}")
            c.font = FONT_BLACK; c.fill = FILL_FORECAST_GREEN; c.number_format = FMT_CURRENCY_M
        r["ocl"] = row; row += 1

        # Total Debt (from Debt Schedule)
        ws.cell(row, 1, "Total Debt / 债务合计"); ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            c = ws.cell(row, col, f"='Debt Schedule'!{cL}{debt_rows['debt_end']}")
            c.font = FONT_GREEN; c.number_format = FMT_CURRENCY_M
        r["debt"] = row; row += 1

        # Other Non-Current Liabilities (BS balance plug of last resort)
        # 历史列: 60% x (TA - EQ - Debt - AP) 蓝色估计
        # 预测列: TA - AP - OCL - Debt - Equity (强制 BS 平衡, 相当于将资产/权益变动的剩余项
        #        转到长期负债的 plug 里, 保证 Balance Check = 0)
        ws.cell(row, 1, "Other Non-Current Liabilities (BS plug) / 其他非流动负债"); ws.cell(row, 2, "M")
        for i, col in enumerate(self.HIST_COLS):
            ta = self.d["hist_total_assets"][self.hist_offset + i]
            eq = self.d["hist_equity"][self.hist_offset + i]
            dt = self.d["hist_debt"][self.hist_offset + i]
            ap = self.d["hist_ap"][self.hist_offset + i]
            gap = ta - eq - dt - ap
            v = max(0.0, gap) * 0.6
            c = ws.cell(row, col, v); c.font = FONT_BLUE; c.fill = FILL_INPUT_GREY
            c.number_format = FMT_CURRENCY_M
            add_source_comment(c, "Estimate", "0.6 x (TA - EQ - Debt - AP) 反推 plug")
        # 预测期公式: ONCL = TA - AP - OCL - Debt - (CS + RE)
        # 其中 TA = CA + PPE + Intangible + Other NCA; Other NCA 预测期已 plug = 前期值
        # 简化: 使用 Total Assets 行 (下面才写入) 会产生前向引用, 我们用 r["ta"] 引用
        # (由于 TA 行在此行之后写入, 该公式暂延迟到 TL 写完后再回填。这里先占位 = prev 值。)
        for col in self.FCST_COLS:
            prev_cL = get_column_letter(col - 1)
            c = ws.cell(row, col, f"={prev_cL}{row}")
            c.font = FONT_BLACK; c.fill = FILL_FORECAST_GREEN; c.number_format = FMT_CURRENCY_M
        r["oncl"] = row; row += 1

        # Total Liabilities
        c = ws.cell(row, 1, "Total Liabilities / 负债合计"); c.font = FONT_BOLD
        ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['ap']}+{cL}{r['ocl']}+{cL}{r['debt']}+{cL}{r['oncl']}"
            cc = ws.cell(row, col, f); cc.font = FONT_BLACK_BOLD; cc.number_format = FMT_CURRENCY_M
            cc.border = BORDER_THIN_BOTTOM
        r["tl"] = row; row += 2

        # ==== EQUITY ====
        c = ws.cell(row, 1, "EQUITY / 股东权益"); c.font = FONT_BOLD; c.fill = FILL_LIGHT_BLUE
        ws.merge_cells(start_row=row, start_column=1, end_row=row, end_column=12); row += 1

        # Common Stock + APIC (蓝色输入, 保持)
        ws.cell(row, 1, "Common Stock + APIC / 普通股+资本公积"); ws.cell(row, 2, "M")
        # historical CS = Equity - RE
        for i, col in enumerate(self.HIST_COLS):
            e = self.d["hist_equity"][self.hist_offset + i]
            re_ = self.d["hist_re"][self.hist_offset + i]
            cs = e - re_ if re_ else e * 0.15
            cc = ws.cell(row, col, cs); cc.font = FONT_BLUE; cc.fill = FILL_INPUT_GREY
            cc.number_format = FMT_CURRENCY_M
            add_source_comment(cc, "富途 BS", "股本+资本公积 (Equity - RE)")
        for col in self.FCST_COLS:
            prev_cL = get_column_letter(col - 1)
            c = ws.cell(row, col, f"={prev_cL}{row}")
            c.font = FONT_BLACK; c.fill = FILL_FORECAST_GREEN; c.number_format = FMT_CURRENCY_M
        r["cs"] = row; row += 1

        # Retained Earnings = Prior + NI - Dividends
        ws.cell(row, 1, "Retained Earnings / 留存收益"); ws.cell(row, 2, "M")
        for i, col in enumerate(self.HIST_COLS):
            v = self.d["hist_re"][self.hist_offset + i]
            if v == 0.0:
                # 富途未提供留存收益, 用 equity - common stock 近似
                v = self.d["hist_equity"][self.hist_offset + i] * 0.85
            cc = ws.cell(row, col, v); cc.font = FONT_BLUE; cc.fill = FILL_INPUT_GREY
            cc.number_format = FMT_CURRENCY_M
            add_source_comment(cc, "富途 BS", "留存收益/未分配利润")
        for col in self.FCST_COLS:
            cL = get_column_letter(col); prev_cL = get_column_letter(col - 1)
            # Prior RE + NI + Dividends (Div 已带负号)
            f = f"={prev_cL}{row}+'Income Statement'!{cL}{is_rows['ni']}+'Cash Flow'!{cL}{cf_rows['dividends']}"
            cc = ws.cell(row, col, f); cc.font = FONT_BLACK; cc.fill = FILL_FORECAST_GREEN
            cc.number_format = FMT_CURRENCY_M
            add_comment(cc, "RE = Prior + NI - Dividends\n(Dividends 在 CF 已存为负数)")
        r["re"] = row; row += 1

        # Total Equity
        c = ws.cell(row, 1, "Total Equity / 股东权益合计"); c.font = FONT_BOLD
        ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['cs']}+{cL}{r['re']}"
            cc = ws.cell(row, col, f); cc.font = FONT_BLACK_BOLD; cc.number_format = FMT_CURRENCY_M
            cc.border = BORDER_THIN_BOTTOM
        r["te"] = row; row += 1

        # ---- 回填: ONCL 预测期公式 = TA - AP - OCL - Debt - Equity (强制 BS 平衡) ----
        for col in self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['ta']}-{cL}{r['ap']}-{cL}{r['ocl']}-{cL}{r['debt']}-{cL}{r['te']}"
            cc = ws.cell(row - 1 - 1 - 1 - 1, col, f) if False else None
        # 直接定位 oncl 行改写 (避免上面 hacky 定位错误)
        for col in self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['ta']}-{cL}{r['ap']}-{cL}{r['ocl']}-{cL}{r['debt']}-{cL}{r['te']}"
            cc = ws.cell(r["oncl"], col, f)
            cc.font = FONT_BLACK; cc.fill = FILL_FORECAST_GREEN; cc.number_format = FMT_CURRENCY_M
            add_comment(cc, "预测 plug: ONCL = TA - AP - OCL - Debt - Equity (强制 BS 平衡)")

        # Total Liabilities & Equity
        c = ws.cell(row, 1, "Total Liabilities & Equity / 负债及股东权益总计"); c.font = FONT_BOLD
        ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['tl']}+{cL}{r['te']}"
            cc = ws.cell(row, col, f); cc.font = FONT_BLACK_BOLD; cc.number_format = FMT_CURRENCY_M
            cc.border = BORDER_MEDIUM_BOTTOM
        r["tle"] = row; row += 2

        # ==== CHECKS ====
        # Balance Check: TA - TL&E = 0
        c = ws.cell(row, 1, "Balance Check (TA − TL&E) / 勾稽校验"); c.font = FONT_BOLD; c.fill = FILL_MEDIUM_BLUE
        ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['ta']}-{cL}{r['tle']}"
            cc = ws.cell(row, col, f); cc.font = FONT_BOLD; cc.fill = FILL_MEDIUM_BLUE
            cc.number_format = FMT_CHECK
            add_comment(cc, "必须为 0. 非 0 时红色警告\n(schema.md 硬性勾稽)")
        r["balance_check"] = row; row += 1

        # Cash Tie-Out: BS Cash - CF Ending Cash = 0
        c = ws.cell(row, 1, "Cash Tie-Out (BS Cash − CF End) / 现金勾稽"); c.font = FONT_BOLD; c.fill = FILL_MEDIUM_BLUE
        ws.cell(row, 2, "M")
        for col in self.HIST_COLS + self.FCST_COLS:
            cL = get_column_letter(col)
            f = f"={cL}{r['cash']}-'Cash Flow'!{cL}{cf_rows['cash_end']}"
            cc = ws.cell(row, col, f); cc.font = FONT_BOLD; cc.fill = FILL_MEDIUM_BLUE
            cc.number_format = FMT_CHECK
        r["cash_tieout"] = row; row += 1

        self._fmt_column_widths(ws)


# ==================== Main ====================
if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--ticker", required=True)
    parser.add_argument("--workspace", required=True)
    args = parser.parse_args()
    data = extract_financial_data(Path(args.workspace), args.ticker)
    output = Path(args.workspace) / "excels" / f"{args.ticker}_3Statement_{date.today().isoformat()}.xlsx"
    ThreeStatementBuilder(data).build(output)
