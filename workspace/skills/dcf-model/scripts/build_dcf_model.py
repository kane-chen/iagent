#!/usr/bin/env python3
"""
生成包含实时公式的 DCF (Discounted Cash Flow) Excel 模型。
准确提取富途API字段，包含完整的 WACC 和 FCF 计算公式链。
参考 get_financials_statements.py 的调用方式获取股价和总股本，包含详尽的调试日志。
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
from typing import Optional, Tuple

# 确保能导入 futuapi 的 common 模块 (与 get_financials_statements.py 保持一致)
_script_dir = _os.path.dirname(_os.path.abspath(__file__))
_potential_paths = [
    _os.path.join(_script_dir, "..", "futuapi", "scripts"),
    _os.path.join(_script_dir, "..", "..", "futuapi", "scripts"),
    _os.path.join(_script_dir, "futuapi", "scripts")
]
for p in _potential_paths:
    if _os.path.isdir(p):
        _sys.path.insert(0, _os.path.normpath(p))
        break

try:
    from common import create_quote_context, check_ret, safe_close, is_empty
except ImportError:
    logging.error("无法导入 common 模块，请确保 futuapi/scripts 目录在 sys.path 中。")
    # 定义空函数以防脚本完全崩溃
    def create_quote_context(*args, **kwargs): raise ImportError("common module not found")
    def check_ret(ret, data, ctx, msg): pass
    def safe_close(ctx): pass
    def is_empty(data): return data is None or len(data) == 0

# 配置日志输出
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    datefmt='%H:%M:%S'
)
logger = logging.getLogger(__name__)

try:
    import openpyxl
    from openpyxl.styles import Font, PatternFill, Border, Side
    from openpyxl.utils import get_column_letter
    from openpyxl.comments import Comment
except ImportError as exc:
    raise ImportError("openpyxl required: pip install openpyxl") from exc

# ==================== Styles ====================
FONT_BLUE = Font(color="0000FF")
FONT_BLACK = Font(color="000000")
FONT_GREEN = Font(color="008000")
FONT_WHITE_BOLD = Font(color="FFFFFF", bold=True)
FONT_BOLD = Font(bold=True)
FILL_DARK_BLUE = PatternFill("solid", fgColor="1F4E79")
FILL_LIGHT_BLUE = PatternFill("solid", fgColor="D9E1F2")
FILL_MEDIUM_BLUE = PatternFill("solid", fgColor="BDD7EE")
FILL_INPUT_GREY = PatternFill("solid", fgColor="F2F2F2")
# 新增:预测列浅绿 & 估值汇总浅橙,提升区块辨识度
FILL_FORECAST_GREEN = PatternFill("solid", fgColor="E2F0D9")
FILL_VALUATION_ORANGE = PatternFill("solid", fgColor="FCE4D6")
FILL_VALUATION_ORANGE_DARK = PatternFill("solid", fgColor="F4B183")
BOTTOM_BORDER = Border(bottom=Side(style="thin", color="000000"))

# ==================== Comment helper ====================
_COMMENT_AUTHOR = "DCF Builder"

def add_comment(cell, text: str, width: int = 260, height: int = 90):
    """给单元格附加气泡备注,统一样式。"""
    c = Comment(text, _COMMENT_AUTHOR)
    c.width = width
    c.height = height
    cell.comment = c

# ==================== Utility ====================
def safe_divide(n, d): return n / d if d != 0 else 0.0

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

def _read_excel_map(file_path: Path) -> dict:
    fp, is_tmp = _ensure_shared_strings(file_path)
    try:
        wb = openpyxl.load_workbook(str(fp), read_only=True, data_only=True)
        sheet = wb.active
        data = {}
        header_row = 1
        for r in range(1, 5):
            vals = [sheet.cell(r, c).value for c in range(1, sheet.max_column + 1) if sheet.cell(r, c).value]
            if "FY" in " ".join(str(v) for v in vals): header_row = r; break
        periods = [str(sheet.cell(header_row, c).value) for c in range(3, sheet.max_column + 1)]
        for r in range(header_row + 1, sheet.max_row + 1):
            ind = sheet.cell(r, 1).value
            if not ind: continue
            ind = str(ind).strip()
            vals = {}
            for c, p in enumerate(periods):
                v = sheet.cell(r, c + 3).value
                num = 0.0
                if isinstance(v, (int, float)): num = float(v)
                elif isinstance(v, str) and v:
                    cl = v.replace(",", "").replace("-", "").strip()
                    if cl.replace(".", "", 1).isdigit(): num = float(cl) * (-1 if v.strip().startswith("-") else 1)
                vals[p] = num
            data[ind] = vals
        wb.close()
        return data
    finally:
        if is_tmp and fp.exists(): os.remove(str(fp))

def find_local_file(excels_path: Path, ticker: str, suffix: str) -> Optional[Path]:
    pattern = re.compile(rf'^.*_{re.escape(ticker)}_{re.escape(suffix)}_.*\.(xlsx|xls)$', re.IGNORECASE)
    files = [f for f in excels_path.iterdir() if f.is_file() and pattern.match(f.name)]
    return sorted(files)[-1] if files else None

def normalize_stock_code(ticker: str) -> str:
    """将 ticker 转为富途格式: BABA -> US.BABA, 00700 -> HK.00700, 600519 -> SH.600519"""
    ticker = ticker.strip().upper()
    if '.' in ticker and ticker.split('.')[0] in ('US', 'HK', 'SH', 'SZ'):
        return ticker
    if ticker.isdigit():
        n = int(ticker)
        if n >= 600000: return f"SH.{ticker}"
        elif n >= 300000: return f"SZ.{ticker}"
        else: return f"HK.{ticker.zfill(5)}"
    return f"US.{ticker}"

# ==================== Futu Market Data Fetching ====================
def fetch_market_data_from_futu(stock_code: str) -> dict:
    """
    参考 get_financials_statements.py 的调用方式，
    使用 common 模块的 create_quote_context, check_ret, safe_close 获取股票快照数据。
    """
    result = {"stock_price": None, "shares_outstanding": None, "shares_source": "Unknown"}
    ctx = None

    logger.info(f"正在通过 create_quote_context 连接 FutuOpenD 获取 {stock_code} 的数据...")

    try:
        # 1. 创建连接 (与 get_financials_statements.py 相同)
        ctx = create_quote_context()

        # 2. 调用 API 获取市场快照 (包含最新价格和总股本)
        logger.info(f"[API 调用] ctx.get_market_snapshot([{stock_code}])")
        ret, data = ctx.get_market_snapshot([stock_code])

        # 3. 检查返回值 (与 get_financials_statements.py 相同)
        check_ret(ret, data, ctx, "获取市场快照")

        if is_empty(data):
            logger.warning("get_market_snapshot 返回空数据，可能是股票代码错误或无权限")
        else:
            logger.debug(f"get_market_snapshot 返回列名: {data.columns.tolist()}")
            row = data.iloc[0]

            # 4. 提取价格
            if 'last_price' in data.columns and row['last_price'] is not None and float(row['last_price']) > 0:
                result["stock_price"] = float(row['last_price'])
                logger.info(f"  -> 成功获取价格 (last_price): {result['stock_price']}")
            else:
                logger.warning(f"  -> last_price 缺失或为 0，原始值: {row.get('last_price', 'N/A')}")

            # 5. 提取总股本
            if 'issued_shares' in data.columns and row['issued_shares'] is not None and float(row['issued_shares']) > 0:
                raw_shares = float(row['issued_shares'])
                result["shares_outstanding"] = raw_shares / 1_000_000  # 转换为百万股
                result["shares_source"] = "Futu get_market_snapshot (issued_shares)"
                logger.info(f"  -> 成功获取总股本 (issued_shares): {raw_shares:,.0f} (已转换为 {result['shares_outstanding']:,.2f}M)")
            else:
                logger.warning(f"  -> issued_shares 缺失或为 0，原始值: {row.get('issued_shares', 'N/A')}")

    except SystemExit:
        # check_ret 在失败时会抛出 SystemExit，这里捕获以防脚本直接退出
        logger.error("check_ret 触发了 SystemExit，可能是 OpenD 未连接、API 调用频率超限或权限不足。")
    except Exception as e:
        logger.exception(f"Futu API 调用发生未预期异常: {e}")
    finally:
        # 6. 关闭连接 (与 get_financials_statements.py 相同)
        if ctx:
            safe_close(ctx)
            logger.info("FutuOpenD 连接已关闭。")

    return result

# ==================== Financial Data Extraction ====================
def extract_financial_data(workspace: Path, ticker: str) -> dict:
    excels_path = workspace / "excels"
    inc_file = find_local_file(excels_path, ticker, "income")
    bs_file = find_local_file(excels_path, ticker, "balance")
    cf_file = find_local_file(excels_path, ticker, "cashflow")
    inc_data = _read_excel_map(inc_file) if inc_file else {}
    bs_data = _read_excel_map(bs_file) if bs_file else {}
    cf_data = _read_excel_map(cf_file) if cf_file else {}

    all_fy = set()
    for d in [inc_data, bs_data, cf_data]:
        for k, v in d.items(): all_fy.update([p for p in v.keys() if "FY" in p])
    fy_cols = sorted(list(all_fy), reverse=True)
    if not fy_cols:
        raise RuntimeError(f"未找到 {ticker} 的历史财报数据，请先运行 futu-financial-report 生成 Excel")
    latest_fy, prev_fy = fy_cols[0], fy_cols[1] if len(fy_cols) > 1 else fy_cols[0]

    # 取最近 5 个财年，按时间正序排列 (oldest -> newest)；同时保留紧邻的更早一年用于计算最老年份的增长率
    hist_fys = list(reversed(fy_cols[:5]))
    prior_to_hist = fy_cols[5] if len(fy_cols) > 5 else None

    def gv(data, keys, col):
        for k in keys:
            if k in data and col in data[k]: return data[k][col]
        return 0.0

    # ---- 历史逐年抽取（按 hist_fys 正序）----
    def _series(data, keys):
        return [gv(data, keys, fy) for fy in hist_fys]

    revenue_series = _series(inc_data, ["总收入", "营业总收入"])
    ebit_series   = _series(inc_data, ["营业利润"])
    tax_series    = _series(inc_data, ["所得税"])
    ebt_series    = _series(inc_data, ["税前利润"])
    capex_series  = [abs(v) for v in _series(inc_data, ["资本开支(CapEx)", "资本开支"])]
    da_series     = _series(cf_data, ["折旧摊销及损耗"])
    if all(v == 0.0 for v in da_series):
        da_series = _series(inc_data, ["折旧摊销及损耗"])

    # 计算历史逐年比率
    def _pct_series(nums, denoms):
        return [safe_divide(n, d) if d else 0.0 for n, d in zip(nums, denoms)]

    ebit_margin_series = _pct_series(ebit_series, revenue_series)
    da_pct_series      = _pct_series(da_series, revenue_series)
    capex_pct_series   = _pct_series(capex_series, revenue_series)
    tax_rate_series    = [safe_divide(t, e) if e > 0 else 0.0 for t, e in zip(tax_series, ebt_series)]

    # 营收增长率：最老一年需用 prior_to_hist 计算，否则留空
    rev_growth_series = []
    prior_rev = gv(inc_data, ["总收入", "营业总收入"], prior_to_hist) if prior_to_hist else 0.0
    for i, rev in enumerate(revenue_series):
        prev = revenue_series[i-1] if i > 0 else prior_rev
        rev_growth_series.append(safe_divide(rev - prev, prev) if prev else None)

    # ---- 最新一年（用于返回给下游） ----
    revenue = revenue_series[-1]
    ebit = ebit_series[-1]
    tax = tax_series[-1]
    ebt = ebt_series[-1]
    capex = capex_series[-1]
    da = da_series[-1]

    rev_growth = rev_growth_series[-1] if rev_growth_series[-1] is not None else 0.0
    tax_rate = tax_rate_series[-1] if tax_rate_series[-1] > 0 else 0.25
    debt = gv(bs_data, ["短期借款与融资租赁负债", "短期借款"], latest_fy)
    cash = gv(bs_data, ["-现金和现金等价物", "现金及现金等价物"], latest_fy)

    # ===== 从 FutuOpenD 实时获取股票价格和总股本 =====
    stock_code = normalize_stock_code(ticker)
    market_data = fetch_market_data_from_futu(stock_code)

    # 如果获取失败，使用回退默认值
    if market_data.get("stock_price") is None:
        logger.warning(f"未能从 API 获取 {ticker} 的价格，使用默认回退值 118.9")
        market_data["stock_price"] = 118.9
        market_data["shares_source"] = "Default Fallback (API Failed)"

    if market_data.get("shares_outstanding") is None:
        logger.warning(f"未能从 API 获取 {ticker} 的总股本，使用默认回退值 8922.51M")
        market_data["shares_outstanding"] = 8922.51
        market_data["shares_source"] = "Default Fallback (API Failed)"

    return {
        "ticker": ticker, "revenue": revenue, "ebit": ebit, "da": da,
        "capex": capex, "rev_growth": rev_growth, "tax_rate": tax_rate,
        "debt": debt, "cash": cash,
        # 市场数据
        "stock_price": market_data["stock_price"],
        "shares_outstanding": market_data["shares_outstanding"],
        "shares_source": market_data["shares_source"],
        # 历史序列 (按 hist_fys 正序，最多 5 期)
        "hist_fys": hist_fys,
        "hist_revenue": revenue_series,
        "hist_ebit": ebit_series,
        "hist_da": da_series,
        "hist_capex": capex_series,
        "hist_tax": tax_series,
        "hist_ebit_margin": ebit_margin_series,
        "hist_da_pct": da_pct_series,
        "hist_capex_pct": capex_pct_series,
        "hist_tax_rate": tax_rate_series,
        "hist_rev_growth": rev_growth_series,
    }

# ==================== DCF Builder ====================
class DCFBuilder:
    def __init__(self, d: dict):
        self.d = d
        self.years = 5
        self.wb = openpyxl.Workbook()

    def build(self, output_path: Path):
        self._dcf()
        self._wacc()
        output_path.parent.mkdir(parents=True, exist_ok=True)
        self.wb.save(str(output_path))
        logger.info(f"DCF model saved: {output_path}")

    def _dcf(self):
        ws = self.wb.active; ws.title = "DCF"
        d = self.d
        hist_fys = d["hist_fys"]  # 正序 (oldest -> newest),最多 5 期
        n_hist = len(hist_fys)
        # 列布局: A=标签; B..F = 历史 5 期 (若不足则前面留空); G..K = FY1..FY5 预测
        hist_start_col = 2 + (5 - n_hist)  # 使最新一年历史右对齐到 F 列
        hist_cols = list(range(hist_start_col, hist_start_col + n_hist))
        fcst_cols = list(range(7, 12))  # G..K
        all_year_cols = hist_cols + fcst_cols
        last_hist_col_letter = get_column_letter(hist_cols[-1]) if hist_cols else "B"

        ws["A1"] = f"{d['ticker']} Corporation DCF Model / {d['ticker']} -- 贴现现金流估值模型"; ws["A1"].fill = FILL_DARK_BLUE; ws["A1"].font = FONT_WHITE_BOLD
        ws.cell(3, 1, "Case 情景 (1=Bear 悲观, 2=Base 中性, 3=Bull 乐观)"); ws.cell(3, 2, 2).font = FONT_BLUE; ws.cell(3, 2).fill = FILL_INPUT_GREY

        # ===== Market Data (from Futu API) =====
        ws.cell(6, 1, "Current Stock Price -- 当前股价"); ws.cell(6, 2, d["stock_price"]).font = FONT_BLUE; ws.cell(6, 2).fill = FILL_INPUT_GREY
        ws.cell(6, 2).number_format = "$0.00"
        ws.cell(7, 1, "Diluted Shares Outstanding (M) -- 稀释后总股本(百万)"); ws.cell(7, 2, d["shares_outstanding"]).font = FONT_BLUE; ws.cell(7, 2).fill = FILL_INPUT_GREY
        ws.cell(7, 2).number_format = "#,##0.00"
        ws.cell(8, 1, "Market Capitalization (M) -- 市值(百万)"); ws.cell(8, 2, "=B6*B7").font = FONT_BLACK
        ws.cell(8, 2).number_format = "#,##0"
        add_comment(ws.cell(8, 2), "计算公式:\n  市值 = 当前股价 × 稀释后总股本\n  Market Cap = B6 × B7")
        ws.cell(9, 1, "Net Debt / (Net Cash) (M) -- 净债务/(净现金)(百万)"); ws.cell(9, 2, d["debt"] - d["cash"]).font = FONT_BLUE; ws.cell(9, 2).fill = FILL_INPUT_GREY
        ws.cell(9, 2).number_format = "#,##0"
        add_comment(ws.cell(9, 2), "计算公式:\n  净债务 = 短期借款(含融资租赁负债) - 现金及等价物\n  数据来源: 富途财报「资产负债表」最新一年")

        # 添加数据来源注释行
        ws.cell(5, 1, f"Data Source 数据来源: {d['shares_source']}").font = FONT_GREEN

        # ===== Assumptions (历史实际值 + 未来输入) =====
        ws.cell(11, 1, "BASE CASE ASSUMPTIONS -- 基准情景假设").font = FONT_BOLD
        # 表头行
        ws.cell(12, 1, "Assumption -- 假设项").fill = FILL_LIGHT_BLUE
        for i, col in enumerate(hist_cols):
            ws.cell(12, col, f"{hist_fys[i]} (Hist)").fill = FILL_LIGHT_BLUE
        for i, col in enumerate(fcst_cols):
            ws.cell(12, col, f"FY{i+1}").fill = FILL_LIGHT_BLUE

        # (row, label, 历史序列 key, 预测默认值, 数字格式, 历史列备注模板)
        assumption_rows = [
            (13, "Revenue Growth -- 营收增长率", "hist_rev_growth", d["rev_growth"], "0.0%",
             "计算公式:\n  营收增长率 = (本年营收 - 上年营收) / 上年营收"),
            (14, "EBIT Margin -- 息税前利润率", "hist_ebit_margin", safe_divide(d["ebit"], d["revenue"]), "0.0%",
             "计算公式:\n  EBIT Margin = 营业利润 / 总收入"),
            (15, "D&A % of Revenue -- 折旧摊销占营收比", "hist_da_pct", safe_divide(d["da"], d["revenue"]), "0.0%",
             "计算公式:\n  D&A% = 折旧摊销及损耗 / 总收入"),
            (16, "CapEx % of Revenue -- 资本开支占营收比", "hist_capex_pct", safe_divide(d["capex"], d["revenue"]), "0.0%",
             "计算公式:\n  CapEx% = |资本开支| / 总收入"),
            (17, "NWC % of Delta Revenue -- 净营运资本占营收变动比", None, 0.01, "0.0%",
             None),
            (18, "Tax Rate -- 税率", "hist_tax_rate", d["tax_rate"], "0.0%",
             "计算公式:\n  Tax Rate = 所得税 / 税前利润"),
        ]
        for row, label, key, val, fmt, hist_note in assumption_rows:
            ws.cell(row, 1, label)
            # 历史列: 展示计算得到的历史比率 (黑色, 只读)
            if key:
                for i, col in enumerate(hist_cols):
                    hv = d[key][i]
                    if hv is not None:
                        c = ws.cell(row, col, hv); c.font = FONT_BLACK; c.number_format = fmt
                        if hist_note:
                            add_comment(c, hist_note)
            # 预测列: 用户可编辑的假设 (蓝色输入, 浅绿背景标识预测区)
            for col in fcst_cols:
                c = ws.cell(row, col, val); c.font = FONT_BLUE; c.fill = FILL_FORECAST_GREEN; c.number_format = fmt

        ws.cell(19, 1, "Terminal Growth -- 永续增长率"); ws.cell(19, 2, 0.025).font = FONT_BLUE; ws.cell(19, 2).fill = FILL_FORECAST_GREEN; ws.cell(19, 2).number_format = "0.0%"
        add_comment(ws.cell(19, 2), "永续增长率 g:\n  用于 Gordon Growth 终值模型,\n  一般不超过长期 GDP 增速 (2%-3%)。")
        ws.cell(20, 1, "WACC -- 加权平均资本成本"); ws.cell(20, 2, "=WACC!B18").font = FONT_GREEN; ws.cell(20, 2).fill = FILL_FORECAST_GREEN; ws.cell(20, 2).number_format = "0.0%"
        add_comment(ws.cell(20, 2), "引用公式:\n  = WACC!B18\n  由 WACC 工作表 CAPM + 资本结构加权计算")

        # ===== FCF Projection (历史实际 + 未来预测) =====
        ws.cell(22, 1, "HISTORICAL & PROJECTED FREE CASH FLOW -- 历史与预测自由现金流").font = FONT_BOLD
        ws.cell(23, 1, "Fiscal Year -- 财年").fill = FILL_LIGHT_BLUE
        for i, col in enumerate(hist_cols):
            ws.cell(23, col, f"{hist_fys[i]} (Hist)").fill = FILL_LIGHT_BLUE
        for i, col in enumerate(fcst_cols):
            ws.cell(23, col, f"FY{i+1}").fill = FILL_LIGHT_BLUE

        def _write_hist(row, series):
            """将历史序列写入 hist_cols, 蓝色输入样式。"""
            for i, col in enumerate(hist_cols):
                c = ws.cell(row, col, series[i]); c.font = FONT_BLUE; c.fill = FILL_INPUT_GREY

        # Revenue (row 24)
        ws.cell(24, 1, "Revenue (M) -- 营业收入(百万)")
        _write_hist(24, d["hist_revenue"])
        first_fcst_letter = get_column_letter(fcst_cols[0])
        # 第一个预测年基于最后历史年
        c = ws.cell(24, fcst_cols[0], f"={last_hist_col_letter}24*(1+{first_fcst_letter}13)"); c.font = FONT_BLACK; c.fill = FILL_FORECAST_GREEN
        add_comment(c, f"计算公式:\n  Revenue(FY1) = Revenue({hist_fys[-1]}) × (1 + Revenue Growth)\n  = {last_hist_col_letter}24 × (1 + {first_fcst_letter}13)")
        for i in range(1, 5):
            prev = get_column_letter(fcst_cols[i-1]); cur = get_column_letter(fcst_cols[i])
            c = ws.cell(24, fcst_cols[i], f"={prev}24*(1+{cur}13)"); c.font = FONT_BLACK; c.fill = FILL_FORECAST_GREEN
            add_comment(c, f"计算公式:\n  Revenue(FY{i+1}) = Revenue(FY{i}) × (1 + Revenue Growth)\n  = {prev}24 × (1 + {cur}13)")

        # EBIT (row 26)
        ws.cell(26, 1, "EBIT (M) -- 息税前利润(百万)")
        _write_hist(26, d["hist_ebit"])
        for col in fcst_cols:
            cl = get_column_letter(col)
            c = ws.cell(26, col, f"={cl}24*{cl}14"); c.font = FONT_BLACK; c.fill = FILL_FORECAST_GREEN
            add_comment(c, f"计算公式:\n  EBIT = Revenue × EBIT Margin\n  = {cl}24 × {cl}14")

        # Tax Rate (row 28) — 展示行 (历史实际 vs 预测=假设块引用)
        ws.cell(28, 1, "Tax Rate -- 税率")
        for i, col in enumerate(hist_cols):
            c = ws.cell(28, col, d["hist_tax_rate"][i]); c.font = FONT_BLUE; c.number_format = "0.0%"
        for col in fcst_cols:
            cl = get_column_letter(col)
            c = ws.cell(28, col, f"={cl}18"); c.font = FONT_BLACK; c.fill = FILL_FORECAST_GREEN; c.number_format = "0.0%"
            add_comment(c, f"引用: 假设块 Tax Rate\n  = {cl}18")

        # Cash Taxes (row 29) — 历史用负号展示实际所得税流出
        ws.cell(29, 1, "Cash Taxes (M) -- 现金税额(百万)")
        _write_hist(29, [-t for t in d["hist_tax"]])
        for col in fcst_cols:
            cl = get_column_letter(col)
            c = ws.cell(29, col, f"=-{cl}26*{cl}28"); c.font = FONT_BLACK; c.fill = FILL_FORECAST_GREEN
            add_comment(c, f"计算公式:\n  Cash Taxes = -EBIT × Tax Rate\n  = -{cl}26 × {cl}28")

        # NOPAT (row 30) — EBIT + Cash Taxes, 所有年份统一公式
        ws.cell(30, 1, "NOPAT (M) -- 税后净营业利润(百万)")
        for col in all_year_cols:
            cl = get_column_letter(col)
            c = ws.cell(30, col, f"={cl}26+{cl}29"); c.font = FONT_BLACK
            if col in fcst_cols:
                c.fill = FILL_FORECAST_GREEN
            add_comment(c, f"计算公式:\n  NOPAT = EBIT + Cash Taxes\n  = {cl}26 + {cl}29\n  (Cash Taxes 已带负号)")

        # D&A (row 31)
        ws.cell(31, 1, "D&A (M) -- 折旧与摊销(百万)")
        _write_hist(31, d["hist_da"])
        for col in fcst_cols:
            cl = get_column_letter(col)
            c = ws.cell(31, col, f"={cl}24*{cl}15"); c.font = FONT_BLACK; c.fill = FILL_FORECAST_GREEN
            add_comment(c, f"计算公式:\n  D&A = Revenue × D&A%\n  = {cl}24 × {cl}15")

        # CapEx (row 32) — 历史以负值展示 (现金流出)
        ws.cell(32, 1, "CapEx (M) -- 资本开支(百万)")
        _write_hist(32, [-v for v in d["hist_capex"]])
        for col in fcst_cols:
            cl = get_column_letter(col)
            c = ws.cell(32, col, f"=-{cl}24*{cl}16"); c.font = FONT_BLACK; c.fill = FILL_FORECAST_GREEN
            add_comment(c, f"计算公式:\n  CapEx = -Revenue × CapEx%\n  = -{cl}24 × {cl}16\n  (负号表示现金流出)")

        # Change in NWC (row 33) — 历史第一年无 prior, 置 0; 其余用 revenue delta * NWC%
        ws.cell(33, 1, "Change in NWC (M) -- 净营运资本变动(百万)")
        for i, col in enumerate(hist_cols):
            if i == 0:
                ws.cell(33, col, 0).font = FONT_BLACK
            else:
                cur = get_column_letter(col); prev = get_column_letter(hist_cols[i-1])
                c = ws.cell(33, col, f"=-({cur}24-{prev}24)*{cur}17"); c.font = FONT_BLACK
                add_comment(c, f"计算公式:\n  ΔNWC = -(本年营收 - 上年营收) × NWC%\n  = -({cur}24 - {prev}24) × {cur}17")
        # 第一个预测年基于最后历史年
        c = ws.cell(33, fcst_cols[0], f"=-({first_fcst_letter}24-{last_hist_col_letter}24)*{first_fcst_letter}17"); c.font = FONT_BLACK; c.fill = FILL_FORECAST_GREEN
        add_comment(c, f"计算公式:\n  ΔNWC = -(FY1 Revenue - {hist_fys[-1]} Revenue) × NWC%\n  = -({first_fcst_letter}24 - {last_hist_col_letter}24) × {first_fcst_letter}17")
        for i in range(1, 5):
            cur = get_column_letter(fcst_cols[i]); prev = get_column_letter(fcst_cols[i-1])
            c = ws.cell(33, fcst_cols[i], f"=-({cur}24-{prev}24)*{cur}17"); c.font = FONT_BLACK; c.fill = FILL_FORECAST_GREEN
            add_comment(c, f"计算公式:\n  ΔNWC = -(FY{i+1} Revenue - FY{i} Revenue) × NWC%\n  = -({cur}24 - {prev}24) × {cur}17")

        # Unlevered FCF (row 34) — 所有年份
        ws.cell(34, 1, "Unlevered FCF (M) -- 无杠杆自由现金流(百万)").font = FONT_BOLD
        for col in all_year_cols:
            cl = get_column_letter(col)
            c = ws.cell(34, col, f"={cl}30+{cl}31+{cl}32+{cl}33"); c.font = FONT_BLACK; c.border = BOTTOM_BORDER
            if col in fcst_cols:
                c.fill = FILL_FORECAST_GREEN
            add_comment(c, f"计算公式:\n  Unlevered FCF = NOPAT + D&A + CapEx + ΔNWC\n  = {cl}30 + {cl}31 + {cl}32 + {cl}33")

        # Discount Period / Factor / PV — 仅预测列
        ws.cell(35, 1, "Discount Period -- 贴现期数")
        for i, col in enumerate(fcst_cols):
            c = ws.cell(35, col, i + 0.5); c.font = FONT_BLUE; c.fill = FILL_FORECAST_GREEN
            if i == 0:
                add_comment(c, "贴现期数采用「期中约定」(mid-year convention):\n  FY1=0.5, FY2=1.5, ..., FY5=4.5\n  假设现金流在会计年度中期均匀发生。")
        ws.cell(36, 1, "Discount Factor -- 贴现因子")
        for col in fcst_cols:
            cl = get_column_letter(col)
            c = ws.cell(36, col, f"=1/(1+$B$20)^{cl}35"); c.font = FONT_BLACK; c.fill = FILL_FORECAST_GREEN; c.number_format = "0.0000"
            add_comment(c, f"计算公式:\n  Discount Factor = 1 / (1 + WACC)^t\n  = 1 / (1 + $B$20)^{cl}35")
        ws.cell(37, 1, "PV of FCF (M) -- 自由现金流现值(百万)")
        for col in fcst_cols:
            cl = get_column_letter(col)
            c = ws.cell(37, col, f"={cl}34*{cl}36"); c.font = FONT_BLACK; c.fill = FILL_FORECAST_GREEN
            add_comment(c, f"计算公式:\n  PV of FCF = FCF × Discount Factor\n  = {cl}34 × {cl}36")

        # ===== Valuation Summary =====
        last_fcst_letter = get_column_letter(fcst_cols[-1])
        ws.cell(39, 1, "VALUATION SUMMARY -- 估值汇总").font = FONT_BOLD
        # 汇总区域行范围 (row 40..50) — 统一浅橙色底纹
        VAL_ROWS = range(40, 51)
        for r in VAL_ROWS:
            ws.cell(r, 1).fill = FILL_VALUATION_ORANGE
            ws.cell(r, 2).fill = FILL_VALUATION_ORANGE

        c = ws.cell(40, 1, "PV of Explicit FCFs -- 显性期自由现金流现值"); c.fill = FILL_VALUATION_ORANGE
        c = ws.cell(40, 2, f"=SUM({first_fcst_letter}37:{last_fcst_letter}37)"); c.font = FONT_BLACK; c.fill = FILL_VALUATION_ORANGE
        add_comment(c, f"计算公式:\n  显性期 FCF 现值合计 = SUM(FY1..FY5 的 PV)\n  = SUM({first_fcst_letter}37:{last_fcst_letter}37)")

        c = ws.cell(41, 1, "Terminal FCF (Year 5) -- 终值年自由现金流(第5年)"); c.fill = FILL_VALUATION_ORANGE
        c = ws.cell(41, 2, f"={last_fcst_letter}34"); c.font = FONT_BLACK; c.fill = FILL_VALUATION_ORANGE
        add_comment(c, f"引用: 第 5 预测年的 Unlevered FCF\n  = {last_fcst_letter}34")

        c = ws.cell(42, 1, "Terminal Value -- 终值"); c.fill = FILL_VALUATION_ORANGE
        c = ws.cell(42, 2, "=B41*(1+B19)/(B20-B19)"); c.font = FONT_BLACK; c.fill = FILL_VALUATION_ORANGE
        add_comment(c, "计算公式 (Gordon Growth Model):\n  TV = FCF(FY5) × (1 + g) / (WACC - g)\n  = B41 × (1 + B19) / (B20 - B19)")

        c = ws.cell(43, 1, "PV of Terminal Value -- 终值现值"); c.fill = FILL_VALUATION_ORANGE
        c = ws.cell(43, 2, f"=B42*{last_fcst_letter}36"); c.font = FONT_BLACK; c.fill = FILL_VALUATION_ORANGE
        add_comment(c, f"计算公式:\n  PV of TV = TV × Discount Factor(FY5)\n  = B42 × {last_fcst_letter}36")

        c = ws.cell(44, 1, "Enterprise Value -- 企业价值"); c.fill = FILL_VALUATION_ORANGE
        c = ws.cell(44, 2, "=B40+B43"); c.font = FONT_BOLD; c.fill = FILL_VALUATION_ORANGE_DARK
        add_comment(c, "计算公式:\n  EV = PV of Explicit FCFs + PV of Terminal Value\n  = B40 + B43")

        c = ws.cell(45, 1, "Less: Net Debt / (Net Cash) -- 减: 净债务/(净现金)"); c.fill = FILL_VALUATION_ORANGE
        c = ws.cell(45, 2, "=B9"); c.font = FONT_BLACK; c.fill = FILL_VALUATION_ORANGE
        add_comment(c, "引用: 市场数据块的净债务\n  = B9")

        c = ws.cell(46, 1, "Equity Value -- 股权价值"); c.fill = FILL_VALUATION_ORANGE
        c = ws.cell(46, 2, "=B44-B45"); c.font = FONT_BOLD; c.fill = FILL_VALUATION_ORANGE_DARK
        add_comment(c, "计算公式:\n  Equity Value = Enterprise Value - Net Debt\n  = B44 - B45")

        c = ws.cell(47, 1, "Diluted Shares Outstanding (M) -- 稀释后总股本(百万)"); c.fill = FILL_VALUATION_ORANGE
        c = ws.cell(47, 2, "=B7"); c.font = FONT_BLACK; c.fill = FILL_VALUATION_ORANGE
        add_comment(c, "引用: 市场数据块的稀释后总股本\n  = B7")

        c = ws.cell(48, 1, "Implied Price per Share -- 每股内在价值"); c.fill = FILL_VALUATION_ORANGE
        c = ws.cell(48, 2, "=B46/B47"); c.font = FONT_BOLD; c.fill = FILL_VALUATION_ORANGE_DARK; c.number_format = "$0.00"
        add_comment(c, "计算公式:\n  Implied Price = Equity Value / 稀释后总股本\n  = B46 / B47")

        c = ws.cell(49, 1, "Current Stock Price -- 当前股价"); c.fill = FILL_VALUATION_ORANGE
        c = ws.cell(49, 2, "=B6"); c.font = FONT_BLACK; c.fill = FILL_VALUATION_ORANGE; c.number_format = "$0.00"
        add_comment(c, "引用: 市场数据块的当前股价\n  = B6")

        c = ws.cell(50, 1, "Implied Upside / (Downside) -- 隐含上涨/(下跌)空间"); c.fill = FILL_VALUATION_ORANGE
        c = ws.cell(50, 2, "=B48/B49-1"); c.font = FONT_BOLD; c.fill = FILL_VALUATION_ORANGE_DARK; c.number_format = "0.0%"
        add_comment(c, "计算公式:\n  Upside% = Implied Price / Current Price - 1\n  = B48 / B49 - 1")

        ws.column_dimensions["A"].width = 46
        for col in all_year_cols:
            ws.column_dimensions[get_column_letter(col)].width = 14
        # FCF 区块统一数字格式 (跳过百分比/贴现因子行)
        for r in range(24, 38):
            for col in all_year_cols:
                if r not in (25, 27, 28, 35, 36):
                    ws.cell(r, col).number_format = "#,##0;(#,##0)"

    def _wacc(self):
        ws = self.wb.create_sheet("WACC")
        d = self.d
        ws.cell(1, 1, "COST OF EQUITY CALCULATION -- 股权成本计算").font = FONT_BOLD
        ws.cell(2, 1, "Risk-Free Rate (10Y Treasury) -- 无风险利率(10年期国债)"); ws.cell(2, 2, 0.043).font = FONT_BLUE; ws.cell(2, 2).fill = FILL_INPUT_GREY
        ws.cell(3, 1, "Beta (5Y Monthly) -- 贝塔系数(5年月度)"); ws.cell(3, 2, 1.2).font = FONT_BLUE; ws.cell(3, 2).fill = FILL_INPUT_GREY
        ws.cell(4, 1, "Equity Risk Premium -- 股权风险溢价"); ws.cell(4, 2, 0.055).font = FONT_BLUE; ws.cell(4, 2).fill = FILL_INPUT_GREY
        c = ws.cell(5, 1, "Cost of Equity -- 股权成本"); c = ws.cell(5, 2, "=B2+B3*B4"); c.font = FONT_BLACK; c.number_format = "0.0%"
        add_comment(c, "计算公式 (CAPM):\n  Cost of Equity = Rf + β × ERP\n  = B2 + B3 × B4")
        ws.cell(7, 1, "COST OF DEBT CALCULATION -- 债务成本计算").font = FONT_BOLD
        ws.cell(8, 1, "Pre-Tax Cost of Debt -- 税前债务成本"); ws.cell(8, 2, 0.045).font = FONT_BLUE; ws.cell(8, 2).fill = FILL_INPUT_GREY
        ws.cell(9, 1, "Tax Rate -- 税率"); ws.cell(9, 2, d["tax_rate"]).font = FONT_BLUE; ws.cell(9, 2).fill = FILL_INPUT_GREY
        c = ws.cell(10, 1, "After-Tax Cost of Debt -- 税后债务成本"); c = ws.cell(10, 2, "=B8*(1-B9)"); c.font = FONT_BLACK; c.number_format = "0.0%"
        add_comment(c, "计算公式:\n  After-Tax Kd = Pre-Tax Kd × (1 - Tax Rate)\n  = B8 × (1 - B9)")
        ws.cell(12, 1, "CAPITAL STRUCTURE & WACC -- 资本结构与加权平均资本成本").font = FONT_BOLD
        c = ws.cell(13, 1, "Market Capitalization (M) -- 市值(百万)"); c = ws.cell(13, 2, "=DCF!B8"); c.font = FONT_GREEN
        add_comment(c, "引用: DCF!B8\n  (市值 = 当前股价 × 稀释后总股本)")
        c = ws.cell(14, 1, "Net Debt / (Net Cash) (M) -- 净债务/(净现金)(百万)"); c = ws.cell(14, 2, "=DCF!B9"); c.font = FONT_GREEN
        add_comment(c, "引用: DCF!B9\n  (净债务 = 短期借款 - 现金)")
        c = ws.cell(15, 1, "Enterprise Capital (M) -- 企业资本合计(百万)"); c = ws.cell(15, 2, "=B13+B14"); c.font = FONT_BLACK
        add_comment(c, "计算公式:\n  Enterprise Capital = 市值 + 净债务\n  = B13 + B14")
        c = ws.cell(16, 1, "Equity Weight -- 股权权重"); c = ws.cell(16, 2, "=B13/B15"); c.font = FONT_BLACK; c.number_format = "0.0%"
        add_comment(c, "计算公式:\n  We = 市值 / Enterprise Capital\n  = B13 / B15")
        c = ws.cell(17, 1, "Debt Weight -- 债务权重"); c = ws.cell(17, 2, "=B14/B15"); c.font = FONT_BLACK; c.number_format = "0.0%"
        add_comment(c, "计算公式:\n  Wd = 净债务 / Enterprise Capital\n  = B14 / B15")
        c = ws.cell(18, 1, "WACC -- 加权平均资本成本"); c = ws.cell(18, 2, "=B16*B5+B17*B10"); c.font = FONT_BOLD; c.fill = FILL_VALUATION_ORANGE_DARK; c.number_format = "0.0%"
        add_comment(c, "计算公式:\n  WACC = We × Cost of Equity + Wd × After-Tax Kd\n  = B16 × B5 + B17 × B10")
        ws.column_dimensions["A"].width = 46; ws.column_dimensions["B"].width = 15

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--ticker", required=True)
    parser.add_argument("--workspace", required=True)
    args = parser.parse_args()
    data = extract_financial_data(Path(args.workspace), args.ticker)
    output = Path(args.workspace) / "excels" / f"{args.ticker}_DCF_Model_{date.today().isoformat()}.xlsx"
    DCFBuilder(data).build(output)
