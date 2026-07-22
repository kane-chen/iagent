#!/usr/bin/env python3
"""
根据命令行参数和自动提取的财务数据生成包含实时公式的 DCF Excel 模型。
替代原有的 JSON 配置文件方式，通过解析 Excel 文件自动生成假设。
"""
import argparse
import re
import zipfile
import tempfile
import os
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Any, Tuple

try:
    import openpyxl
    from openpyxl.comments import Comment
    from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
    from openpyxl.utils import get_column_letter
    from openpyxl.worksheet.datavalidation import DataValidation
except ImportError as exc:
    raise ImportError("openpyxl required: pip install openpyxl") from exc

# ==================== Constants & Styles ====================
BLUE_FONT = Font(color="0000FF")
BLACK_FONT = Font(color="000000")
GREEN_FONT = Font(color="008000")
WHITE_BOLD_FONT = Font(color="FFFFFF", bold=True)
HEADER_FILL = PatternFill("solid", fgColor="1F4E79")
SUBHEADER_FILL = PatternFill("solid", fgColor="D9E1F2")
OUTPUT_FILL = PatternFill("solid", fgColor="BDD7EE")
INPUT_FILL = PatternFill("solid", fgColor="F2F2F2")
THIN_BORDER = Border(
    left=Side(style="thin", color="B7B7B7"),
    right=Side(style="thin", color="B7B7B7"),
    top=Side(style="thin", color="B7B7B7"),
    bottom=Side(style="thin", color="B7B7B7"),
)

SCENARIOS = ("bear", "base", "bull")
SERIES_KEYS = (
    "revenue_growth",
    "ebit_margin",
    "da_pct_of_revenue",
    "capex_pct_of_revenue",
    "nwc_pct_of_delta_rev",
    "tax_rate",
)
SCENARIO_LABELS = {
    "revenue_growth": "Revenue Growth",
    "ebit_margin": "EBIT Margin",
    "da_pct_of_revenue": "D&A % of Revenue",
    "capex_pct_of_revenue": "CapEx % of Revenue",
    "nwc_pct_of_delta_rev": "NWC % of Delta Revenue",
    "tax_rate": "Tax Rate",
    "terminal_growth": "Terminal Growth",
    "wacc": "WACC",
}
ROW_SUFFIXES = {
    "revenue_growth": "growth",
    "ebit_margin": "margin",
    "da_pct_of_revenue": "da",
    "capex_pct_of_revenue": "capex",
    "nwc_pct_of_delta_rev": "nwc",
    "tax_rate": "tax",
    "terminal_growth": "terminal_g",
    "wacc": "wacc",
}


def _legacy_key(key: str) -> str:
    return {
        "da_pct_of_revenue": "da_pct",
        "capex_pct_of_revenue": "capex_pct",
        "nwc_pct_of_delta_rev": "nwc",
        "revenue_m": "revenue",
    }.get(key, key)


# ==================== Configuration Classes ====================
@dataclass
class DCFConfig:
    """DCF 模型配置。"""
    company_name: str
    ticker: str
    fye: str
    stock_price: float
    shares_outstanding_m: float
    net_debt_m: float
    projection_years: int = 5
    scenarios: dict = field(default_factory=dict)
    wacc_inputs: dict = field(default_factory=dict)
    terminal_value: dict = field(default_factory=dict)
    sensitivity: dict = field(default_factory=dict)
    historical: dict = field(default_factory=dict)
    market_data: dict = field(default_factory=dict)
    company: dict = field(default_factory=dict)
    output: dict = field(default_factory=dict)
    source_annotations: dict = field(default_factory=dict)

    @property
    def terminal_growth(self) -> float:
        return float(self.terminal_value.get("terminal_growth", 0.03))

    @property
    def default_case(self) -> int:
        return int(self.output.get("default_case", 2))

    @property
    def units(self) -> str:
        return str(self.output.get("units", "M")).upper()

    @property
    def amount_scale(self) -> float:
        return 1000.0 if self.units == "B" else 1.0

    @property
    def mid_year_convention(self) -> bool:
        return bool(self.terminal_value.get("mid_year_convention", True))

    @classmethod
    def from_dict(cls, data: dict) -> "DCFConfig":
        company = data.get("company", {})
        market = data.get("market_data", {})
        terminal = dict(data.get("terminal_value", {}))
        if "terminal_growth" in data and "terminal_growth" not in terminal:
            terminal["terminal_growth"] = data["terminal_growth"]

        config = cls(
            company_name=company.get("name", ""),
            ticker=company.get("ticker", ""),
            fye=company.get("fye", ""),
            stock_price=market.get("stock_price"),
            shares_outstanding_m=market.get("shares_outstanding_m"),
            net_debt_m=market.get("net_debt_m"),
            projection_years=int(data.get("projection_years", 5)),
            scenarios=data.get("scenarios", {}),
            wacc_inputs=data.get("wacc_inputs", {}),
            terminal_value=terminal,
            sensitivity=data.get("sensitivity", {}),
            historical=data.get("historical", {}),
            market_data=market,
            company=company,
            output=data.get("output", {}),
            source_annotations=data.get("source_annotations", {}),
        )
        config.validate()
        return config

    def validate(self) -> None:
        missing = []
        for name, value in (
            ("company.name", self.company_name),
            ("company.ticker", self.ticker),
            ("company.fye", self.fye),
            ("market_data.stock_price", self.stock_price),
            ("market_data.shares_outstanding_m", self.shares_outstanding_m),
            ("market_data.net_debt_m", self.net_debt_m),
        ):
            if value is None or value == "":
                missing.append(name)
        if missing:
            raise ValueError(f"Missing required configuration fields: {', '.join(missing)}")
        if self.projection_years not in (3, 5, 7, 10):
            raise ValueError("projection_years must be one of 3, 5, 7, or 10")
        years = self.historical.get("years", [])
        revenue = self.historical.get("revenue_m", self.historical.get("revenue", []))
        if not years or not revenue:
            raise ValueError("historical.years and historical.revenue_m are required")
        if len(years) != len(revenue):
            raise ValueError("historical.years and historical.revenue_m must have equal lengths")
        for key, values in self.historical.items():
            if isinstance(values, list) and key != "years" and len(values) not in (0, len(years)):
                raise ValueError(f"historical.{key} must contain {len(years)} values")
        for scenario in SCENARIOS:
            if scenario not in self.scenarios:
                raise ValueError(f"scenarios.{scenario} is required")
            data = self.scenarios[scenario]
            for key in ("revenue_growth", "ebit_margin"):
                values = data.get(key)
                if not isinstance(values, list) or len(values) != self.projection_years:
                    raise ValueError(f"scenarios.{scenario}.{key} must contain {self.projection_years} values")
            terminal_growth = float(data.get("terminal_growth", self.terminal_growth))
            scenario_wacc = data.get("wacc")
            if scenario_wacc is not None and terminal_growth >= float(scenario_wacc):
                raise ValueError(f"scenarios.{scenario}.terminal_growth must be less than WACC")

    def historical_series(self, key: str, default: float = 0.0) -> list:
        values = self.historical.get(key, self.historical.get(_legacy_key(key)))
        if isinstance(values, list) and values:
            return [float(value) for value in values]
        return [default] * len(self.historical["years"])

    def scenario_series(self, scenario: str, key: str) -> list:
        data = self.scenarios[scenario]
        value = data.get(key, data.get(_legacy_key(key)))
        if value is None:
            historical_default = {
                "da_pct_of_revenue": 0.0,
                "capex_pct_of_revenue": 0.0,
                "nwc_pct_of_delta_rev": 0.0,
                "tax_rate": float(self.wacc_inputs.get("tax_rate", 0.21)),
            }.get(key, 0.0)
            historical = self.historical_series(key, historical_default)
            value = historical[-1] if historical else historical_default
        if isinstance(value, list):
            return [float(item) for item in value]
        return [float(value)] * self.projection_years


