#!/usr/bin/env python3
"""
根据命令行参数和自动提取的财务数据生成包含实时公式的 3-statement (IS, BS, CF) Excel 模型。
严格遵循 SKILL.md 中的公式优于硬编码原则和投行颜色/格式规范。
"""
import argparse
import re
import zipfile
import tempfile
import os
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any, Tuple

try:
    import openpyxl
    from openpyxl.comments import Comment
    from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
    from openpyxl.utils import get_column_letter
except ImportError as exc:
    raise ImportError("openpyxl required: pip install openpyxl") from exc

# ==================== Constants & Styles ====================
FONT_BLUE = Font(color="0000FF")
FONT_BLACK = Font(color="000000")
FONT_GREEN = Font(color="008000")
FONT_RED = Font(color="FF0000")
FONT_WHITE_BOLD = Font(color="FFFFFF", bold=True)
FONT_BOLD = Font(bold=True)

FILL_DARK_BLUE = PatternFill("solid", fgColor="1F4E79")
FILL_LIGHT_BLUE = PatternFill("solid", fgColor="D9E1F2")
FILL_MEDIUM_BLUE = PatternFill("solid", fgColor="BDD7EE")
FILL_INPUT_GREY = PatternFill("solid", fgColor="F2F2F2")

THIN_BORDER = Border(
    left=Side(style="thin", color="B7B7B7"),
    right=Side(style="thin", color="B7B7B7"),
    top=Side(style="thin", color="B7B7B7"),
    bottom=Side(style="thin", color="B7B7B7"),
)
BOTTOM_BORDER = Border(bottom=Side(style="thin", color="000000"))

# ==================== Data Extraction & Logic ====================
def safe_divide(numerator: float, denominator: float) -> float:
    return numerator / denominator if denominator != 0 else 0.0

def _ensure_shared_strings(file_path: Path) -> Tuple[Path, bool]:
    """Fix xlsx files missing sharedStrings.xml which crashes openpyxl."""
    file_path = Path(file_path)
    try:
        with zipfile.ZipFile(str(file_path), 'r') as zf:
            namelist = zf.namelist()
            has_shared_strings = 'xl/sharedStrings.xml' in namelist
            content_types_bytes = zf.read('[Content_Types].xml')
            content_types_str = content_types_bytes.decode('utf-8', errors='ignore')
            needs_shared_strings = 'sharedStrings' in content_types_str
    except Exception:
        return file_path, False

    if has_shared_strings or not needs_shared_strings:
        return file_path, False

    fd, temp_path_str = tempfile.mkstemp(suffix='.xlsx', dir=str(file_path.parent))
    os.close(fd)
    temp_path = Path(temp_path_str)

    try:
        with zipfile.ZipFile(str(file_path), 'r') as src:
            with zipfile.ZipFile(str(temp_path), 'w', zipfile.ZIP_DEFLATED) as dst:
                for item in src.namelist():
                    dst.writestr(item, src.read(item))
                dst.writestr(
                    'xl/sharedStrings.xml',
                    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
                    '<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
                    'count="0" uniqueCount="0"/>'
                )
        return temp_path, True
    except Exception:
        if temp_path.exists():
            os.remove(str(temp_path))
        return file_path, False

def _read_excel_robust(file_path: Path):
    fixed_path, is_temp = _ensure_shared_strings(file_path)
    try:
        try:
            wb = openpyxl.load_workbook(str(fixed_path), read_only=True, data_only=True)
            _ = wb.sheetnames
            return wb
        except Exception:
            pass
        wb = openpyxl.load_workbook(str(fixed_path), read_only=False, data_only=True)
        return wb
    finally:
        if is_temp and fixed_path.exists():
            try:
                os.remove(str(fixed_path))
            except Exception:
                pass

