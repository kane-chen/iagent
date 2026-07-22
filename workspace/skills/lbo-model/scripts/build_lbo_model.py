#!/usr/bin/env python3
"""
根据命令行参数和自动提取的财务数据生成包含实时公式的 LBO Excel 模型。
遵循 SKILL.md 中的投行标准：公式优于硬编码、严格的颜色规范和专业的格式。
"""
import argparse
import re
import zipfile
import tempfile
import os
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Any, Tuple, Dict, List

try:
    import openpyxl
    from openpyxl.comments import Comment
    from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
    from openpyxl.utils import get_column_letter
except ImportError as exc:
    raise ImportError("openpyxl required: pip install openpyxl") from exc

# ==================== Constants & Styles ====================
# Font Colors
FONT_BLUE = Font(color="0000FF")
FONT_BLACK = Font(color="000000")
FONT_PURPLE = Font(color="800080")
FONT_GREEN = Font(color="008000")
FONT_WHITE_BOLD = Font(color="FFFFFF", bold=True)

# Fill Colors
FILL_DARK_BLUE = PatternFill("solid", fgColor="1F4E79")
FILL_LIGHT_BLUE = PatternFill("solid", fgColor="D9E1F2")
FILL_MEDIUM_BLUE = PatternFill("solid", fgColor="BDD7EE")
FILL_INPUT_GREY = PatternFill("solid", fgColor="F2F2F2")

# Borders
THIN_BORDER = Border(
    left=Side(style="thin", color="B7B7B7"),
    right=Side(style="thin", color="B7B7B7"),
    top=Side(style="thin", color="B7B7B7"),
    bottom=Side(style="thin", color="B7B7B7"),
)


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
        for indicator, values in source.items():
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

    revenue = get_values(income_data, ["总收入", "营业总收入", "Revenue"], latest_fy)
    ebit = get_values(income_data, ["营业利润", "EBIT", "Operating Income"], latest_fy)
    capex = get_values(income_data, ["资本开支(CapEx)", "资本开支", "CapEx"], latest_fy)
    da = get_values(cashflow_data, ["折旧摊销", "D&A", "Depreciation & Amortization"], latest_fy)
    if da == 0.0:
        da = abs(capex) * 0.7  # Estimate if missing

    total_debt = get_values(balance_data, ["短期借款与融资租赁负债", "短期借款", "Total Debt"], latest_fy)
    cash = get_values(balance_data, ["现金及现金等价物和短期投资", "现金及现金等价物", "Cash & Equivalents"], latest_fy)

    return {
        "ticker": ticker,
        "latest_fy": latest_fy,
        "revenue": revenue,
        "ebit": ebit,
        "da": da,
        "capex": capex,
        "total_debt": total_debt,
        "cash": cash,
    }


