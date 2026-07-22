#!/usr/bin/env python3
"""
生成包含实时公式的 3-statement (IS, BS, CF) Excel 模型。
准确提取富途API字段，修复所有已知问题（税率、D&A、COGS/OpEx拆分等）。
"""
import argparse
import re
import zipfile
import tempfile
import os
from datetime import date
from pathlib import Path
from typing import Optional, Tuple

try:
    import openpyxl
    from openpyxl.styles import Font, PatternFill, Border, Side
    from openpyxl.utils import get_column_letter
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
BOTTOM_BORDER = Border(bottom=Side(style="thin", color="000000"))

# ==================== Utility ====================
def safe_divide(n, d): return n / d if d != 0 else 0.0

def _ensure_shared_strings(file_path: Path) -> Tuple[Path, bool]:
    file_path = Path(file_path)
    try:
        with zipfile.ZipFile(str(file_path), 'r') as zf:
            if 'xl/sharedStrings.xml' in zf.namelist(): return file_path, False
            ct = zf.read('[Content_Types].xml').decode('utf-8', errors='ignore')
            if 'sharedStrings' not in ct: return file_path, False
    except: return file_path, False
    fd, tmp = tempfile.mkstemp(suffix='.xlsx', dir=str(file_path.parent))
    os.close(fd)
    try:
        with zipfile.ZipFile(str(file_path), 'r') as src, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as dst:
            for item in src.namelist(): dst.writestr(item, src.read(item))
            dst.writestr('xl/sharedStrings.xml', '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="0" uniqueCount="0"/>')
        return Path(tmp), True
    except: return file_path, False

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
    latest_fy, prev_fy = fy_cols[0], fy_cols[1] if len(fy_cols) > 1 else fy_cols[0]

    def gv(data, keys, col):
        for k in keys:
            if k in data and col in data[k]: return data[k][col]
        return 0.0

    revenue = gv(inc_data, ["总收入", "营业总收入"], latest_fy)
    prev_revenue = gv(inc_data, ["总收入", "营业总收入"], prev_fy)
    cogs = gv(inc_data, ["营业总成本"], latest_fy)
    opex = gv(inc_data, ["营业费用"], latest_fy)
    ebit = gv(inc_data, ["营业利润"], latest_fy)
    ebt = gv(inc_data, ["税前利润"], latest_fy)
    tax = gv(inc_data, ["所得税"], latest_fy)
    capex = abs(gv(inc_data, ["资本开支"], latest_fy))

    da = gv(cf_data, ["折旧摊销及损耗"], latest_fy)
    if da == 0.0: da = gv(inc_data, ["折旧摊销及损耗"], latest_fy)

    rev_growth = safe_divide(revenue - prev_revenue, prev_revenue)
    tax_rate = safe_divide(tax, ebt) if ebt > 0 else 0.25
    debt = gv(bs_data, ["短期借款与融资租赁负债", "短期借款"], latest_fy)
    interest_exp = debt * 0.03  # 估算

    ar = gv(bs_data, ["-应收账款净额", "应收账款净额"], latest_fy)
    if ar == 0.0: ar = gv(bs_data, ["应收款项"], latest_fy)
    ap = gv(bs_data, ["应付账款", "-应付账款"], latest_fy)
    if ap == 0.0: ap = gv(bs_data, ["其他应付款", "-其他应付款"], latest_fy)

    cash = gv(bs_data, ["-现金和现金等价物", "现金及现金等价物"], latest_fy)
    ppe = gv(bs_data, ["固定资产净额"], latest_fy)
    equity = gv(bs_data, ["归属于母公司股东权益合计", "股东权益合计"], latest_fy)

    re_avail = gv(bs_data, ["留存收益", "未分配利润"], latest_fy)
    retained_earnings = re_avail if re_avail != 0.0 else equity * 0.85
    common_stock = equity - retained_earnings

    return {
        "ticker": ticker, "revenue": revenue, "cogs": cogs, "opex": opex,
        "ebit": ebit, "da": da, "capex": capex, "ebitda": ebit + da,
        "rev_growth": rev_growth, "tax_rate": tax_rate, "interest_exp": interest_exp,
        "cash": cash, "ar": ar, "ap": ap, "ppe": ppe, "debt": debt,
        "equity": equity, "retained_earnings": retained_earnings, "common_stock": common_stock
    }

