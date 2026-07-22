# -*- coding: utf-8 -*-
"""
CSV 财务报表解析器——兼容旧格式的扁平 ticker_前缀 CSV 文件。
"""

from pathlib import Path
from typing import Any, Optional, Tuple

import pandas as pd

from ..storage import PERIOD_RE, period_sort_key, safe_float


def parse_financials_csv(
    files: list[Path],
    period: str = "quarterly",
    num_periods: int = 8,
) -> Tuple[dict, dict, dict, dict]:
    """解析本地 CSV 格式的三大表 + 财务指标。

    返回 (income, balance, cashflow, indicators)，每项为 {period: {metric: value}}。
    """
    income_df = balance_df = cashflow_df = indicators_df = None

    for f in files:
        stem_lower = f.stem.lower()
        if f.suffix.lower() in (".xlsx", ".xls"):
            # 旧格式 Excel 仍按表格读入，再走统一 DataFrame 转换
            df = pd.read_excel(f)
        else:
            try:
                df = pd.read_csv(f, encoding="utf-8")
            except UnicodeDecodeError:
                df = pd.read_csv(f, encoding="gbk")
        if "income" in stem_lower:
            income_df = df
        elif "balance" in stem_lower:
            balance_df = df
        elif "cashflow" in stem_lower or "cash_flow" in stem_lower:
            cashflow_df = df
        elif "indicator" in stem_lower:
            indicators_df = df

    income = _df_to_period_map(income_df, period, num_periods) if income_df is not None else {}
    balance = _df_to_period_map(balance_df, period, num_periods) if balance_df is not None else {}
    cashflow = _df_to_period_map(cashflow_df, period, num_periods) if cashflow_df is not None else {}
    indicators = _df_to_period_map(indicators_df, period, num_periods) if indicators_df is not None else {}

    return income, balance, cashflow, indicators


def _df_to_period_map(df: pd.DataFrame, period: str, num_periods: int) -> dict:
    """将 DataFrame 转为 {period: {metric: value}}。

    支持两种布局：
      - 宽表：第 1 列为指标名（item/metric），其余列是期间
      - 长表：有 period 列，其余列是指标
    """
    if df.empty:
        return {}

    # 尝试识别长表布局：有一列名为 period/期间
    period_col = None
    for col in df.columns:
        if str(col).strip().lower() in ("period", "期间", "fiscal_period"):
            period_col = col
            break

    if period_col is not None:
        return _parse_long(df, period_col, period, num_periods)
    else:
        return _parse_wide(df, period, num_periods)


def _parse_wide(df: pd.DataFrame, period: str, num_periods: int) -> dict:
    """宽表布局：第 1 列为指标名，其余列是期间。"""
    first_col = df.columns[0]
    # 尝试将第一列作为指标名
    metrics = df[first_col].astype(str).tolist()

    # 收集期间列
    period_cols = []
    for col in df.columns[1:]:
        label = str(col).strip()
        if PERIOD_RE.match(label):
            if period == "quarterly" and label.endswith("FY"):
                continue
            if period == "annual" and not label.endswith("FY"):
                continue
            period_cols.append(label)

    # 按期间倒序排列，截取 num_periods
    period_cols.sort(key=period_sort_key, reverse=True)
    period_cols = period_cols[:num_periods]

    result: dict[str, dict] = {}
    for p in period_cols:
        series = df[p]
        period_data: dict[str, Optional[float]] = {}
        for i, metric_name in enumerate(metrics):
            if i < len(series):
                val = safe_float(series.iloc[i])
                if val is not None:
                    period_data[metric_name] = val
        result[p] = period_data
    return result


def _parse_long(df: pd.DataFrame, period_col: str, period: str, num_periods: int) -> dict:
    """长表布局：有 period 列，其余列是指标。"""
    result: dict[str, dict] = {}
    for _, row in df.iterrows():
        p_label = str(row[period_col]).strip()
        if not PERIOD_RE.match(p_label):
            continue
        if period == "quarterly" and p_label.endswith("FY"):
            continue
        if period == "annual" and not p_label.endswith("FY"):
            continue

        period_data: dict[str, Optional[float]] = {}
        for col in df.columns:
            if col == period_col:
                continue
            val = safe_float(row[col])
            if val is not None:
                period_data[str(col)] = val
        result[p_label] = period_data

    # 按期间倒序，截取 num_periods
    sorted_periods = sorted(result.keys(), key=period_sort_key, reverse=True)
    sorted_periods = sorted_periods[:num_periods]
    return {p: result[p] for p in sorted_periods}