# ==================== Excel Builder Class ====================
class LBOModelBuilder:
    def __init__(self, financial_data: dict, entry_multiple: float, exit_multiple: float):
        self.data = financial_data
        self.entry_multiple = entry_multiple
        self.exit_multiple = exit_multiple
        self.wb = openpyxl.Workbook()

        # Assumptions
        self.debt_pct = 0.50
        self.interest_rate = 0.05
        self.tax_rate = 0.25
        self.rev_growth = 0.05
        self.fees_pct = 0.02

        # EBITDA proxy: EBIT + D&A
        self.ebitda = self.data["ebit"] + self.data["da"]
        self.ebitda_margin = safe_divide(self.ebitda, self.data["revenue"])
        self.da_pct = safe_divide(self.data["da"], self.data["revenue"])

    def build(self, output_path) -> Path:
        self._build_sources_and_uses()
        self._build_operating_model()
        self._build_debt_schedule()
        self._build_returns()

        path = Path(output_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        self.wb.save(path)
        print(f"LBO model saved to {path}")
        return path

    def _build_sources_and_uses(self) -> None:
        ws = self.wb.active
        ws.title = "Sources & Uses"

        # Title
        ws.merge_cells("A1:F1")
        ws["A1"] = "SOURCES & USES"
        ws["A1"].fill = FILL_DARK_BLUE
        ws["A1"].font = FONT_WHITE_BOLD

        # Assumptions Block
        ws["A3"] = "Transaction Assumptions"
        ws["A3"].font = Font(bold=True)

        ws["A4"] = "Entry EBITDA"
        ws["B4"] = self.ebitda
        ws["B4"].font = FONT_BLUE
        ws["B4"].fill = FILL_INPUT_GREY

        ws["A5"] = "Entry EBITDA Multiple (x)"
        ws["B5"] = self.entry_multiple
        ws["B5"].font = FONT_BLUE
        ws["B5"].fill = FILL_INPUT_GREY

        ws["A6"] = "Transaction Fees (%)"
        ws["B6"] = self.fees_pct
        ws["B6"].font = FONT_BLUE
        ws["B6"].fill = FILL_INPUT_GREY
        ws["B6"].number_format = "0.0%"

        ws["A7"] = "Debt Financing (% of EV)"
        ws["B7"] = self.debt_pct
        ws["B7"].font = FONT_BLUE
        ws["B7"].fill = FILL_INPUT_GREY
        ws["B7"].number_format = "0.0%"

        # Sources & Uses Table
        ws["A9"] = "Sources"
        ws["A9"].fill = FILL_LIGHT_BLUE
        ws["A9"].font = Font(bold=True)
        ws["C9"] = "Uses"
        ws["C9"].fill = FILL_LIGHT_BLUE
        ws["C9"].font = Font(bold=True)

        # Uses
        ws["C10"] = "Purchase of Equity"
        ws["D10"] = "=B4*B5"
        ws["D10"].font = FONT_BLACK

        ws["C11"] = "Transaction Fees"
        ws["D11"] = "=D10*B6"
        ws["D11"].font = FONT_BLACK

        ws["C12"] = "Total Uses"
        ws["C12"].font = Font(bold=True)
        ws["D12"] = "=D10+D11"
        ws["D12"].font = FONT_BLACK
        ws["D12"].fill = FILL_MEDIUM_BLUE

        # Sources
        ws["A10"] = "Total Debt"
        ws["B10"] = "=D12*B7"
        ws["B10"].font = FONT_BLACK

        ws["A11"] = "Sponsor Equity (Plug)"
        ws["B11"] = "=D12-B10"
        ws["B11"].font = FONT_BLACK

        ws["A12"] = "Total Sources"
        ws["A12"].font = Font(bold=True)
        ws["B12"] = "=B10+B11"
        ws["B12"].font = FONT_BLACK
        ws["B12"].fill = FILL_MEDIUM_BLUE

        # Formatting
        ws.column_dimensions["A"].width = 25
        ws.column_dimensions["B"].width = 15
        ws.column_dimensions["C"].width = 25
        ws.column_dimensions["D"].width = 15
        for row in ws.iter_rows(min_row=4, max_row=12, min_col=2, max_col=4):
            for cell in row:
                if isinstance(cell.value, (int, float)) or (isinstance(cell.value, str) and cell.value.startswith("=")):
                    cell.number_format = "#,##0;(#,##0)"

    def _build_operating_model(self) -> None:
        ws = self.wb.create_sheet("Operating Model")

        ws.merge_cells("A1:G1")
        ws["A1"] = "OPERATING MODEL"
        ws["A1"].fill = FILL_DARK_BLUE
        ws["A1"].font = FONT_WHITE_BOLD

        # Headers
        ws["A3"] = "Item"
        ws["A3"].fill = FILL_LIGHT_BLUE
        ws["A3"].font = Font(bold=True)
        for i in range(1, 6):
            cell = ws.cell(row=3, column=i+1, value=f"Year {i}")
            cell.fill = FILL_LIGHT_BLUE
            cell.font = Font(bold=True)
            cell.alignment = Alignment(horizontal="center")

        # Revenue
        ws["A4"] = "Revenue"
        ws["B4"] = self.data["revenue"]
        ws["B4"].font = FONT_BLUE
        ws["B4"].fill = FILL_INPUT_GREY
        for i in range(2, 6):
            prev_col = get_column_letter(i)
            curr_col = get_column_letter(i+1)
            ws[f"{curr_col}4"] = f"={prev_col}4*(1+0.05)" # 5% growth hardcoded for simplicity, or could be input
            ws[f"{curr_col}4"].font = FONT_BLACK

        # EBITDA Margin
        ws["A5"] = "EBITDA Margin"
        for i in range(1, 6):
            col = get_column_letter(i+1)
            ws[f"{col}5"] = self.ebitda_margin
            ws[f"{col}5"].font = FONT_BLUE
            ws[f"{col}5"].fill = FILL_INPUT_GREY
            ws[f"{col}5"].number_format = "0.0%"

        # EBITDA
        ws["A6"] = "EBITDA"
        ws["A6"].font = Font(bold=True)
        for i in range(1, 6):
            col = get_column_letter(i+1)
            ws[f"{col}6"] = f"={col}4*{col}5"
            ws[f"{col}6"].font = FONT_BLACK
            ws[f"{col}6"].fill = FILL_LIGHT_BLUE

        # D&A
        ws["A7"] = "Less: D&A"
        for i in range(1, 6):
            col = get_column_letter(i+1)
            ws[f"{col}7"] = f"={col}4*{self.da_pct}"
            ws[f"{col}7"].font = FONT_BLACK

        # EBIT
        ws["A8"] = "EBIT"
        for i in range(1, 6):
            col = get_column_letter(i+1)
            ws[f"{col}8"] = f"={col}6-{col}7"
            ws[f"{col}8"].font = FONT_BLACK

        # Taxes
        ws["A9"] = "Less: Taxes"
        for i in range(1, 6):
            col = get_column_letter(i+1)
            ws[f"{col}9"] = f"={col}8*{self.tax_rate}"
            ws[f"{col}9"].font = FONT_BLACK
            ws[f"{col}9"].number_format = "#,##0;(#,##0)"

        # Net Income
        ws["A10"] = "Net Income"
        ws["A10"].font = Font(bold=True)
        for i in range(1, 6):
            col = get_column_letter(i+1)
            ws[f"{col}10"] = f"={col}8-{col}9"
            ws[f"{col}10"].font = FONT_BLACK
            ws[f"{col}10"].fill = FILL_LIGHT_BLUE

        # Formatting
        ws.column_dimensions["A"].width = 20
        for i in range(1, 6):
            ws.column_dimensions[get_column_letter(i+1)].width = 15
        for row in ws.iter_rows(min_row=4, max_row=10, min_col=2, max_col=6):
            for cell in row:
                if cell.row not in [5, 9] and cell.number_format != "0.0%":
                    cell.number_format = "#,##0;(#,##0)"

    def _build_debt_schedule(self) -> None:
        ws = self.wb.create_sheet("Debt Schedule")

        ws.merge_cells("A1:F1")
        ws["A1"] = "DEBT SCHEDULE"
        ws["A1"].fill = FILL_DARK_BLUE
        ws["A1"].font = FONT_WHITE_BOLD

        ws["A3"] = "Item"
        ws["A3"].fill = FILL_LIGHT_BLUE
        ws["A3"].font = Font(bold=True)
        for i in range(1, 6):
            cell = ws.cell(row=3, column=i+1, value=f"Year {i}")
            cell.fill = FILL_LIGHT_BLUE
            cell.font = Font(bold=True)

        # Beginning Balance
        ws["A4"] = "Beginning Debt Balance"
        ws["B4"] = "='Sources & Uses'!B10"
        ws["B4"].font = FONT_GREEN
        for i in range(2, 6):
            prev_col = get_column_letter(i)
            curr_col = get_column_letter(i+1)
            ws[f"{curr_col}4"] = f"={prev_col}7" # Link to prior ending
            ws[f"{curr_col}4"].font = FONT_PURPLE

        # Interest Expense
        ws["A5"] = "Interest Expense"
        for i in range(1, 6):
            col = get_column_letter(i+1)
            ws[f"{col}5"] = f"={col}4*{self.interest_rate}"
            ws[f"{col}5"].font = FONT_BLACK

        # Mandatory Repayment
        ws["A6"] = "Mandatory Repayment"
        total_debt_val = self.ebitda * self.entry_multiple * self.debt_pct
        annual_repay = total_debt_val / 5.0
        for i in range(1, 6):
            col = get_column_letter(i+1)
            ws[f"{col}6"] = annual_repay if i <= 5 else 0
            ws[f"{col}6"].font = FONT_BLUE
            ws[f"{col}6"].fill = FILL_INPUT_GREY

        # Ending Balance
        ws["A7"] = "Ending Debt Balance"
        ws["A7"].font = Font(bold=True)
        for i in range(1, 6):
            col = get_column_letter(i+1)
            ws[f"{col}7"] = f"={col}4-{col}6"
            ws[f"{col}7"].font = FONT_BLACK
            ws[f"{col}7"].fill = FILL_LIGHT_BLUE

        # Formatting
        ws.column_dimensions["A"].width = 25
        for i in range(1, 6):
            ws.column_dimensions[get_column_letter(i+1)].width = 15
        for row in ws.iter_rows(min_row=4, max_row=7, min_col=2, max_col=6):
            for cell in row:
                cell.number_format = "#,##0;(#,##0)"

    def _build_returns(self) -> None:
        ws = self.wb.create_sheet("Returns")

        ws.merge_cells("A1:B1")
        ws["A1"] = "RETURNS ANALYSIS"
        ws["A1"].fill = FILL_DARK_BLUE
        ws["A1"].font = FONT_WHITE_BOLD

        ws["A3"] = "Exit Assumptions"
        ws["A3"].font = Font(bold=True)

        ws["A4"] = "Exit EBITDA (Year 5)"
        ws["B4"] = "='Operating Model'!F6"
        ws["B4"].font = FONT_GREEN

        ws["A5"] = "Exit Multiple (x)"
        ws["B5"] = self.exit_multiple
        ws["B5"].font = FONT_BLUE
        ws["B5"].fill = FILL_INPUT_GREY

        ws["A6"] = "Exit Enterprise Value"
        ws["B6"] = "=B4*B5"
        ws["B6"].font = FONT_BLACK

        ws["A7"] = "Less: Year 5 Debt"
        ws["B7"] = "='Debt Schedule'!F7"
        ws["B7"].font = FONT_GREEN

        ws["A8"] = "Exit Equity Value"
        ws["B8"] = "=B6-B7"
        ws["B8"].font = FONT_BLACK
        ws["B8"].fill = FILL_MEDIUM_BLUE

        ws["A10"] = "Returns"
        ws["A10"].font = Font(bold=True)

        ws["A11"] = "Initial Equity Investment"
        ws["B11"] = "='Sources & Uses'!B11"
        ws["B11"].font = FONT_GREEN

        ws["A12"] = "MOIC (x)"
        ws["B12"] = "=B8/B11"
        ws["B12"].font = FONT_BLACK
        ws["B12"].fill = FILL_MEDIUM_BLUE
        ws["B12"].number_format = "0.00\"x\""

        ws["A13"] = "IRR"
        ws["B13"] = "=(B8/B11)^(1/5)-1"
        ws["B13"].font = FONT_BLACK
        ws["B13"].fill = FILL_MEDIUM_BLUE
        ws["B13"].number_format = "0.0%"

        # Formatting
        ws.column_dimensions["A"].width = 25
        ws.column_dimensions["B"].width = 15
        for row in ws.iter_rows(min_row=4, max_row=11, min_col=2, max_col=2):
            for cell in row:
                if cell.row not in [12, 13] and cell.number_format != "0.0%" and "x" not in str(cell.number_format):
                    cell.number_format = "#,##0;(#,##0)"


def main() -> None:
    parser = argparse.ArgumentParser(description="Build an LBO Excel model from financial data")
    parser.add_argument("--ticker", required=True, help="Stock ticker symbol (e.g., AAPL)")
    parser.add_argument("--workspace", required=True, help="Path to workspace directory containing 'excels' folder")
    parser.add_argument("--entry_multiple", type=float, default=10.0, help="Entry EV/EBITDA multiple (e.g., 10.0)")
    parser.add_argument("--exit_multiple", type=float, default=10.0, help="Exit EV/EBITDA multiple (e.g., 10.0)")
    args = parser.parse_args()

    financial_data = extract_financial_data(Path(args.workspace), args.ticker)

    output_filename = f"{args.ticker}_LBO_Model_{date.today().isoformat()}.xlsx"
    output_path = Path(args.workspace) /"models"/output_filename

    builder = LBOModelBuilder(financial_data, args.entry_multiple, args.exit_multiple)
    builder.build(output_path)
    print(f"LBO model generated at: {output_path}")

if __name__ == "__main__":
    main()