# ==================== Builder ====================
class ThreeStatementBuilder:
    def __init__(self, d: dict):
        self.d = d
        self.years = 5
        self.wb = openpyxl.Workbook()
        self.cogs_pct = safe_divide(d["cogs"], d["revenue"])
        self.opex_pct = safe_divide(d["opex"], d["revenue"])
        self.da_pct = safe_divide(d["da"], d["revenue"])
        self.capex_pct = safe_divide(d["capex"], d["revenue"])
        self.interest_rate = safe_divide(d["interest_exp"], d["debt"]) if d["debt"] != 0 else 0.03

    def build(self, output_path: Path):
        self._assumptions()
        self._income()
        self._cashflow()
        self._balance()
        output_path.parent.mkdir(parents=True, exist_ok=True)
        self.wb.save(str(output_path))
        print(f"3-Statement model saved: {output_path}")

    def _assumptions(self):
        ws = self.wb.active; ws.title = "Assumptions"
        ws.merge_cells("A1:B1"); ws["A1"] = "ASSUMPTIONS"; ws["A1"].fill = FILL_DARK_BLUE; ws["A1"].font = FONT_WHITE_BOLD
        inputs = [("Revenue Growth %", self.d["rev_growth"]), ("COGS % of Revenue", self.cogs_pct),
                  ("OpEx % of Revenue", self.opex_pct), ("D&A % of Revenue", self.da_pct),
                  ("Interest Rate on Debt", self.interest_rate), ("Tax Rate", self.d["tax_rate"]),
                  ("CapEx % of Revenue", self.capex_pct)]
        for i, (l, v) in enumerate(inputs):
            ws.cell(3+i, 1, l); ws.cell(3+i, 2, v).font = FONT_BLUE; ws.cell(3+i, 2).fill = FILL_INPUT_GREY
            ws.cell(3+i, 2).number_format = "0.0%"
        ws.column_dimensions["A"].width = 25; ws.column_dimensions["B"].width = 15

    def _income(self):
        ws = self.wb.create_sheet("Income Statement")
        ws.merge_cells("A1:G1"); ws["A1"] = "INCOME STATEMENT"; ws["A1"].fill = FILL_DARK_BLUE; ws["A1"].font = FONT_WHITE_BOLD
        for i, h in enumerate(["Item", "Year 0 (Hist)"] + [f"Year {j}" for j in range(1, 6)]):
            ws.cell(3, i+1, h).fill = FILL_LIGHT_BLUE
        d = self.d
        def wr(row, label, hist_val, proj_formula):
            ws.cell(row, 1, label)
            ws.cell(row, 2, hist_val).font = FONT_BLUE if isinstance(hist_val, (int, float)) else FONT_BLACK
            if isinstance(hist_val, (int, float)): ws.cell(row, 2).fill = FILL_INPUT_GREY
            for c in range(3, 8):
                ws.cell(row, c, proj_formula(get_column_letter(c), get_column_letter(c-1))).font = FONT_GREEN if "Balance Sheet" in proj_formula(get_column_letter(c), get_column_letter(c-1)) else FONT_BLACK
        wr(4, "Revenue", d["revenue"], lambda col, prev: f"={prev}4*(1+Assumptions!$B$3)")
        wr(5, "Less: COGS", d["cogs"], lambda col, prev: f"=-{col}4*Assumptions!$B$4")
        wr(6, "Gross Profit", "=B4+B5", lambda col, prev: f"={col}4+{col}5")
        ws.cell(6, 1).font = FONT_BOLD; ws.cell(6, 2).font = FONT_BLACK
        wr(7, "Less: OpEx", d["opex"], lambda col, prev: f"=-{col}4*Assumptions!$B$5")
        wr(8, "EBITDA", "=B6+B7", lambda col, prev: f"={col}6+{col}7")
        ws.cell(8, 1).font = FONT_BOLD; ws.cell(8, 2).font = FONT_BLACK
        wr(9, "Less: D&A", d["da"], lambda col, prev: f"=-{col}4*Assumptions!$B$6")
        wr(10, "EBIT", "=B8+B9", lambda col, prev: f"={col}8+{col}9")
        ws.cell(10, 1).font = FONT_BOLD; ws.cell(10, 2).font = FONT_BLACK
        wr(11, "Less: Interest Expense", d["interest_exp"], lambda col, prev: f"=-'Balance Sheet'!{prev}13*Assumptions!$B$7")
        wr(12, "EBT", "=B10+B11", lambda col, prev: f"={col}10+{col}11")
        ws.cell(12, 1).font = FONT_BOLD; ws.cell(12, 2).font = FONT_BLACK
        wr(13, "Less: Taxes", "=-MAX(0,B12)*Assumptions!$B$8", lambda col, prev: f"=-MAX(0,{col}12)*Assumptions!$B$8")
        ws.cell(13, 2).font = FONT_BLACK
        wr(14, "Net Income", "=B12+B13", lambda col, prev: f"={col}12+{col}13")
        ws.cell(14, 1).font = FONT_BOLD; ws.cell(14, 2).font = FONT_BLACK; ws.cell(14, 2).border = BOTTOM_BORDER
        for c in range(3, 8): ws.cell(14, c).border = BOTTOM_BORDER
        ws.column_dimensions["A"].width = 25
        for i in range(1, 7): ws.column_dimensions[get_column_letter(i+1)].width = 15
        for r in range(4, 15):
            for c in range(2, 8): ws.cell(r, c).number_format = "#,##0;(#,##0)"

    def _cashflow(self):
        ws = self.wb.create_sheet("Cash Flow")
        ws.merge_cells("A1:G1"); ws["A1"] = "CASH FLOW STATEMENT"; ws["A1"].fill = FILL_DARK_BLUE; ws["A1"].font = FONT_WHITE_BOLD
        for i, h in enumerate(["Item", "Year 0 (Hist)"] + [f"Year {j}" for j in range(1, 6)]):
            ws.cell(3, i+1, h).fill = FILL_LIGHT_BLUE
        d = self.d
        ar_pct, inv_pct, ap_pct = safe_divide(d["ar"], d["revenue"]), safe_divide(0, d["revenue"]), safe_divide(d["ap"], d["revenue"])
        for c in range(2, 8):
            col, prev = get_column_letter(c), get_column_letter(c-1)
            ws.cell(4, c, f"='Income Statement'!{col}14" if c > 2 else "='Income Statement'!B14").font = FONT_GREEN
            ws.cell(5, c, f"=-'Income Statement'!{col}9" if c > 2 else "=-'Income Statement'!B9").font = FONT_GREEN
            ws.cell(6, c, f"='Income Statement'!{col}4*{ar_pct}" if c > 2 else d["ar"]).font = FONT_BLACK if c > 2 else FONT_BLUE
            ws.cell(7, c, f"=-({col}6-{prev}6)" if c > 2 else 0).font = FONT_BLACK
            ws.cell(8, c, 0).font = FONT_BLACK if c > 2 else FONT_BLUE
            ws.cell(9, c, 0).font = FONT_BLACK
            ws.cell(10, c, f"='Income Statement'!{col}4*{ap_pct}" if c > 2 else d["ap"]).font = FONT_BLACK if c > 2 else FONT_BLUE
            ws.cell(11, c, f"={col}10-{prev}10" if c > 2 else 0).font = FONT_BLACK
            ws.cell(12, c, f"={col}4+{col}5+{col}7+{col}9+{col}11" if c > 2 else "=B4+B5+B7+B9+B11").font = FONT_BOLD
            ws.cell(13, c, f"=-'Income Statement'!{col}4*Assumptions!$B$9" if c > 2 else 0).font = FONT_BLACK
            ws.cell(14, c, f"={col}13").font = FONT_BOLD
            ws.cell(15, c, f"=-'Balance Sheet'!{prev}13*0.1" if c > 2 else 0).font = FONT_GREEN
            ws.cell(16, c, f"={col}15").font = FONT_BOLD
            ws.cell(17, c, f"={col}12+{col}14+{col}16").font = FONT_BOLD
            ws.cell(18, c, f"={prev}19" if c > 2 else d["cash"]).font = FONT_BLACK if c > 2 else FONT_BLUE
            ws.cell(19, c, f"={col}18+{col}17").font = FONT_BOLD
        ws.cell(4, 1, "Net Income"); ws.cell(5, 1, "Plus: D&A"); ws.cell(6, 1, "AR Balance")
        ws.cell(7, 1, "Delta AR"); ws.cell(8, 1, "Inventory Balance"); ws.cell(9, 1, "Delta Inventory")
        ws.cell(10, 1, "AP Balance"); ws.cell(11, 1, "Delta AP"); ws.cell(12, 1, "CFO")
        ws.cell(13, 1, "Less: CapEx"); ws.cell(14, 1, "CFI"); ws.cell(15, 1, "Debt Repayment")
        ws.cell(16, 1, "CFF"); ws.cell(17, 1, "Net Change in Cash"); ws.cell(18, 1, "Beginning Cash")
        ws.cell(19, 1, "Ending Cash")
        ws.cell(19, 2).border = BOTTOM_BORDER
        ws.column_dimensions["A"].width = 25
        for i in range(1, 7): ws.column_dimensions[get_column_letter(i+1)].width = 15
        for r in range(4, 20):
            for c in range(2, 8): ws.cell(r, c).number_format = "#,##0;(#,##0)"

    def _balance(self):
        ws = self.wb.create_sheet("Balance Sheet")
        ws.merge_cells("A1:G1"); ws["A1"] = "BALANCE SHEET"; ws["A1"].fill = FILL_DARK_BLUE; ws["A1"].font = FONT_WHITE_BOLD
        for i, h in enumerate(["Item", "Year 0 (Hist)"] + [f"Year {j}" for j in range(1, 6)]):
            ws.cell(3, i+1, h).fill = FILL_LIGHT_BLUE
        d = self.d
        for c in range(2, 8):
            col, prev = get_column_letter(c), get_column_letter(c-1)
            ws.cell(4, c, f"='Cash Flow'!{col}19" if c > 2 else d["cash"]).font = FONT_GREEN if c > 2 else FONT_BLUE
            ws.cell(5, c, f"='Cash Flow'!{col}6" if c > 2 else d["ar"]).font = FONT_GREEN if c > 2 else FONT_BLUE
            ws.cell(6, c, 0).font = FONT_BLACK if c > 2 else FONT_BLUE
            ws.cell(7, c, f"=SUM({col}4:{col}6)").font = FONT_BOLD
            ws.cell(8, c, f"={prev}8+'Income Statement'!{col}9+('Cash Flow'!{col}13*-1)" if c > 2 else d["ppe"]).font = FONT_GREEN if c > 2 else FONT_BLUE
            ws.cell(9, c, f"={col}7+{col}8").font = FONT_BOLD
            ws.cell(11, c, f"='Cash Flow'!{col}10" if c > 2 else d["ap"]).font = FONT_GREEN if c > 2 else FONT_BLUE
            ws.cell(12, c, f"={prev}12+'Cash Flow'!{col}15" if c > 2 else d["debt"]).font = FONT_GREEN if c > 2 else FONT_BLUE
            ws.cell(13, c, f"={col}12").font = FONT_BLACK
            ws.cell(14, c, f"={col}11+{col}13").font = FONT_BOLD
            ws.cell(15, c, f"={prev}15" if c > 2 else d["common_stock"]).font = FONT_BLACK if c > 2 else FONT_BLUE
            ws.cell(16, c, f"={prev}16+'Income Statement'!{col}14" if c > 2 else d["retained_earnings"]).font = FONT_GREEN if c > 2 else FONT_BLUE
            ws.cell(17, c, f"={col}15+{col}16").font = FONT_BOLD
            ws.cell(18, c, f"={col}14+{col}17").font = FONT_BOLD
            ws.cell(20, c, f"={col}9-{col}18").fill = FILL_MEDIUM_BLUE
        ws.cell(4, 1, "Cash"); ws.cell(5, 1, "Accounts Receivable"); ws.cell(6, 1, "Inventory")
        ws.cell(7, 1, "Total Current Assets"); ws.cell(8, 1, "PP&E, Net"); ws.cell(9, 1, "Total Assets")
        ws.cell(11, 1, "Accounts Payable"); ws.cell(12, 1, "Short Term Debt"); ws.cell(13, 1, "Total Debt")
        ws.cell(14, 1, "Total Current Liabilities"); ws.cell(15, 1, "Common Stock")
        ws.cell(16, 1, "Retained Earnings"); ws.cell(17, 1, "Total Equity")
        ws.cell(18, 1, "Total Liabilities & Equity"); ws.cell(20, 1, "Balance Check")
        ws.cell(9, 2).border = BOTTOM_BORDER; ws.cell(18, 2).border = BOTTOM_BORDER
        ws.column_dimensions["A"].width = 25
        for i in range(1, 7): ws.column_dimensions[get_column_letter(i+1)].width = 15
        for r in range(4, 19):
            for c in range(2, 8): ws.cell(r, c).number_format = "#,##0;(#,##0)"

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--ticker", required=True)
    parser.add_argument("--workspace", required=True)
    args = parser.parse_args()
    data = extract_financial_data(Path(args.workspace), args.ticker)
    output = Path(args.workspace) /"excels"/ f"{args.ticker}_3Statement_{date.today().isoformat()}.xlsx"
    ThreeStatementBuilder(data).build(output)