def _read_excel_map(file_path: Path) -> dict:
    wb = _read_excel_robust(file_path)
    sheet = wb.active
    data = {}

    header_row_idx = None
    for row_idx in range(1, min(5, sheet.max_row + 1)):
        row_values = [sheet.cell(row=row_idx, column=c).value for c in range(1, sheet.max_column + 1)]
        row_str = " ".join(str(v) for v in row_values if v is not None)
        if "FY" in row_str or "Q" in row_str:
            header_row_idx = row_idx
            break

    if header_row_idx is None:
        header_row_idx = 1

    periods = []
    for col_idx in range(3, sheet.max_column + 1):
        val = sheet.cell(row=header_row_idx, column=col_idx).value
        periods.append(str(val) if val is not None else "")

    for row_idx in range(header_row_idx + 1, sheet.max_row + 1):
        indicator = sheet.cell(row=row_idx, column=1).value
        if indicator is None or str(indicator).strip() == "":
            continue
        indicator = str(indicator).strip()

        values = {}
        for col_idx, period in enumerate(periods):
            val = sheet.cell(row=row_idx, column=col_idx + 3).value
            num_val = 0.0
            if isinstance(val, (int, float)):
                num_val = float(val)
            elif isinstance(val, str) and val:
                clean_val = val.replace(",", "").replace("-", "").strip()
                if clean_val and clean_val.replace(".", "", 1).isdigit():
                    num_val = float(clean_val) * (-1 if val.strip().startswith("-") else 1)
            values[period] = num_val
        data[indicator] = values

    wb.close()
    return data

def _find_latest_file(excels_path: Path, ticker: str, suffix: str) -> Path:
    pattern = re.compile(rf'^.*_{re.escape(ticker)}_{re.escape(suffix)}_.*\.(xlsx|xls)$', re.IGNORECASE)
    files = [f for f in excels_path.iterdir() if f.is_file() and pattern.match(f.name)]
    if not files:
        raise FileNotFoundError(f"No files found matching '*_{ticker}_{suffix}_*.(xlsx|xls)' in {excels_path}")
    files.sort()
    return files[-1]

def extract_financial_data(workspace: Path, ticker: str) -> dict:
    excels_path = workspace / "excels"
    if not excels_path.exists():
        raise FileNotFoundError(f"Excels directory not found: {excels_path}")

    balance_file = _find_latest_file(excels_path, ticker, "balance")
    income_file = _find_latest_file(excels_path, ticker, "income")
    cashflow_file = _find_latest_file(excels_path, ticker, "cashflow")

    print(f"Using files:\n  Balance: {balance_file.name}\n  Income: {income_file.name}\n  Cashflow: {cashflow_file.name}")

    balance_data = _read_excel_map(balance_file)
    income_data = _read_excel_map(income_file)
    cashflow_data = _read_excel_map(cashflow_file)

    fy_columns = set()
    for source in [income_data, balance_data, cashflow_data]:
        for _, values in source.items():
            for col in values.keys():
                if "FY" in str(col):
                    fy_columns.add(str(col))

    fy_columns = sorted(fy_columns, reverse=True)
    if not fy_columns:
        raise ValueError("No FY columns found")

    latest_fy = fy_columns[0]

    def get_values(data_map, keys, col):
        for key in keys:
            if key in data_map and col in data_map[key]:
                return data_map[key][col]
        return 0.0

    # IS Data
    revenue = get_values(income_data, ["总收入", "营业总收入", "Revenue"], latest_fy)
    total_costs = get_values(income_data, ["营业总成本", "Total Cost of Revenue"], latest_fy)
    cogs = total_costs * 0.7 # Estimate COGS as 70% of total costs
    opex = total_costs * 0.3 # Estimate OpEx as 30%
    da = get_values(cashflow_data, ["折旧摊销", "D&A"], latest_fy)
    if da == 0.0:
        capex = get_values(income_data, ["资本开支(CapEx)", "资本开支"], latest_fy)
        da = abs(capex) * 0.7
    interest_exp = get_values(income_data, ["利息费用", "Interest Expense"], latest_fy)
    tax_exp = get_values(income_data, ["所得税", "Income Tax"], latest_fy)
    ebt = revenue - total_costs - interest_exp
    tax_rate = safe_divide(tax_exp, ebt) if ebt > 0 else 0.25

    # BS Data
    cash = get_values(balance_data, ["现金及现金等价物和短期投资", "现金及现金等价物", "Cash & Equivalents"], latest_fy)
    ar = get_values(balance_data, ["应收账款净额", "应收款项", "Accounts Receivable"], latest_fy)
    inventory = get_values(balance_data, ["存货", "Inventory"], latest_fy)
    ppe = get_values(balance_data, ["固定资产净额", "PP&E"], latest_fy)
    ap = get_values(balance_data, ["应付账款", "Accounts Payable"], latest_fy)
    debt = get_values(balance_data, ["短期借款与融资租赁负债", "短期借款", "Total Debt"], latest_fy)
    equity = get_values(balance_data, ["归属于母公司股东权益合计", "Total Equity"], latest_fy)
    retained_earnings = equity * 0.8 # Estimate RE as 80% of Equity

    return {
        "ticker": ticker,
        "latest_fy": latest_fy,
        "revenue": revenue,
        "cogs": cogs,
        "opex": opex,
        "da": da,
        "interest_exp": interest_exp,
        "tax_rate": tax_rate,
        "cash": cash,
        "ar": ar,
        "inventory": inventory,
        "ppe": ppe,
        "ap": ap,
        "debt": debt,
        "equity": equity,
        "retained_earnings": retained_earnings,
    }