# ==================== Excel Builder Class ====================
class DCFModelBuilder:
    """创建包含 DCF、WACC 和敏感性分析的工作簿。"""
    def __init__(self, config: DCFConfig):
        self.config = config
        self.wb = openpyxl.Workbook()
        self._row_map: dict = {}

    @property
    def row_map(self) -> dict:
        if not self._row_map:
            self._plan_layout()
        return dict(self._row_map)

    def build(self, output_path) -> Path:
        self._plan_layout()
        self._build_dcf_sheet()
        self._build_wacc_sheet()
        self._generate_sensitivity_tables()
        self._apply_formatting()
        self._configure_calculation()
        path = Path(output_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        self.wb.save(path)
        print(f"DCF model saved to {path}")
        return path

    def _plan_layout(self) -> None:
        rm: dict = {}
        rm.update(
            title=1,
            ticker_date=2,
            case_selector=4,
            case_name=5,
            market_data_header=7,
            stock_price=8,
            shares=9,
            market_cap=10,
            net_debt=11,
        )
        row = 13
        for scenario in SCENARIOS:
            rm[f"{scenario}_header"] = row
            rm[f"{scenario}_col_header"] = row + 1
            for offset, suffix in enumerate(
                ("growth", "margin", "da", "capex", "nwc", "tax", "terminal_g", "wacc"),
                start=2,
            ):
                rm[f"{scenario}_{suffix}"] = row + offset
            row += 11
        rm["consolidation_header"] = row
        rm["consol_col_header"] = row + 1
        for offset, suffix in enumerate(
            ("growth", "margin", "da", "capex", "nwc", "tax", "terminal_g", "wacc"),
            start=2,
        ):
            rm[f"consol_{suffix}"] = row + offset
        row += 12
        rm["projection_header"] = row
        rm["year"] = row + 1
        for offset, key in enumerate(
            (
                "revenue", "revenue_growth", "ebit", "ebit_margin", "tax_rate", "taxes", "nopat",
                "da_pct", "da", "capex_pct", "capex", "nwc_pct", "delta_nwc", "unlevered_fcf",
                "discount_period", "discount_factor", "pv_fcf",
            ),
            start=2,
        ):
            rm[key] = row + offset
        rm["hist_revenue"] = rm["revenue"]
        rm["pv_fcf_start"] = rm["pv_fcf"]
        rm["pv_fcf_end"] = rm["pv_fcf"]
        row += 21
        rm["valuation_header"] = row
        for offset, key in enumerate(
            (
                "sum_pv_fcf", "terminal_fcf", "terminal_value", "pv_terminal_value",
                "enterprise_value", "valuation_net_debt", "equity_value",
                "valuation_shares", "implied_price", "valuation_stock_price", "implied_upside",
            ),
            start=1,
        ):
            rm[key] = row + offset
        rm["sensitivity_start"] = row + 15

        history_count = len(self.config.historical["years"])
        rm["historical_start_col"] = 2
        rm["historical_end_col"] = 1 + history_count
        rm["projection_start_col"] = 2 + history_count
        rm["projection_end_col"] = 1 + history_count + self.config.projection_years
        self._row_map = rm

    def _build_dcf_sheet(self) -> None:
        ws = self.wb.active
        ws.title = "DCF"
        rm = self._row_map
        end_col = max(rm["projection_end_col"], 1 + self.config.projection_years)

        ws.cell(rm["title"], 1, f"{self.config.company_name} DCF Model")
        ws.merge_cells(start_row=rm["title"], start_column=1, end_row=rm["title"], end_column=end_col)
        ws.cell(rm["ticker_date"], 1, f"Ticker: {self.config.ticker} | FYE: {self.config.fye}")
        ws.cell(rm["case_selector"], 1, "Case (1=Bear, 2=Base, 3=Bull)")
        self._input_cell(ws, rm["case_selector"], 2, self.config.default_case, "default_case")
        validation = DataValidation(type="whole", operator="between", formula1="1", formula2="3")
        ws.add_data_validation(validation)
        validation.add(ws.cell(rm["case_selector"], 2))
        ws.cell(rm["case_name"], 2, f'=CHOOSE(B{rm["case_selector"]},"Bear","Base","Bull")')

        self._section_header(ws, rm["market_data_header"], "MARKET DATA & KEY INPUTS", end_col)
        ws.cell(rm["stock_price"], 1, "Current Stock Price")
        self._input_cell(ws, rm["stock_price"], 2, float(self.config.stock_price), "stock_price")
        ws.cell(rm["shares"], 1, "Diluted Shares Outstanding (M)")
        self._input_cell(ws, rm["shares"], 2, float(self.config.shares_outstanding_m), "shares_outstanding")
        ws.cell(rm["market_cap"], 1, f"Market Capitalization ({self.config.units})")
        ws.cell(
            rm["market_cap"], 2, f'=B{rm["stock_price"]}*B{rm["shares"]}/{self.config.amount_scale:g}',
        )
        ws.cell(rm["net_debt"], 1, f"Net Debt / (Net Cash) ({self.config.units})")
        self._input_cell(
            ws, rm["net_debt"], 2, float(self.config.net_debt_m) / self.config.amount_scale, "net_debt",
        )

        for scenario in SCENARIOS:
            self._write_scenario_block(ws, scenario)
        self._write_consolidation_block(ws)
        self._write_projection_schedule(ws)
        self._write_valuation_summary(ws)

    def _write_scenario_block(self, ws, scenario: str) -> None:
        rm = self._row_map
        header = rm[f"{scenario}_header"]
        end_col = 1 + self.config.projection_years
        self._section_header(ws, header, f"{scenario.upper()} CASE ASSUMPTIONS", end_col)
        ws.cell(rm[f"{scenario}_col_header"], 1, "Assumption")
        latest_year = int(self.config.historical["years"][-1])
        for index in range(self.config.projection_years):
            ws.cell(rm[f"{scenario}_col_header"], 2 + index, f"FY{latest_year + index + 1}E")

        for key in SERIES_KEYS:
            row = rm[f"{scenario}_{ROW_SUFFIXES[key]}"]
            ws.cell(row, 1, SCENARIO_LABELS[key])
            values = self.config.scenario_series(scenario, key)
            for index, value in enumerate(values):
                self._input_cell(ws, row, 2 + index, value, f"{scenario}.{key}")
                ws.cell(row, 2 + index).number_format = self._percentage_format

        data = self.config.scenarios[scenario]
        terminal_row = rm[f"{scenario}_terminal_g"]
        ws.cell(terminal_row, 1, SCENARIO_LABELS["terminal_growth"])
        terminal_growth = float(data.get("terminal_growth", self.config.terminal_growth))
        self._input_cell(ws, terminal_row, 2, terminal_growth, f"{scenario}.terminal_growth")
        ws.cell(terminal_row, 2).number_format = self._percentage_format

        wacc_row = rm[f"{scenario}_wacc"]
        ws.cell(wacc_row, 1, SCENARIO_LABELS["wacc"])
        if data.get("wacc") is None:
            cell = ws.cell(wacc_row, 2, "='WACC'!$B$20")
            cell.font = GREEN_FONT
        else:
            self._input_cell(ws, wacc_row, 2, float(data["wacc"]), f"{scenario}.wacc")
        ws.cell(wacc_row, 2).number_format = self._percentage_format

    def _write_consolidation_block(self, ws) -> None:
        rm = self._row_map
        end_col = 1 + self.config.projection_years
        self._section_header(ws, rm["consolidation_header"], "SELECTED CASE ASSUMPTIONS", end_col)
        ws.cell(rm["consol_col_header"], 1, "Assumption")
        for index in range(self.config.projection_years):
            ws.cell(rm["consol_col_header"], 2 + index, f"FY{index + 1}")

        selector = f'$B${rm["case_selector"]}'
        for key in SERIES_KEYS:
            suffix = ROW_SUFFIXES[key]
            row = rm[f"consol_{suffix}"]
            ws.cell(row, 1, SCENARIO_LABELS[key])
            for index in range(self.config.projection_years):
                col = get_column_letter(2 + index)
                references = ",".join(f"{col}{rm[f'{scenario}_{suffix}']}" for scenario in SCENARIOS)
                cell = ws.cell(row, 2 + index, f"=CHOOSE({selector},{references})")
                cell.number_format = self._percentage_format

        for key in ("terminal_growth", "wacc"):
            suffix = ROW_SUFFIXES[key]
            row = rm[f"consol_{suffix}"]
            ws.cell(row, 1, SCENARIO_LABELS[key])
            references = ",".join(f"B{rm[f'{scenario}_{suffix}']}" for scenario in SCENARIOS)
            cell = ws.cell(row, 2, f"=CHOOSE({selector},{references})")
            cell.number_format = self._percentage_format

    def _write_projection_schedule(self, ws) -> None:
        rm = self._row_map
        end_col = rm["projection_end_col"]
        self._section_header(ws, rm["projection_header"], "HISTORICAL & PROJECTED FREE CASH FLOW", end_col)
        labels = {
            "year": "Fiscal Year",
            "revenue": f"Revenue ({self.config.units})",
            "revenue_growth": "Revenue Growth",
            "ebit": f"EBIT ({self.config.units})",
            "ebit_margin": "EBIT Margin",
            "tax_rate": "Tax Rate",
            "taxes": f"Cash Taxes ({self.config.units})",
            "nopat": f"NOPAT ({self.config.units})",
            "da_pct": "D&A % of Revenue",
            "da": f"D&A ({self.config.units})",
            "capex_pct": "CapEx % of Revenue",
            "capex": f"CapEx ({self.config.units})",
            "nwc_pct": "NWC % of Delta Revenue",
            "delta_nwc": f"Change in NWC ({self.config.units})",
            "unlevered_fcf": f"Unlevered FCF ({self.config.units})",
            "discount_period": "Discount Period",
            "discount_factor": "Discount Factor",
            "pv_fcf": f"PV of FCF ({self.config.units})",
        }
        for key, label in labels.items():
            ws.cell(rm[key], 1, label)

        history = self.config.historical
        years = history["years"]
        revenue = history.get("revenue_m", history.get("revenue"))
        ebit_values = history.get("ebit_m")
        ebit_margins = history.get("ebit_margin")
        tax_rates = self.config.historical_series("tax_rate", float(self.config.wacc_inputs.get("tax_rate", 0.21)))
        da_pcts = self.config.historical_series("da_pct_of_revenue", 0.0)
        capex_pcts = self.config.historical_series("capex_pct_of_revenue", 0.0)
        nwc_pcts = self.config.historical_series("nwc_pct_of_delta_rev", 0.0)

        for index, year in enumerate(years):
            col = rm["historical_start_col"] + index
            letter = get_column_letter(col)
            prior_letter = get_column_letter(col - 1)
            self._input_cell(ws, rm["year"], col, int(year), "historical_years")
            self._input_cell(
                ws, rm["revenue"], col, float(revenue[index]) / self.config.amount_scale, "historical_revenue",
            )
            if index > 0:
                ws.cell(rm["revenue_growth"], col, f"={letter}{rm['revenue']}/{prior_letter}{rm['revenue']}-1")
            if ebit_values:
                self._input_cell(
                    ws, rm["ebit"], col, float(ebit_values[index]) / self.config.amount_scale, "historical_ebit",
                )
                ws.cell(rm["ebit_margin"], col, f"={letter}{rm['ebit']}/{letter}{rm['revenue']}")
            elif ebit_margins:
                self._input_cell(ws, rm["ebit_margin"], col, float(ebit_margins[index]), "historical_ebit_margin")
                ws.cell(rm["ebit"], col, f"={letter}{rm['revenue']}*{letter}{rm['ebit_margin']}")
            else:
                raise ValueError("historical.ebit_m or historical.ebit_margin is required")

            self._input_cell(ws, rm["tax_rate"], col, tax_rates[index], "historical_tax_rate")
            ws.cell(rm["taxes"], col, f"={letter}{rm['ebit']}*{letter}{rm['tax_rate']}")
            ws.cell(rm["nopat"], col, f"={letter}{rm['ebit']}-{letter}{rm['taxes']}")
            self._input_cell(ws, rm["da_pct"], col, da_pcts[index], "historical_da_pct")
            ws.cell(rm["da"], col, f"={letter}{rm['revenue']}*{letter}{rm['da_pct']}")
            self._input_cell(ws, rm["capex_pct"], col, capex_pcts[index], "historical_capex_pct")
            ws.cell(rm["capex"], col, f"={letter}{rm['revenue']}*{letter}{rm['capex_pct']}")
            self._input_cell(ws, rm["nwc_pct"], col, nwc_pcts[index], "historical_nwc_pct")
            if index > 0:
                ws.cell(
                    rm["delta_nwc"], col, f"=({letter}{rm['revenue']}-{prior_letter}{rm['revenue']})*{letter}{rm['nwc_pct']}",
                )
            else:
                ws.cell(rm["delta_nwc"], col, 0)
            ws.cell(
                rm["unlevered_fcf"], col, f"={letter}{rm['nopat']}+{letter}{rm['da']}-{letter}{rm['capex']}-{letter}{rm['delta_nwc']}",
            )

        latest_year = int(years[-1])
        for index in range(self.config.projection_years):
            col = rm["projection_start_col"] + index
            letter = get_column_letter(col)
            prior_letter = get_column_letter(col - 1)
            assumption_col = get_column_letter(2 + index)

            ws.cell(rm["year"], col, latest_year + index + 1)
            ws.cell(
                rm["revenue"], col, f"={prior_letter}{rm['revenue']}*(1+{assumption_col}{rm['consol_growth']})",
            )
            ws.cell(rm["revenue_growth"], col, f"={letter}{rm['revenue']}/{prior_letter}{rm['revenue']}-1")
            ws.cell(rm["ebit_margin"], col, f"={assumption_col}{rm['consol_margin']}")
            ws.cell(rm["ebit"], col, f"={letter}{rm['revenue']}*{letter}{rm['ebit_margin']}")
            ws.cell(rm["tax_rate"], col, f"={assumption_col}{rm['consol_tax']}")
            ws.cell(rm["taxes"], col, f"={letter}{rm['ebit']}*{letter}{rm['tax_rate']}")
            ws.cell(rm["nopat"], col, f"={letter}{rm['ebit']}-{letter}{rm['taxes']}")
            ws.cell(rm["da_pct"], col, f"={assumption_col}{rm['consol_da']}")
            ws.cell(rm["da"], col, f"={letter}{rm['revenue']}*{letter}{rm['da_pct']}")
            ws.cell(rm["capex_pct"], col, f"={assumption_col}{rm['consol_capex']}")
            ws.cell(rm["capex"], col, f"={letter}{rm['revenue']}*{letter}{rm['capex_pct']}")
            ws.cell(rm["nwc_pct"], col, f"={assumption_col}{rm['consol_nwc']}")
            ws.cell(
                rm["delta_nwc"], col, f"=({letter}{rm['revenue']}-{prior_letter}{rm['revenue']})*{letter}{rm['nwc_pct']}",
            )
            ws.cell(
                rm["unlevered_fcf"], col, f"={letter}{rm['nopat']}+{letter}{rm['da']}-{letter}{rm['capex']}-{letter}{rm['delta_nwc']}",
            )
            period = index + 0.5 if self.config.mid_year_convention else index + 1.0
            ws.cell(rm["discount_period"], col, period)
            ws.cell(rm["discount_factor"], col, f"=1/(1+$B${rm['consol_wacc']})^{letter}{rm['discount_period']}")
            ws.cell(rm["pv_fcf"], col, f"={letter}{rm['unlevered_fcf']}*{letter}{rm['discount_factor']}")

    def _write_valuation_summary(self, ws) -> None:
        rm = self._row_map
        self._section_header(ws, rm["valuation_header"], "VALUATION SUMMARY", 4)
        labels = {
            "sum_pv_fcf": "PV of Explicit FCFs",
            "terminal_fcf": "Terminal FCF / EBITDA Proxy",
            "terminal_value": "Terminal Value",
            "pv_terminal_value": "PV of Terminal Value",
            "enterprise_value": "Enterprise Value",
            "valuation_net_debt": "Less: Net Debt / (Net Cash)",
            "equity_value": "Equity Value",
            "valuation_shares": "Diluted Shares Outstanding (M)",
            "implied_price": "Implied Price per Share",
            "valuation_stock_price": "Current Stock Price",
            "implied_upside": "Implied Upside / (Downside)",
        }
        for key, label in labels.items():
            ws.cell(rm[key], 1, label)

        start = get_column_letter(rm["projection_start_col"])
        end = get_column_letter(rm["projection_end_col"])
        final_period = f"{end}{rm['discount_period']}"
        final_fcf = f"{end}{rm['unlevered_fcf']}"

        ws.cell(rm["sum_pv_fcf"], 2, f"=SUM({start}{rm['pv_fcf']}:{end}{rm['pv_fcf']})")
        ws.cell(rm["terminal_fcf"], 2, f"={final_fcf}")
        method = self.config.terminal_value.get("method", "perpetuity_growth")
        if method == "exit_multiple":
            multiple = float(self.config.terminal_value["exit_multiple"])
            ws.cell(rm["terminal_value"], 2, f"=B{rm['terminal_fcf']}*{multiple:g}")
        else:
            ws.cell(
                rm["terminal_value"], 2, f"=B{rm['terminal_fcf']}*(1+$B${rm['consol_terminal_g']})/"
                f"($B${rm['consol_wacc']}-$B${rm['consol_terminal_g']})",
            )
        ws.cell(
            rm["pv_terminal_value"], 2, f"=B{rm['terminal_value']}/(1+$B${rm['consol_wacc']})^{final_period}",
        )
        ws.cell(rm["enterprise_value"], 2, f"=B{rm['sum_pv_fcf']}+B{rm['pv_terminal_value']}")
        ws.cell(rm["valuation_net_debt"], 2, f"=B{rm['net_debt']}")
        ws.cell(rm["equity_value"], 2, f"=B{rm['enterprise_value']}-B{rm['valuation_net_debt']}")
        ws.cell(rm["valuation_shares"], 2, f"=B{rm['shares']}")
        per_share_scale = 1000 if self.config.units == "B" else 1
        ws.cell(
            rm["implied_price"], 2, f"=B{rm['equity_value']}*{per_share_scale}/B{rm['valuation_shares']}",
        )
        ws.cell(rm["valuation_stock_price"], 2, f"=B{rm['stock_price']}")
        ws.cell(rm["implied_upside"], 2, f"=B{rm['implied_price']}/B{rm['valuation_stock_price']}-1")
        ws.cell(rm["implied_price"], 2).fill = OUTPUT_FILL
        ws.cell(rm["implied_price"], 2).font = Font(bold=True)

    def _build_wacc_sheet(self) -> None:
        ws = self.wb.create_sheet("WACC")
        rm = self._row_map
        wacc = self.config.wacc_inputs

        self._section_header(ws, 1, "COST OF EQUITY CALCULATION", 4)
        rows = {
            2: ("Risk-Free Rate (10Y Treasury)", float(wacc.get("risk_free_rate", 0.043)), "risk_free_rate"),
            3: ("Beta (5Y Monthly)", float(wacc.get("beta", 1.0)), "beta"),
            4: ("Equity Risk Premium", float(wacc.get("equity_risk_premium", 0.055)), "equity_risk_premium"),
        }
        for row, (label, value, source_key) in rows.items():
            ws.cell(row, 1, label)
            self._input_cell(ws, row, 2, value, source_key)
            ws.cell(row, 2).number_format = "0.0x" if source_key == "beta" else self._percentage_format
        ws.cell(5, 1, "Cost of Equity")
        ws.cell(5, 2, "=B2+B3*B4")
        ws.cell(5, 2).number_format = self._percentage_format

        self._section_header(ws, 7, "COST OF DEBT CALCULATION", 4)
        ws.cell(8, 1, "Pre-Tax Cost of Debt")
        self._input_cell(ws, 8, 2, float(wacc.get("pre_tax_cost_of_debt", 0.045)), "pre_tax_cost_of_debt")
        ws.cell(9, 1, "Tax Rate")
        self._input_cell(ws, 9, 2, float(wacc.get("tax_rate", 0.21)), "wacc_tax_rate")
        ws.cell(10, 1, "After-Tax Cost of Debt")
        ws.cell(10, 2, "=B8*(1-B9)")
        for row in (8, 9, 10):
            ws.cell(row, 2).number_format = self._percentage_format

        self._section_header(ws, 12, "CAPITAL STRUCTURE & WACC", 4)
        capital_rows = {
            13: "Current Stock Price",
            14: "Diluted Shares Outstanding (M)",
            15: f"Market Capitalization ({self.config.units})",
            16: f"Net Debt / (Net Cash) ({self.config.units})",
            17: f"Enterprise Capital ({self.config.units})",
            18: "Equity Weight",
            19: "Debt Weight",
            20: "WACC",
        }
        for row, label in capital_rows.items():
            ws.cell(row, 1, label)
        ws.cell(13, 2, f"='DCF'!B{rm['stock_price']}").font = GREEN_FONT
        ws.cell(14, 2, f"='DCF'!B{rm['shares']}").font = GREEN_FONT
        ws.cell(15, 2, f"=B13*B14/{self.config.amount_scale:g}")
        ws.cell(16, 2, f"='DCF'!B{rm['net_debt']}").font = GREEN_FONT
        ws.cell(17, 2, "=B15+B16")
        ws.cell(18, 2, '=IF(B17=0,0,B15/B17)')
        ws.cell(19, 2, '=IF(B17=0,0,B16/B17)')
        ws.cell(20, 2, "=B5*B18+B10*B19")
        for row in (18, 19, 20):
            ws.cell(row, 2).number_format = self._percentage_format
        ws.cell(20, 2).fill = OUTPUT_FILL
        ws.cell(20, 2).font = Font(bold=True)
        ws.column_dimensions["A"].width = 34
        ws.column_dimensions["B"].width = 18

    def _generate_sensitivity_tables(self) -> None:
        _generate_sensitivity_for_model(self.wb, self.config, self._row_map)

    def _apply_formatting(self) -> None:
        ws = self.wb["DCF"]
        rm = self._row_map
        percent_rows = {rm[key] for key in (
            "revenue_growth", "ebit_margin", "tax_rate", "da_pct", "capex_pct", "nwc_pct",
            "discount_factor", "implied_upside",
        )}
        amount_rows = {rm[key] for key in (
            "revenue", "ebit", "taxes", "nopat", "da", "capex", "delta_nwc",
            "unlevered_fcf", "pv_fcf", "sum_pv_fcf", "terminal_fcf", "terminal_value",
            "pv_terminal_value", "enterprise_value", "valuation_net_debt", "equity_value",
        )}
        for row in ws.iter_rows():
            for cell in row:
                if cell.value is not None:
                    cell.alignment = Alignment(vertical="center")
                    if isinstance(cell.value, str) and cell.value.startswith("=") and cell.font.color is None:
                        cell.font = Font(color="000000", bold=bool(cell.font.bold))
                    if cell.row in percent_rows:
                        cell.number_format = self._percentage_format
                    elif cell.row in amount_rows:
                        cell.number_format = self._currency_format
        ws.cell(rm["stock_price"], 2).number_format = self._per_share_format
        ws.cell(rm["implied_price"], 2).number_format = self._per_share_format
        ws.cell(rm["valuation_stock_price"], 2).number_format = self._per_share_format
        ws.column_dimensions["A"].width = 36
        for col in range(2, rm["projection_end_col"] + 1):
            ws.column_dimensions[get_column_letter(col)].width = 14
        ws.freeze_panes = "B4"

    def _configure_calculation(self) -> None:
        calculation = getattr(self.wb, "calculation", None)
        if calculation is not None:
            calculation.fullCalcOnLoad = True
            calculation.forceFullCalc = True
            calculation.calcMode = "auto"

    def _section_header(self, ws, row: int, title: str, end_col: int) -> None:
        ws.cell(row, 1, title)
        if end_col > 1:
            ws.merge_cells(start_row=row, start_column=1, end_row=row, end_column=end_col)
        for col in range(1, end_col + 1):
            cell = ws.cell(row, col)
            cell.fill = HEADER_FILL
            cell.font = WHITE_BOLD_FONT
            cell.border = THIN_BORDER

    def _input_cell(self, ws, row: int, col: int, value: Any, source_key: str) -> None:
        cell = ws.cell(row, col, value)
        cell.font = BLUE_FONT
        cell.fill = INPUT_FILL
        source = self.config.source_annotations.get(
            source_key, f"Source: DCF configuration input, {source_key}",
        )
        cell.comment = Comment(source, "DCF Builder")

    @property
    def _percentage_format(self) -> str:
        return self.config.output.get("number_format", {}).get("percentage", "0.0%")

    @property
    def _currency_format(self) -> str:
        return self.config.output.get("number_format", {}).get("currency", "$#,##0")

    @property
    def _per_share_format(self) -> str:
        return self.config.output.get("number_format", {}).get("per_share", "$#,##0.00")


# ==================== Sensitivity Analysis (Inlined) ====================
def _generate_sensitivity_for_model(wb, config, row_map):
    """Generate sensitivity analysis tables on the DCF sheet."""
    ws = wb["DCF"]
    rm = row_map
    start_row = rm["sensitivity_start"]

    base = config.scenarios.get("base", {})
    base_wacc = float(base.get("wacc", 0.09))
    base_terminal_growth = float(base.get("terminal_growth", 0.025))
    base_rev_growth = base.get("revenue_growth", [0.05] * 5)
    base_rev_growth_1 = float(base_rev_growth[0]) if base_rev_growth else 0.05
    base_ebit_margin = base.get("ebit_margin", [0.06] * 5)
    base_ebit_margin_1 = float(base_ebit_margin[0]) if base_ebit_margin else 0.06
    base_beta = float(config.wacc_inputs.get("beta", 1.2))
    base_rfr = float(config.wacc_inputs.get("risk_free_rate", 0.043))
    base_erp = float(config.wacc_inputs.get("equity_risk_premium", 0.055))

    sens_config = config.sensitivity.get("tables", {})
    row = start_row + 1

    if sens_config.get("wacc_vs_terminal_growth", {}).get("enabled", True):
        row = _write_sensitivity_table_wacc_tg(ws, row, config, base_wacc, base_terminal_growth)
        row += 2

    if sens_config.get("revenue_growth_vs_ebit_margin", {}).get("enabled", True):
        row = _write_sensitivity_table_rg_em(ws, row, config, base_rev_growth_1, base_ebit_margin_1)
        row += 2

    if sens_config.get("beta_vs_risk_free_rate", {}).get("enabled", True):
        row = _write_sensitivity_table_beta_rfr(ws, row, config, base_beta, base_rfr, base_erp)


def _recalc_equity_value(config, wacc, terminal_growth, rev_growth_override=None, ebit_margin_override=None):
    """Recalculate equity value with given parameters."""
    base = config.scenarios.get("base", {})
    revenue_growth = base.get("revenue_growth", [0.05] * 5)
    ebit_margins = base.get("ebit_margin", [0.06] * 5)
    da_pcts = base.get("da_pct_of_revenue", [0.05] * 5)
    capex_pcts = base.get("capex_pct_of_revenue", [0.08] * 5)
    nwc_pcts = base.get("nwc_pct_of_delta_rev", [0.01] * 5)
    tax_rate = float(base.get("tax_rate", 0.21))

    if rev_growth_override is not None:
        growth = [max(rev_growth_override - i * 0.015, 0.03) for i in range(5)]
        revenue_growth = growth
    if ebit_margin_override is not None:
        ebit_margins = [ebit_margin_override] * 5

    latest_revenue = float(config.historical.get("revenue_m", config.historical.get("revenue", [1]))[0]) / config.amount_scale

    sum_pv_fcf = 0.0
    prev_revenue = latest_revenue
    last_fcf = 0.0
    for i in range(5):
        rev = prev_revenue * (1 + revenue_growth[i])
        ebit = rev * ebit_margins[i]
        nopat = ebit * (1 - tax_rate)
        da = rev * (da_pcts[i] if i < len(da_pcts) else da_pcts[-1])
        capex = rev * (capex_pcts[i] if i < len(capex_pcts) else capex_pcts[-1])
        delta_nwc = (rev - prev_revenue) * (nwc_pcts[i] if i < len(nwc_pcts) else nwc_pcts[-1])
        ufcf = nopat + da - capex - delta_nwc
        period = i + 0.5
        df = 1.0 / (1 + wacc) ** period
        sum_pv_fcf += ufcf * df
        last_fcf = ufcf
        prev_revenue = rev

    tg = min(terminal_growth, wacc - 0.005)
    terminal_fcf = last_fcf * (1 + tg)
    terminal_value = terminal_fcf / (wacc - tg)
    pv_tv = terminal_value / (1 + wacc) ** 5
    ev = sum_pv_fcf + pv_tv
    net_debt = float(config.net_debt_m) / config.amount_scale
    return ev - net_debt


def _write_sensitivity_table_wacc_tg(ws, start_row, config, base_wacc, base_tg):
    wacc_offsets = [-0.01, -0.005, 0, 0.005, 0.01]
    tg_offsets = [-0.005, -0.0025, 0, 0.0025, 0.005]

    ws.cell(start_row, 1, "Implied Equity Value: WACC vs Terminal Growth").font = Font(bold=True)
    start_row += 1
    ws.cell(start_row, 1, "WACC \\ Term Growth")
    ws.cell(start_row, 1).fill = SUBHEADER_FILL
    ws.cell(start_row, 1).font = Font(bold=True)
    for j, tg_off in enumerate(tg_offsets):
        col_val = base_tg + tg_off
        cell = ws.cell(start_row, j + 2, col_val)
        cell.number_format = "0.0%"
        cell.fill = SUBHEADER_FILL
        cell.font = Font(bold=True)
    start_row += 1
    for i, wacc_off in enumerate(wacc_offsets):
        wacc_val = base_wacc + wacc_off
        cell = ws.cell(start_row + i, 1, wacc_val)
        cell.number_format = "0.0%"
        cell.fill = SUBHEADER_FILL
        cell.font = Font(bold=True)
        for j, tg_off in enumerate(tg_offsets):
            tg_val = base_tg + tg_off
            equity_val = _recalc_equity_value(config, wacc_val, tg_val)
            cell = ws.cell(start_row + i, j + 2, equity_val)
            cell.number_format = "#,##0"
            if i == 2 and j == 2:
                cell.fill = OUTPUT_FILL
                cell.font = Font(bold=True)
    return start_row + len(wacc_offsets)


def _write_sensitivity_table_rg_em(ws, start_row, config, base_rg, base_em):
    rg_offsets = [-0.03, -0.015, 0, 0.015, 0.03]
    em_offsets = [-0.03, -0.015, 0, 0.015, 0.03]

    ws.cell(start_row, 1, "Implied Equity Value: Revenue Growth vs EBIT Margin").font = Font(bold=True)
    start_row += 1
    ws.cell(start_row, 1, "Rev Growth \\ EBIT Margin")
    ws.cell(start_row, 1).fill = SUBHEADER_FILL
    ws.cell(start_row, 1).font = Font(bold=True)
    for j, em_off in enumerate(em_offsets):
        col_val = base_em + em_off
        cell = ws.cell(start_row, j + 2, col_val)
        cell.number_format = "0.0%"
        cell.fill = SUBHEADER_FILL
        cell.font = Font(bold=True)
    start_row += 1
    base = config.scenarios.get("base", {})
    base_wacc = float(base.get("wacc", 0.09))
    base_tg = float(base.get("terminal_growth", 0.025))
    for i, rg_off in enumerate(rg_offsets):
        rg_val = base_rg + rg_off
        cell = ws.cell(start_row + i, 1, rg_val)
        cell.number_format = "0.0%"
        cell.fill = SUBHEADER_FILL
        cell.font = Font(bold=True)
        for j, em_off in enumerate(em_offsets):
            em_val = base_em + em_off
            equity_val = _recalc_equity_value(config, base_wacc, base_tg,
                                               rev_growth_override=rg_val,
                                               ebit_margin_override=em_val)
            cell = ws.cell(start_row + i, j + 2, equity_val)
            cell.number_format = "#,##0"
            if i == 2 and j == 2:
                cell.fill = OUTPUT_FILL
                cell.font = Font(bold=True)
    return start_row + len(rg_offsets)


def _write_sensitivity_table_beta_rfr(ws, start_row, config, base_beta, base_rfr, base_erp):
    beta_offsets = [-0.2, -0.1, 0, 0.1, 0.2]
    rfr_offsets = [-0.005, -0.0025, 0, 0.0025, 0.005]

    ws.cell(start_row, 1, "Implied Equity Value: Beta vs Risk-Free Rate").font = Font(bold=True)
    start_row += 1
    ws.cell(start_row, 1, "Beta \\ Risk-Free Rate")
    ws.cell(start_row, 1).fill = SUBHEADER_FILL
    ws.cell(start_row, 1).font = Font(bold=True)
    for j, rfr_off in enumerate(rfr_offsets):
        col_val = base_rfr + rfr_off
        cell = ws.cell(start_row, j + 2, col_val)
        cell.number_format = "0.0%"
        cell.fill = SUBHEADER_FILL
        cell.font = Font(bold=True)
    start_row += 1
    base = config.scenarios.get("base", {})
    base_tg = float(base.get("terminal_growth", 0.025))
    for i, beta_off in enumerate(beta_offsets):
        beta_val = base_beta + beta_off
        cell = ws.cell(start_row + i, 1, beta_val)
        cell.number_format = "0.00"
        cell.fill = SUBHEADER_FILL
        cell.font = Font(bold=True)
        for j, rfr_off in enumerate(rfr_offsets):
            rfr_val = base_rfr + rfr_off
            new_cost_of_equity = rfr_val + beta_val * base_erp
            new_wacc = new_cost_of_equity
            equity_val = _recalc_equity_value(config, new_wacc, base_tg)
            cell = ws.cell(start_row + i, j + 2, equity_val)
            cell.number_format = "#,##0"
            if i == 2 and j == 2:
                cell.fill = OUTPUT_FILL
                cell.font = Font(bold=True)
    return start_row + len(beta_offsets)


# ==================== Data Extraction & Logic ====================
def safe_divide(numerator: float, denominator: float) -> float:
    return numerator / denominator if denominator != 0 else 0.0


def _ensure_shared_strings(file_path: Path) -> Tuple[Path, bool]:
    """
    Ensure the xlsx file has xl/sharedStrings.xml.
    Some xlsx files are missing this entry, causing openpyxl to crash
    with KeyError: "There is no item named 'xl/sharedStrings.xml' in the archive".

    If the file's [Content_Types].xml references sharedStrings but the actual
    xl/sharedStrings.xml is missing from the zip, we create a temporary copy
    with a minimal empty sharedStrings.xml injected.

    Returns:
        Tuple of (path_to_use, is_temp_file)
    """
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

    # Need to inject a minimal sharedStrings.xml into a temp copy
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
    """
    Read an Excel file robustly, handling xlsx files that may lack sharedStrings.xml.
    Tries read_only=True first, falls back to read_only=False.
    Also injects a minimal sharedStrings.xml if the file is missing it.
    """
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
    """
    Read a financial Excel sheet into a map: indicator -> {period -> value}.
    Handles xlsx files without sharedStrings.xml by injecting a minimal one.
    """
    wb = _read_excel_robust(file_path)
    sheet = wb.active
    data = {}

    # Find header row (row with period labels like "2026FY", "2026Q4", etc.)
    header_row_idx = None
    for row_idx in range(1, min(5, sheet.max_row + 1)):
        row_values = []
        for col_idx in range(1, sheet.max_column + 1):
            cell = sheet.cell(row=row_idx, column=col_idx)
            val = cell.value
            row_values.append(val)
        row_str = " ".join(str(v) for v in row_values if v is not None)
        if "FY" in row_str or "Q" in row_str:
            header_row_idx = row_idx
            break

    if header_row_idx is None:
        header_row_idx = 1

    # Read periods from header row (start from column C = index 3)
    periods = []
    for col_idx in range(3, sheet.max_column + 1):
        cell = sheet.cell(row=header_row_idx, column=col_idx)
        val = cell.value
        periods.append(str(val) if val is not None else "")

    # Read data rows
    for row_idx in range(header_row_idx + 1, sheet.max_row + 1):
        indicator_cell = sheet.cell(row=row_idx, column=1)
        indicator = indicator_cell.value
        if indicator is None or str(indicator).strip() == "":
            continue
        indicator = str(indicator).strip()

        values = {}
        for col_idx, period in enumerate(periods):
            cell = sheet.cell(row=row_idx, column=col_idx + 3)
            val = cell.value
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
    """
    Find the latest file matching *_{ticker}_{suffix}_* in the given path.
    Ignores regional prefixes (US_, HK_, SH_, SZ_, etc.).
    """
    pattern = re.compile(rf'^.*_{re.escape(ticker)}_{re.escape(suffix)}_.*\.(xlsx|xls)$', re.IGNORECASE)

    files = [f for f in excels_path.iterdir() if f.is_file() and pattern.match(f.name)]

    if not files:
        raise FileNotFoundError(
            f"No files found matching pattern '*_{ticker}_{suffix}_*.(xlsx|xls)' in {excels_path}"
        )

    files.sort()
    return files[-1]


def extract_financial_data(workspace: Path, ticker: str) -> dict:
    """
    从 workspace/excels 路径下读取财务数据。
    文件格式: {region}_{ticker}_{type}_yyyymmdd_stamp.excel
    忽略 region 前缀，只匹配 ticker 和 type。
    """
    excels_path = workspace / "excels"
    if not excels_path.exists():
        raise FileNotFoundError(f"Excels directory not found: {excels_path}")

    balance_file = _find_latest_file(excels_path, ticker, "balance")
    income_file = _find_latest_file(excels_path, ticker, "income")
    cashflow_file = _find_latest_file(excels_path, ticker, "cashflow")

    print(f"Using files:")
    print(f"  Balance: {balance_file.name}")
    print(f"  Income: {income_file.name}")
    print(f"  Cashflow: {cashflow_file.name}")

    balance_data = _read_excel_map(balance_file)
    income_data = _read_excel_map(income_file)
    cashflow_data = _read_excel_map(cashflow_file)

    # Extract FY columns from any available data source
    fy_columns = set()
    for source in [income_data, balance_data, cashflow_data]:
        for indicator, values in source.items():
            for col in values.keys():
                if "FY" in str(col):
                    fy_columns.add(str(col))

    fy_columns = sorted(fy_columns, reverse=True)
    if not fy_columns:
        raise ValueError("No FY columns found in any of the financial data files")

    data = {
        "years": [],
        "revenue": [],
        "ebit": [],
        "ebit_margin": [],
        "tax_expense": [],
        "net_income": [],
        "da_pct_of_revenue": [],
        "capex": [],
        "capex_pct_of_revenue": [],
        "nwc_change": [],
        "nwc_pct_of_delta_rev": [],
        "free_cash_flow": [],
        "total_debt": 0.0,
        "cash_and_equivalents": 0.0,
        "shareholders_equity": 0.0,
        "tax_rate": 0.21,
    }

    # Try multiple possible indicator names for each metric
    revenue_keys = ["总收入", "营业收入", "Revenue", "Total Revenue", "营业总收入"]
    ebit_keys = ["营业利润", "EBIT", "Operating Income", "Operating Profit", "息税前利润"]
    tax_keys = ["所得税", "所得税费用", "Income Tax", "Tax Expense", "Tax"]
    net_income_keys = ["净利润", "Net Income", "归属母公司净利润", "归属于母公司股东的净利润"]
    capex_keys = ["资本开支(CapEx)", "资本开支", "CapEx", "Capital Expenditure",
                  "购建固定资产无形资产和其他长期资产支付的现金"]
    fcf_keys = ["自由现金流(FCF)", "自由现金流", "Free Cash Flow", "FCF"]

    def get_values(data_map, keys, col):
        for key in keys:
            if key in data_map and col in data_map[key]:
                return data_map[key][col]
        return 0.0

    num_years = min(5, len(fy_columns))
    for i in range(num_years):
        col = fy_columns[i]
        year_str = str(col).replace("FY", "")
        try:
            year = int(year_str)
        except ValueError:
            year_match = re.search(r'\d{4}', year_str)
            year = int(year_match.group()) if year_match else 2020

        data["years"].append(year)

        revenue = get_values(income_data, revenue_keys, col)
        ebit = get_values(income_data, ebit_keys, col)
        tax = get_values(income_data, tax_keys, col)
        net_income = get_values(income_data, net_income_keys, col)
        capex = get_values(income_data, capex_keys, col)
        fcf = get_values(cashflow_data, fcf_keys, col)
        if fcf == 0.0:
            fcf = get_values(income_data, fcf_keys, col)

        data["revenue"].append(revenue)
        data["ebit"].append(ebit)
        data["tax_expense"].append(tax)
        data["net_income"].append(net_income)
        data["capex"].append(capex)
        data["free_cash_flow"].append(fcf)
        data["ebit_margin"].append(safe_divide(ebit, revenue))
        data["capex_pct_of_revenue"].append(safe_divide(abs(capex), revenue))
        est_da = abs(capex) * 0.7
        data["da_pct_of_revenue"].append(safe_divide(est_da, revenue))

    if data["years"]:
        total_tax = sum(data["tax_expense"])
        total_ebit = sum([e for e in data["ebit"] if e > 0])
        data["tax_rate"] = safe_divide(total_tax, total_ebit) if total_ebit > 0 else 0.21

    latest_fy = fy_columns[0]
    debt_keys = ["短期借款与融资租赁负债", "短期借款", "Total Debt", "借款"]
    cash_keys = ["现金及现金等价物和短期投资", "现金及现金等价物",
                 "Cash & Equivalents", "Cash and Equivalents"]
    equity_keys = [
        "归属母公司股东权益合计", "归属于母公司股东权益合计",
        "归属母公司股东的权益", "Shareholders' Equity", "股东权益合计",
    ]

    data["total_debt"] = get_values(balance_data, debt_keys, latest_fy)
    data["cash_and_equivalents"] = get_values(balance_data, cash_keys, latest_fy)
    data["shareholders_equity"] = get_values(balance_data, equity_keys, latest_fy)

    return data


def build_scenarios(historical_data: dict, user_tax_rate: float) -> dict:
    """基于历史数据构建 Bear, Base, Bull 情景。"""
    avg_rev_growth = 0.0
    if len(historical_data["years"]) > 1:
        growth_rates = []
        for i in range(len(historical_data["years"]) - 1):
            prev_rev = historical_data["revenue"][i + 1]
            curr_rev = historical_data["revenue"][i]
            if prev_rev > 0:
                growth_rates.append((curr_rev - prev_rev) / prev_rev)
        if growth_rates:
            avg_rev_growth = sum(growth_rates) / len(growth_rates)

    base_growth = [max(avg_rev_growth * 0.8, 0.03) - i * 0.015 for i in range(5)]
    base_growth = [max(g, 0.03) for g in base_growth]
    bear_growth = [max(avg_rev_growth * 0.5, 0.02) - i * 0.01 for i in range(5)]
    bear_growth = [max(g, 0.01) for g in bear_growth]
    bull_growth = [max(avg_rev_growth * 1.3, 0.10) - i * 0.012 for i in range(5)]
    bull_growth = [max(g, 0.05) for g in bull_growth]

    latest_ebit_margin = historical_data["ebit_margin"][0] if historical_data["ebit_margin"] else 0.0
    latest_da_pct = historical_data["da_pct_of_revenue"][0] if historical_data["da_pct_of_revenue"] else 0.0
    latest_capex_pct = historical_data["capex_pct_of_revenue"][0] if historical_data["capex_pct_of_revenue"] else 0.0

    base_ebit_margin = [latest_ebit_margin * (1 + 0.01 * (i + 1)) for i in range(5)]
    base_da_pct = [latest_da_pct] * 5
    base_capex_pct = [latest_capex_pct] * 5
    bear_ebit_margin = [latest_ebit_margin * (1 - 0.02 * (i + 1)) for i in range(5)]
    bear_da_pct = [latest_da_pct * 1.1] * 5
    bear_capex_pct = [latest_capex_pct * 1.15] * 5
    bull_ebit_margin = [min(latest_ebit_margin * (1 + 0.02 * (i + 1)), 0.50) for i in range(5)]
    bull_da_pct = [latest_da_pct * 0.9] * 5
    bull_capex_pct = [latest_capex_pct * 0.85] * 5

    scenarios = {
        "bear": {
            "revenue_growth": bear_growth,
            "ebit_margin": bear_ebit_margin,
            "da_pct_of_revenue": bear_da_pct,
            "capex_pct_of_revenue": bear_capex_pct,
            "nwc_pct_of_delta_rev": [0.02] * 5,
            "tax_rate": user_tax_rate,
            "terminal_growth": 0.02,
            "wacc": 0.10,
        },
        "base": {
            "revenue_growth": base_growth,
            "ebit_margin": base_ebit_margin,
            "da_pct_of_revenue": base_da_pct,
            "capex_pct_of_revenue": base_capex_pct,
            "nwc_pct_of_delta_rev": [0.01] * 5,
            "tax_rate": user_tax_rate,
            "terminal_growth": 0.025,
            "wacc": 0.09,
        },
        "bull": {
            "revenue_growth": bull_growth,
            "ebit_margin": bull_ebit_margin,
            "da_pct_of_revenue": bull_da_pct,
            "capex_pct_of_revenue": bull_capex_pct,
            "nwc_pct_of_delta_rev": [0.005] * 5,
            "tax_rate": user_tax_rate,
            "terminal_growth": 0.035,
            "wacc": 0.08,
        },
    }
    return scenarios


def create_dcf_config_from_args(args) -> DCFConfig:
    """根据命令行参数和提取的数据创建 DCFConfig 对象。"""
    workspace_path = Path(args.workspace)
    financial_data = extract_financial_data(workspace_path, args.ticker)
    scenarios = build_scenarios(financial_data, args.tax_rate)

    shares_outstanding = 0.0
    if args.current_price > 0 and financial_data["shareholders_equity"] > 0:
        shares_outstanding = financial_data["shareholders_equity"] / args.current_price
    else:
        shares_outstanding = 1000.0

    config_dict = {
        "company": {
            "name": f"{args.ticker} Corporation",
            "ticker": args.ticker,
            "fye": "March",
            "sector": "Technology",
            "currency": "M USD",
        },
        "market_data": {
            "stock_price": args.current_price,
            "shares_outstanding_m": shares_outstanding,
            "net_debt_m": financial_data["total_debt"] - financial_data["cash_and_equivalents"],
            "total_debt_m": financial_data["total_debt"],
            "cash_and_equivalents_m": financial_data["cash_and_equivalents"],
        },
        "projection_years": 5,
        "historical": {
            "years": financial_data["years"],
            "revenue_m": financial_data["revenue"],
            "ebit_m": financial_data["ebit"],
            "ebit_margin": financial_data["ebit_margin"],
            "tax_rate": [financial_data["tax_rate"]] * len(financial_data["years"]),
            "da_pct_of_revenue": financial_data["da_pct_of_revenue"],
            "capex_pct_of_revenue": financial_data["capex_pct_of_revenue"],
            "nwc_pct_of_delta_rev": [0.0] * len(financial_data["years"]),
        },
        "scenarios": scenarios,
        "wacc_inputs": {
            "risk_free_rate": 0.043,
            "beta": 1.2,
            "equity_risk_premium": 0.055,
            "pre_tax_cost_of_debt": 0.045,
            "tax_rate": args.tax_rate,
        },
        "terminal_value": {
            "method": "perpetuity_growth",
            "mid_year_convention": True,
        },
        "sensitivity": {
            "table_size": 5,
            "tables": {
                "wacc_vs_terminal_growth": {"enabled": True, "row_step": 0.005, "col_step": 0.005},
                "revenue_growth_vs_ebit_margin": {"enabled": True, "row_step": 0.02, "col_step": 0.02},
                "beta_vs_risk_free_rate": {"enabled": True, "row_step": 0.15, "col_step": 0.005},
            },
        },
        "output": {
            "default_case": 2,
            "units": "M",
            "number_format": {
                "currency": "$#,##0",
                "per_share": "$#,##0.00",
                "percentage": "0.0%",
                "negative_in_parens": True,
            },
        },
        "source_annotations": {
            "stock_price": "Source: Market data, User input.",
            "shares_outstanding": "Source: Estimated from Equity/Price.",
            "net_debt": "Source: Extracted from latest balance sheet.",
            "risk_free_rate": "Source: US Treasury, 10Y yield.",
            "historical_revenue": "Source: Extracted from income statement.",
        },
    }
    return DCFConfig.from_dict(config_dict)


def main() -> None:
    parser = argparse.ArgumentParser(description="Build a DCF Excel model from command line args")
    parser.add_argument("--ticker", required=True, help="Stock ticker symbol (e.g., BABA)")
    parser.add_argument("--workspace", required=True, help="Path to workspace directory containing 'excels' folder")
    parser.add_argument("--current_price", type=float, required=True, help="Current stock price")
    parser.add_argument("--tax_rate", type=float, required=True, help="Effective tax rate (e.g., 0.21)")
    args = parser.parse_args()

    config = create_dcf_config_from_args(args)
    output_filename = f"{args.ticker}_DCF_Model_{date.today().isoformat()}.xlsx"
    output_path = Path(args.workspace)/"models"/ output_filename
    DCFModelBuilder(config).build(output_path)
    print(f"DCF model generated at: {output_path}")


if __name__ == "__main__":
    main()
