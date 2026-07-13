#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
查询公司分部损益（segments）—— 从 workspace/excels/{TICKER}_segments_*.xlsx。

流程：
  1. 按 ticker 找最新版本文件
  2. 打开 sheet
  3. 第 1 行是 period 列头（'2026Q1' / '2024Q3' / ...）
  4. 第 2 行起每行是 (分部, 指标, 各 period 值)；分部名开头的 ├─ / 缩进表示层级
  5. --segment 支持子串过滤；--metric 支持指标名子串过滤；period 三类过滤
  6. 输出 JSON，保留层级信息（level + parent_segment）

用法：
  python query_segments.py --ticker BABA
  python query_segments.py --ticker BABA --segment "Taobao and Tmall Group" --pretty
  python query_segments.py --ticker BABA --metric 收入 --fiscal-years 2024-2025

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
    resolve_excels_dir,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="查询公司分部损益（segments excel）",
    )
    parser.add_argument("--ticker", required=True, help="股票代码，例如 BABA / 00700")
    parser.add_argument(
        "--segment",
        default=None,
        help="分部名子串过滤，例如 'Cloud'；留空返回全部分部",
    )
    parser.add_argument(
        "--metric",
        default=None,
        help="指标名子串过滤，例如 '收入' / 'EBITA'；留空返回全部指标",
    )
    parser.add_argument("--fiscal-years", default=None, help="财年过滤，如 '2024' 或 '2022-2025'")
    parser.add_argument("--fiscal-periods", default=None, help="财期类型，如 'FY,Q3'")
    parser.add_argument("--periods", default=None, help="精确 period 列表，如 '2024Q3,2025FY'")
    parser.add_argument(
        "--max-level",
        type=int,
        default=None,
        help="只返回 level ≤ N 的分部（Level 1 顶层，2/3 子层）",
    )
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--excels-dir", type=Path, default=None)
    parser.add_argument("--workspace", type=Path, default=None,
                        help="workspace 根目录；未传时按 IAGENT_WORKSPACE_DIR / 脚本位置回溯推断")
    parser.add_argument("--pretty", action="store_true")
    return parser.parse_args()


def parse_segment_hierarchy(raw: str, prefixes: list[str]) -> tuple[str, int]:
    """
    从原始 segment 单元格文本中剥离层级前缀。
    输入 '  ├─ China commerce retail' → ('China commerce retail', 2)
    输入 '    ├─ Customer management' → ('Customer management', 3)
    输入 'Taobao and Tmall Group'   → ('Taobao and Tmall Group', 1)

    规则：前导空格数 // 2 + 1 = level；命中的层级前缀 token 都被剥掉。
    """
    original = raw
    leading_spaces = len(raw) - len(raw.lstrip(" "))
    level = (leading_spaces // 2) + 1
    stripped = raw.lstrip(" ")
    # 剥掉层级 token（├─ / └─ / ├ / └ / │），可能出现多次
    changed = True
    while changed:
        changed = False
        for token in prefixes:
            if stripped.startswith(token):
                stripped = stripped[len(token):].lstrip(" ")
                changed = True
                break
    name = stripped.strip()
    return name if name else original.strip(), level


def main() -> int:
    args = parse_args()
    cfg = load_config(args.config)
    seg_cfg = cfg["segments"]

    excels_dir = resolve_excels_dir(cfg, args.excels_dir, args.workspace)
    if not excels_dir.is_dir():
        return fail(args.ticker, f"excels 目录不存在: {excels_dir}")

    excel_path = find_latest_excel(excels_dir, seg_cfg["filenamePattern"], args.ticker)
    if excel_path is None:
        return fail(
            args.ticker,
            f"未找到 {args.ticker} 的 segments excel。目录={excels_dir}，"
            f"预期命名 {args.ticker}_segments_*.xlsx",
        )

    period_regex = re.compile(cfg["periodFilter"]["periodTypeRegex"])
    years, period_types, exact_periods = parse_period_filter(
        args.fiscal_years, args.fiscal_periods, args.periods, period_regex.pattern,
    )

    ws, wb = open_worksheet(excel_path, seg_cfg["sheetName"])
    try:
        header_row = seg_cfg["headerRow"]
        first_period_col = seg_cfg["firstPeriodColumn"]
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

        segment_col = seg_cfg["segmentColumn"]
        metric_col = seg_cfg["metricColumn"]
        data_start_row = seg_cfg["dataStartRow"]
        prefixes: list[str] = seg_cfg["hierarchyPrefixes"]

        rows_out: list[dict[str, Any]] = []
        # 维护 level → 最近的 segment_name，用于给子分部推导 parent_segment
        parent_stack: dict[int, str] = {}

        for row in range(data_start_row, ws.max_row + 1):
            seg_raw = ws.cell(row, segment_col).value
            metric_raw = ws.cell(row, metric_col).value
            if not seg_raw:
                continue
            seg_name, level = parse_segment_hierarchy(str(seg_raw), prefixes)

            # 更新 parent_stack：新 seg 之前，把所有 level ≥ 当前 level 的清掉（不同分支）
            for lv in list(parent_stack.keys()):
                if lv >= level:
                    del parent_stack[lv]
            parent_segment = parent_stack.get(level - 1) if level > 1 else None
            parent_stack[level] = seg_name

            if args.max_level is not None and level > args.max_level:
                continue
            if args.segment and args.segment.lower() not in seg_name.lower():
                continue

            metric_name = str(metric_raw).strip() if metric_raw else ""
            if args.metric and args.metric.lower() not in metric_name.lower():
                continue

            values: dict[str, Any] = {}
            for col, label in period_columns:
                values[label] = normalize_value(ws.cell(row, col).value)

            rows_out.append({
                "segment": seg_name,
                "level": level,
                "parent_segment": parent_segment,
                "metric": metric_name,
                "values": values,
            })

        emit({
            "success": True,
            "ticker": args.ticker.upper(),
            "source": excel_path.name,
            "sheet": ws.title,
            "periods": [label for _, label in period_columns],
            "rows": rows_out,
            "count": len(rows_out),
        }, args.pretty)
        return 0
    finally:
        wb.close()


if __name__ == "__main__":
    sys.exit(main())