# ==================== Excel Builder Class ====================
class ThreeStatementModelBuilder:
    def __init__(self, financial_data: dict, growth_rate: float):
        self.data = financial_data
        self.growth_rate = growth_rate
        self.projection_years = 5
        self.wb = openpyxl.Workbook()

        # Assumptions derived from historicals
        self.cogs_pct = safe_divide(self.data["cogs"], self.data["revenue"])
        self.opex_pct = safe_divide(self.data["opex"], self.data["revenue"])
        self.da_pct = safe_divide(self.data["da"], self.data["revenue"])
        self.tax_rate = self.data["tax_rate"]
        self.interest_rate = safe_divide(self.data["interest_exp"], self.data["debt"]) if self.data["debt"] != 0 else 0.05

        # Historicals for Year 0
        self.hist_rev = self.data["revenue"]
        self.hist_ar = self.data["ar"]
        self.hist_inv = self.data["inventory"]
        self.hist_ap = self.data["ap"]
        self.hist_ppe = self.data["ppe"]
        self.hist_debt = self.data["debt"]
        self.hist_re = self.data["retained_earnings"]
        self.hist_cash = self.data["cash"]
        self.hist_eq = self.data["equity"] - self.hist_re # Common Stock

    def build(self, output_path) -> Path:
        self._build_assumptions()
        self._build_income_statement()
        self._build_balance_sheet()
        self._build_cash_flow()

        path = Path(output_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        self.wb.save(path)
        print(f"3-Statement model saved to {path}")
        return path

    def _build_assumptions(self) -> None:
        ws = self.wb.active
        ws.title = "Assumptions"

        ws.merge_cells("A1:B1")
        ws["A1"] = "ASSUMPTIONS"
        ws["A1"].fill = FILL_DARK_BLUE
        ws["A1"].font = FONT_WHITE_BOLD

        # Inputs
        inputs = {
            "A3": "Revenue Growth %", "B3": self.growth_rate,
            "A4": "COGS % of Revenue", "B4": self.cogs_pct,
            "A5": "OpEx % of Revenue", "B5": self.opex_pct,
            "A6": "D&A % of Revenue", "B6": self.da_pct,
            "A7": "Interest Rate on Debt", "B7": self.interest_rate,
            "A8": "Tax Rate", "B8": self.tax_rate,
            "A9": "CapEx % of Revenue", "B9": 0.05, # Assumed 5%
        }

        for cell, val in inputs.items():
            ws[cell] = val
            ws[cell].font = FONT_BLUE
            ws[cell].fill = FILL_INPUT_GREY
            if "%" in cell.replace("A", ""):
                ws[cell].number_format = "0.0%"

        ws.column_dimensions["A"].width = 25
        ws.column_dimensions["B"].width = 15

        # Format percentages
        for r in range(3, 10):
            ws[f"B{r}"].number_format = "0.0%"

    def _build_income_statement(self) -> None:
        ws = self.wb.create_sheet("Income Statement")
        ws.merge_cells(f"A1:G1")
        ws["A1"] = "INCOME STATEMENT"
        ws["A1"].fill = FILL_DARK_BLUE
        ws["A1"].font = FONT_WHITE_BOLD

        # Headers
        ws["A3"] = "Item"
        ws["A3"].fill = FILL_LIGHT_BLUE
        ws["A3"].font = FONT_BOLD
        ws["B3"] = "Year 0 (Hist)"
        ws["B3"].fill = FILL_LIGHT_BLUE
        ws["B3"].font = FONT_BOLD
        for i in range(1, self.projection_years + 1):
            cell = ws.cell(row=3, column=i+2, value=f"Year {i}")
            cell.fill = FILL_LIGHT_BLUE
            cell.font = FONT_BOLD

        # Revenue
        ws["A4"] = "Revenue"
        ws["B4"] = self.hist_rev
        ws["B4"].font = FONT_BLUE
        ws["B4"].fill = FILL_INPUT_GREY
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            prev_col = get_column_letter(i+2)
            ws[f"{col}4"] = f"={prev_col}4*(1+Assumptions!$B$3)"
            ws[f"{col}4"].font = FONT_BLACK

        # COGS
        ws["A5"] = "Less: COGS"
        ws["B5"] = self.data["cogs"]
        ws["B5"].font = FONT_BLUE
        ws["B5"].fill = FILL_INPUT_GREY
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}5"] = f"=-{col}4*Assumptions!$B$4"
            ws[f"{col}5"].font = FONT_BLACK

        # Gross Profit
        ws["A6"] = "Gross Profit"
        ws["B6"] = "=B4+B5"
        ws["B6"].font = FONT_BOLD
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}6"] = f"={col}4+{col}5"
            ws[f"{col}6"].font = FONT_BOLD

        # OpEx
        ws["A7"] = "Less: OpEx"
        ws["B7"] = -self.data["opex"]
        ws["B7"].font = FONT_BLUE
        ws["B7"].fill = FILL_INPUT_GREY
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}7"] = f"=-{col}4*Assumptions!$B$5"
            ws[f"{col}7"].font = FONT_BLACK

        # EBITDA
        ws["A8"] = "EBITDA"
        ws["B8"] = "=B6+B7"
        ws["B8"].font = FONT_BOLD
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}8"] = f"={col}6+{col}7"
            ws[f"{col}8"].font = FONT_BOLD

        # D&A
        ws["A9"] = "Less: D&A"
        ws["B9"] = -self.data["da"]
        ws["B9"].font = FONT_BLUE
        ws["B9"].fill = FILL_INPUT_GREY
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}9"] = f"=-{col}4*Assumptions!$B$6"
            ws[f"{col}9"].font = FONT_BLACK

        # EBIT
        ws["A10"] = "EBIT"
        ws["B10"] = "=B8+B9"
        ws["B10"].font = FONT_BOLD
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}10"] = f"={col}8+{col}9"
            ws[f"{col}10"].font = FONT_BOLD

        # Interest Expense
        ws["A11"] = "Less: Interest Expense"
        ws["B11"] = -self.data["interest_exp"]
        ws["B11"].font = FONT_BLUE
        ws["B11"].fill = FILL_INPUT_GREY
        # Projected interest uses beginning debt balance from BS to avoid circularity
        # Debt is on BS row 15. Year 1 uses Year 0 BS.
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            prev_col = get_column_letter(i+2)
            ws[f"{col}11"] = f"=-'Balance Sheet'!{prev_col}15*Assumptions!$B$7"
            ws[f"{col}11"].font = FONT_GREEN # Cross-tab link

        # EBT
        ws["A12"] = "EBT"
        ws["B12"] = "=B10+B11"
        ws["B12"].font = FONT_BOLD
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}12"] = f"={col}10+{col}11"
            ws[f"{col}12"].font = FONT_BOLD

        # Taxes
        ws["A13"] = "Less: Taxes"
        ws["B13"] = "=-MAX(0,B12)*Assumptions!$B$8"
        ws["B13"].font = FONT_BLACK
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}13"] = f"=-MAX(0,{col}12)*Assumptions!$B$8"
            ws[f"{col}13"].font = FONT_BLACK

        # Net Income
        ws["A14"] = "Net Income"
        ws["B14"] = "=B12+B13"
        ws["B14"].font = FONT_BOLD
        ws["B14"].border = BOTTOM_BORDER
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}14"] = f"={col}12+{col}13"
            ws[f"{col}14"].font = FONT_BOLD
            ws[f"{col}14"].border = BOTTOM_BORDER

        # Formatting
        ws.column_dimensions["A"].width = 25
        for i in range(1, self.projection_years + 2):
            ws.column_dimensions[get_column_letter(i+1)].width = 15
        for row in ws.iter_rows(min_row=4, max_row=14, min_col=2, max_col=self.projection_years+2):
            for cell in row:
                cell.number_format = "#,##0;(#,##0)"

    def _build_balance_sheet(self) -> None:
        ws = self.wb.create_sheet("Balance Sheet")
        ws.merge_cells(f"A1:G1")
        ws["A1"] = "BALANCE SHEET"
        ws["A1"].fill = FILL_DARK_BLUE
        ws["A1"].font = FONT_WHITE_BOLD

        ws["A3"] = "Assets"
        ws["A3"].fill = FILL_LIGHT_BLUE
        ws["B3"] = "Year 0 (Hist)"
        ws["B3"].fill = FILL_LIGHT_BLUE
        for i in range(1, self.projection_years + 1):
            ws.cell(row=3, column=i+2, value=f"Year {i}").fill = FILL_LIGHT_BLUE

        # Cash
        ws["A4"] = "Cash"
        ws["B4"] = self.hist_cash
        ws["B4"].font = FONT_BLUE
        ws["B4"].fill = FILL_INPUT_GREY
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}4"] = f"='Cash Flow'!{col}13" # Link to CF Ending Cash
            ws[f"{col}4"].font = FONT_GREEN

        # AR
        ws["A5"] = "Accounts Receivable"
        ws["B5"] = self.hist_ar
        ws["B5"].font = FONT_BLUE
        ws["B5"].fill = FILL_INPUT_GREY
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}5"] = f"='Cash Flow'!{col}8" # Link to CF ending AR balance
            ws[f"{col}5"].font = FONT_GREEN

        # Inventory
        ws["A6"] = "Inventory"
        ws["B6"] = self.hist_inv
        ws["B6"].font = FONT_BLUE
        ws["B6"].fill = FILL_INPUT_GREY
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}6"] = f"='Cash Flow'!{col}9"
            ws[f"{col}6"].font = FONT_GREEN

        # Total Current Assets
        ws["A7"] = "Total Current Assets"
        ws["B7"] = "=SUM(B4:B6)"
        ws["B7"].font = FONT_BOLD
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}7"] = f"=SUM({col}4:{col}6)"
            ws[f"{col}7"].font = FONT_BOLD

        # PP&E
        ws["A8"] = "PP&E, Net"
        ws["B8"] = self.hist_ppe
        ws["B8"].font = FONT_BLUE
        ws["B8"].fill = FILL_INPUT_GREY
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            prev_col = get_column_letter(i+2)
            # PP&E = Prior PP&E - D&A + CapEx
            ws[f"{col}8"] = f"={prev_col}8+'Income Statement'!{col}9+('Cash Flow'!{col}11*-1)"
            ws[f"{col}8"].font = FONT_GREEN

        # Total Assets
        ws["A9"] = "Total Assets"
        ws["B9"] = "=B7+B8"
        ws["B9"].font = FONT_BOLD
        ws["B9"].border = BOTTOM_BORDER
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}9"] = f"={col}7+{col}8"
            ws[f"{col}9"].font = FONT_BOLD
            ws[f"{col}9"].border = BOTTOM_BORDER

        # Liabilities
        ws["A11"] = "Liabilities & Equity"
        ws["A11"].fill = FILL_LIGHT_BLUE

        # AP
        ws["A12"] = "Accounts Payable"
        ws["B12"] = self.hist_ap
        ws["B12"].font = FONT_BLUE
        ws["B12"].fill = FILL_INPUT_GREY
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}12"] = f"='Cash Flow'!{col}10"
            ws[f"{col}12"].font = FONT_GREEN

        # Debt
        ws["A13"] = "Total Debt"
        ws["B13"] = self.hist_debt
        ws["B13"].font = FONT_BLUE
        ws["B13"].fill = FILL_INPUT_GREY
        # Wait, referenced as row 15 in IS formulas for interest. Let's adjust.
        # Actually, let's keep row mapping strict.
        # Let's swap AP and Debt to make Debt row 13.
        # Oops. Let's fix IS formula to use row 13.

        # Total Current Liabilities
        ws["A14"] = "Total Current Liabilities"
        ws["B14"] = "=B12+B13"
        ws["B14"].font = FONT_BOLD
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}14"] = f"={col}12+{col}13"
            ws[f"{col}14"].font = FONT_BOLD

        # Common Stock
        ws["A15"] = "Common Stock"
        ws["B15"] = self.hist_eq
        ws["B15"].font = FONT_BLUE
        ws["B15"].fill = FILL_INPUT_GREY
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}15"] = f"={get_column_letter(i+2)}15" # No change assumed

        # Retained Earnings
        ws["A16"] = "Retained Earnings"
        ws["B16"] = self.hist_re
        ws["B16"].font = FONT_BLUE
        ws["B16"].fill = FILL_INPUT_GREY
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            prev_col = get_column_letter(i+2)
            ws[f"{col}16"] = f"={prev_col}16+'Income Statement'!{col}14"
            ws[f"{col}16"].font = FONT_GREEN

        # Total Equity
        ws["A17"] = "Total Equity"
        ws["B17"] = "=B15+B16"
        ws["B17"].font = FONT_BOLD
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}17"] = f"={col}15+{col}16"
            ws[f"{col}17"].font = FONT_BOLD

        # Total Liabilities & Equity
        ws["A18"] = "Total Liabilities & Equity"
        ws["B18"] = "=B14+B17"
        ws["B18"].font = FONT_BOLD
        ws["B18"].border = BOTTOM_BORDER
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}18"] = f"={col}14+{col}17"
            ws[f"{col}18"].font = FONT_BOLD
            ws[f"{col}18"].border = BOTTOM_BORDER

        # Balance Check
        ws["A20"] = "Balance Check"
        ws["B20"] = "=B9-B18"
        ws["B20"].fill = FILL_MEDIUM_BLUE
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}20"] = f"={col}9-{col}18"
            ws[f"{col}20"].fill = FILL_MEDIUM_BLUE

        # Fix IS Interest formula to point to BS Row 13
        ws_is = self.wb["Income Statement"]
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            prev_col = get_column_letter(i+2)
            ws_is[f"{col}11"] = f"=-'Balance Sheet'!{prev_col}13*Assumptions!$B$7"

        # Formatting
        ws.column_dimensions["A"].width = 25
        for i in range(1, self.projection_years + 2):
            ws.column_dimensions[get_column_letter(i+1)].width = 15
        for row in ws.iter_rows(min_row=4, max_row=20, min_col=2, max_col=self.projection_years+2):
            for cell in row:
                if cell.row != 20:
                    cell.number_format = "#,##0;(#,##0)"

    def _build_cash_flow(self) -> None:
        ws = self.wb.create_sheet("Cash Flow")
        ws.merge_cells(f"A1:G1")
        ws["A1"] = "CASH FLOW STATEMENT"
        ws["A1"].fill = FILL_DARK_BLUE
        ws["A1"].font = FONT_WHITE_BOLD

        ws["A3"] = "Item"
        ws["A3"].fill = FILL_LIGHT_BLUE
        ws["B3"] = "Year 0 (Hist)"
        ws["B3"].fill = FILL_LIGHT_BLUE
        for i in range(1, self.projection_years + 1):
            ws.cell(row=3, column=i+2, value=f"Year {i}").fill = FILL_LIGHT_BLUE

        # Net Income
        ws["A4"] = "Net Income"
        ws["B4"] = "='Income Statement'!B14"
        ws["B4"].font = FONT_GREEN
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}4"] = f"='Income Statement'!{col}14"
            ws[f"{col}4"].font = FONT_GREEN

        # D&A
        ws["A5"] = "Plus: D&A"
        ws["B5"] = "=-'Income Statement'!B9"
        ws["B5"].font = FONT_GREEN
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}5"] = f"=-'Income Statement'!{col}9"
            ws[f"{col}5"].font = FONT_GREEN

        # Changes in WC
        ws["A6"] = "Changes in Working Capital"
        ws["A6"].font = FONT_BOLD

        # ΔAR (Increase = Cash Outflow)
        ws["A7"] = "Δ Accounts Receivable"
        ws["B7"] = 0
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            prev_col = get_column_letter(i+2)
            # We need to project AR to calculate delta. Let's use DSO proxy (AR/Rev %)
            # Actually, let's just make AR a % of Revenue for simplicity in the CF tab
            # AR End = Rev * (Hist AR / Hist Rev)
            ar_pct = safe_divide(self.hist_ar, self.hist_rev)
            ws[f"{col}7"] = f"=-('Income Statement'!{col}4*{ar_pct}-{prev_col}7*-1-{prev_col}4*{ar_pct}+{prev_col}4*{ar_pct})"
            # This is getting messy. Let's put AR, Inv, AP balances explicitly in CF rows 8, 9, 10
            # and delta in 7, but let's just do standard:
            # AR Balance Year i = Rev * AR%
            # Delta AR = AR Bal Year i - AR Bal Year i-1
            # Cash impact = - Delta AR

        # Let's clear the messy formula and do it right.
        # Row 8: AR Balance
        ws["A8"] = "AR Balance"
        ws["B8"] = self.hist_ar
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}8"] = f"='Income Statement'!{col}4*{safe_divide(self.hist_ar, self.hist_rev)}"
        # Row 9: Inventory Balance
        ws["A9"] = "Inventory Balance"
        ws["B9"] = self.hist_inv
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}9"] = f"='Income Statement'!{col}4*{safe_divide(self.hist_inv, self.hist_rev)}"
        # Row 10: AP Balance
        ws["A10"] = "AP Balance"
        ws["B10"] = self.hist_ap
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}10"] = f"='Income Statement'!{col}4*{safe_divide(self.hist_ap, self.hist_rev)}"

        # Now do the deltas
        ws["A7"] = "Δ AR"
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            prev_col = get_column_letter(i+2)
            ws[f"{col}7"] = f"=-({col}8-{prev_col}8)"
            ws[f"{col}7"].font = FONT_BLACK

        # Need rows for ΔInv and ΔAP. Let's shift things down.
        # Let's restructure rows properly.

        # Clear old A6:A10 for rewrite
        for r in range(6, 12):
            ws[f"A{r}"].value = None
            for c in range(2, self.projection_years+3):
                ws.cell(row=r, column=c).value = None

        ws["A6"] = "CFO: Working Capital Changes"
        ws["A6"].font = FONT_BOLD

        ws["A7"] = "AR Balance"
        ws["B7"] = self.hist_ar
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}7"] = f"='Income Statement'!{col}4*{safe_divide(self.hist_ar, self.hist_rev)}"

        ws["A8"] = "Δ AR"
        ws["B8"] = 0
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            prev_col = get_column_letter(i+2)
            ws[f"{col}8"] = f"=-({col}7-{prev_col}7)"

        ws["A9"] = "Inventory Balance"
        ws["B9"] = self.hist_inv
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}9"] = f"='Income Statement'!{col}4*{safe_divide(self.hist_inv, self.hist_rev)}"

        ws["A10"] = "Δ Inventory"
        ws["B10"] = 0
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            prev_col = get_column_letter(i+2)
            ws[f"{col}10"] = f"=-({col}9-{prev_col}9)"

        ws["A11"] = "AP Balance"
        ws["B11"] = self.hist_ap
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}11"] = f"='Income Statement'!{col}4*{safe_divide(self.hist_ap, self.hist_rev)}"

        ws["A12"] = "Δ AP"
        ws["B12"] = 0
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            prev_col = get_column_letter(i+2)
            ws[f"{col}12"] = f"={col}11-{prev_col}11"

        # Total CFO
        ws["A13"] = "Cash from Operations (CFO)"
        ws["B13"] = "=B4+B5+B8+B10+B12"
        ws["B13"].font = FONT_BOLD
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}13"] = f"={col}4+{col}5+{col}8+{col}10+{col}12"
            ws[f"{col}13"].font = FONT_BOLD

        # CFI: CapEx
        ws["A14"] = "Less: CapEx"
        ws["B14"] = 0
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}14"] = f"=-'Income Statement'!{col}4*Assumptions!$B$9"

        ws["A15"] = "Cash from Investing (CFI)"
        ws["B15"] = "=B14"
        ws["B15"].font = FONT_BOLD
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}15"] = f"={col}14"
            ws[f"{col}15"].font = FONT_BOLD

        # CFF: Debt Repayment (Assume 10% of beginning debt repaid annually)
        ws["A16"] = "Debt Repayment"
        ws["B16"] = 0
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            prev_col = get_column_letter(i+2)
            ws[f"{col}16"] = f"=-'Balance Sheet'!{prev_col}13*0.1"
            ws[f"{col}16"].font = FONT_GREEN

        ws["A17"] = "Cash from Financing (CFF)"
        ws["B17"] = "=B16"
        ws["B17"].font = FONT_BOLD
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}17"] = f"={col}16"
            ws[f"{col}17"].font = FONT_BOLD

        # Net Change in Cash
        ws["A18"] = "Net Change in Cash"
        ws["B18"] = "=B13+B15+B17"
        ws["B18"].font = FONT_BOLD
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}18"] = f"={col}13+{col}15+{col}17"
            ws[f"{col}18"].font = FONT_BOLD

        # Ending Cash
        ws["A19"] = "Beginning Cash"
        ws["B19"] = self.hist_cash
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            prev_col = get_column_letter(i+2)
            ws[f"{col}19"] = f"={prev_col}20"

        ws["A20"] = "Ending Cash"
        ws["B20"] = "=B19+B18"
        ws["B20"].font = FONT_BOLD
        ws["B20"].border = BOTTOM_BORDER
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws[f"{col}20"] = f"={col}19+{col}18"
            ws[f"{col}20"].font = FONT_BOLD
            ws[f"{col}20"].border = BOTTOM_BORDER

        # Update BS links to point to the correct CF rows
        ws_bs = self.wb["Balance Sheet"]
        for i in range(1, self.projection_years + 1):
            col = get_column_letter(i+3)
            ws_bs[f"{col}5"] = f"='Cash Flow'!{col}7"  # AR
            ws_bs[f"{col}6"] = f"='Cash Flow'!{col}9"  # Inv
            ws_bs[f"{col}12"] = f"='Cash Flow'!{col}11" # AP
            ws_bs[f"{col}4"] = f"='Cash Flow'!{col}20"  # Cash
            # BS Debt = Prior Debt - Repayment
            prev_col = get_column_letter(i+2)
            ws_bs[f"{col}13"] = f"={prev_col}13+'Cash Flow'!{col}16"
            # BS PP&E = Prior PP&E + CapEx - D&A
            ws_bs[f"{col}8"] = f"={prev_col}8+('Cash Flow'!{col}14*-1)+'Income Statement'!{col}9"

        # Formatting
        ws.column_dimensions["A"].width = 25
        for i in range(1, self.projection_years + 2):
            ws.column_dimensions[get_column_letter(i+1)].width = 15
        for row in ws.iter_rows(min_row=4, max_row=20, min_col=2, max_col=self.projection_years+2):
            for cell in row:
                cell.number_format = "#,##0;(#,##0)"


def main() -> None:
    parser = argparse.ArgumentParser(description="Build a 3-statement Excel model from financial data")
    parser.add_argument("--ticker", required=True, help="Stock ticker symbol (e.g., BABA)")
    parser.add_argument("--workspace", required=True, help="Path to workspace directory containing 'excels' folder")
    parser.add_argument("--growth_rate", type=float, default=0.05, help="Revenue growth rate assumption (e.g., 0.05)")
    args = parser.parse_args()

    financial_data = extract_financial_data(Path(args.workspace), args.ticker)

    output_filename = f"{args.ticker}_3Statement_Model_{date.today().isoformat()}.xlsx"
    output_path = Path(args.workspace) / "models" /output_filename

    builder = ThreeStatementModelBuilder(financial_data, args.growth_rate)
    builder.build(output_path)
    print(f"3-Statement model generated at: {output_path}")

if __name__ == "__main__":
    main()
