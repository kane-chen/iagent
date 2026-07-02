#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
查询公司整体损益表（income statement）—— 从 workspace/excels/{MARKET}_{TICKER}_income_*.xlsx。

流程：
  1. 按 ticker 在 excels 目录里找最新版本文件（时间戳降序）
  2. 打开 sheet（默认 workbook.active）
  3. 读取第 1 行的 period 列（'2026Q1' / '2026FY' / ...），跑 period 过滤器
  4. 从第 3 行开始每行是一个指标（第 1 列指标名、第 2 列单位、之后每列一个 period 数值）
  5. --metrics 可选做指标过滤
  6. 输出 JSON

用法：
  python query_income.py --ticker BABA
  python query_income.py --ticker BABA --metrics 营业总收入,营业总成本 --fiscal-years 2024-2025 --pretty
  python query_income.py --ticker 00700 --fiscal-periods FY,Q4 --pretty

原实现：io.invest.iagent.service.retrieve.FinancialMetricsQueryService（占位空壳）。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import Any

from _common import (
    DEFAULT_CONFIG, WORKSPACE_DIR,
    emit, fail, find_latest_excel, load_config,
    normalize_value, open_worksheet,
    parse_period_filter, period_passes,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="查询公司整体损益表（income excel）",
    )
    parser.add_argument("--ticker", required=True, help="股票代码，例如 BABA / 00700 / 000858")
    parser.add_argument(
        "--metrics",
        default=None,
        help="逗号分隔的指标名子串，例如 '营业总收入,毛利,营业利润'；留空返回全部指标",
    )
    parser.add_argument("--fiscal-years", default=None, help="财年过滤，如 '2024' 或 '2022-2025'")
    parser.add_argument("--fiscal-periods", default=None, help="财期类型，如 'FY,Q3'")
    parser.add_argument("--periods", default=None, help="精确 period 列表，如 '2024Q3,2025FY'")
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--excels-dir", type=Path, default=None, help="覆盖 config 里的 excels 目录")
    parser.add_argument("--pretty", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    cfg = load_config(args.config)
    income_cfg = cfg["income"]
    excels_dir = args.excels_dir or (WORKSPACE_DIR.parent / cfg["excelsDir"]) \
        if not (WORKSPACE_DIR / cfg["excelsDir"].split("/", 1)[-1]).exists() \
        else (WORKSPACE_DIR / cfg["excelsDir"].split("/", 1)[-1])
    # 上面的 fallback 兼容 excelsDir 是 'workspace/excels' 或 'excels'
    if not excels_dir.is_dir():
        excels_dir = Path(cfg["excelsDir"])
    if not excels_dir.is_dir():
        return fail(args.ticker, f"excels 目录不存在: {excels_dir}")

    excel_path = find_latest_excel(excels_dir, income_cfg["filenamePattern"], args.ticker)
    if excel_path is None:
        return fail(
            args.ticker,
            f"未找到 {args.ticker} 的 income excel。目录={excels_dir}，"
            f"预期命名 {{MARKET}}_{args.ticker}_income_*.xlsx",
        )

    period_regex = re.compile(cfg["periodFilter"]["periodTypeRegex"])
    years, period_types, exact_periods = parse_period_filter(
        args.fiscal_years, args.fiscal_periods, args.periods, period_regex.pattern,
    )

    metric_filters: list[str] | None = None
    if args.metrics:
        metric_filters = [m.strip() for m in args.metrics.split(",") if m.strip()]

    ws, wb = open_worksheet(excel_path, income_cfg["sheetName"])
    try:
        # 抓 period 列（第 1 行；从 firstPeriodColumn 起）
        header_row = income_cfg["headerRow"]
        first_period_col = income_cfg["firstPeriodColumn"]
        max_col = ws.max_column

        period_columns: list[tuple[int, str]] = []
        for col in range(first_period_col, max_col + 1):
            label = ws.cell(header_row, col).value
            if not label:
                continue
            label = str(label).strip()
            if period_passes(label, years, period_types, exact_periods, period_regex):
                period_columns.append((col, label))

        if not period_columns:
            return fail(args.ticker, "过滤后无匹配的 period 列，请放宽 --fiscal-years / --fiscal-periods")

        # 抓 report_date 行
        report_date_row = income_cfg["reportDateRow"]
        report_dates: dict[str, Any] = {}
        for col, label in period_columns:
            report_dates[label] = normalize_value(ws.cell(report_date_row, col).value)

        # 抓 metric 行
        metric_col = income_cfg["metricColumn"]
        unit_col = income_cfg["unitColumn"]
        data_start_row = income_cfg["dataStartRow"]

        metrics_out: list[dict[str, Any]] = []
        for row in range(data_start_row, ws.max_row + 1):
            metric_name = ws.cell(row, metric_col).value
            if not metric_name:
                continue
            metric_name = str(metric_name).strip()
            if metric_filters and not any(f in metric_name for f in metric_filters):
                continue

            unit = ws.cell(row, unit_col).value
            values: dict[str, Any] = {}
            for col, label in period_columns:
                values[label] = normalize_value(ws.cell(row, col).value)

            metrics_out.append({
                "metric": metric_name,
                "unit": str(unit).strip() if unit else None,
                "values": values,
            })

        emit({
            "success": True,
            "ticker": args.ticker.upper(),
            "source": excel_path.name,
            "sheet": ws.title,
            "periods": [label for _, label in period_columns],
            "report_dates": report_dates,
            "metrics": metrics_out,
            "count": len(metrics_out),
        }, args.pretty)
        return 0
    finally:
        wb.close()


if __name__ == "__main__":
    sys.exit(main())
