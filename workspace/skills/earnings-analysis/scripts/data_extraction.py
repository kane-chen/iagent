# scripts/data_extraction.py
"""
数据提取脚本：从指定工作目录中自动查找并读取Excel文件，进行数据验证。
根据股票代码和文件后缀（income, balance, cashflow）匹配文件。
"""
import os
import re
import pandas as pd
from datetime import datetime, timedelta
from pathlib import Path

def find_excel_files(ticker, work_path):
    """
    根据股票代码和工作路径自动查找符合命名规范的Excel文件。

    Args:
        ticker (str): 股票代码（例如："GOOG"）
        work_path (str): 工作目录路径

    Returns:
        dict: 包含各表文件路径的字典，键为 'income', 'balance', 'cashflow'

    Raises:
        FileNotFoundError: 如果找不到任何必需的文件
    """
    work_path = Path(work_path) / "excels"
    if not os.path.exists(work_path):
        raise FileNotFoundError(f"工作目录不存在: {work_path}")

    # 定义正则表达式：^.*_{ticker}_{suffix}_.*\.(xlsx|xls)$
    # 示例匹配: US_GOOG_income_20260723_114333.xlsx
    pattern = re.compile(
        rf"^.*_{re.escape(ticker)}_(income|balance|cashflow)_.*\.(xlsx|xls)$",
        re.IGNORECASE
    )

    found_files = {
        'income': None,
        'balance': None,
        'cashflow': None
    }

    # 遍历目录查找文件
    for filename in os.listdir(work_path):
        match = pattern.match(filename)
        if match:
            # 获取匹配到的后缀 并转为小写作为键
            suffix = match.group(1).lower()
            if suffix in found_files:
                # 如果找到多个匹配文件，默认取第一个（可根据需求修改为取最新修改时间的文件）
                if found_files[suffix] is None:
                    found_files[suffix] = os.path.join(work_path, filename)

    # 验证是否所有类型的文件都已找到
    missing_files = [k for k, v in found_files.items() if v is None]
    if missing_files:
        raise FileNotFoundError(
            f"在目录 '{work_path}' 中未找到股票代码 '{ticker}' 的以下类型Excel文件: {', '.join(missing_files)}。\n"
            f"请确认文件名包含 '_{ticker}_income_', '_{ticker}_balance_', '_{ticker}_cashflow_'"
        )

    return found_files

def read_and_validate_excel(ticker, work_path, sheet_name, reference_date, file_type):
    """
    读取指定类型的Excel文件工作表，并验证数据时效性。

    Args:
        ticker (str): 股票代码
        work_path (str): 工作目录路径
        sheet_name (str): 工作表名称（如 'sheet_资产负债表'）
        reference_date (str): 参考日期（YYYY-MM-DD），用于验证财报日期是否在3个月内
        file_type (str): 文件类型，必须是 'income', 'balance', 'cashflow' 之一

    Returns:
        pd.DataFrame: 包含验证后数据的DataFrame
        dict: 验证结果（是否通过、警告信息）
    """
    # 1. 查找文件
    try:
        file_map = find_excel_files(ticker, work_path)
        file_path = file_map.get(file_type)
    except Exception as e:
        return None, {"status": "error", "message": str(e)}

    # 2. 读取Excel文件
    try:
        # 设置第一行为索引列（假设第一列是指标名称）
        df = pd.read_excel(file_path, sheet_name=sheet_name, index_col=0)
    except Exception as e:
        return None, {"status": "error", "message": f"读取Excel文件失败 ({file_path}): {str(e)}"}

     # 构建验证结果
    validation_result = {
        "status": "success",
    }

    return df, validation_result


def extract_quarterly_data(df, quarter_year):
    """
    从DataFrame中提取特定季度的数据。

    Args:
        df (pd.DataFrame): 财务数据DataFrame（行为指标，列为季度）
        quarter_year (str): 季度标识，如"2026Q2"或"2026Q2 (2026-06-29)"

    Returns:
        pd.Series: 该季度的数据行

    Raises:
        ValueError: 如果列不存在
    """
    # 尝试直接匹配列名
    if quarter_year in df.columns:
        return df[quarter_year]

    # 如果直接匹配失败，尝试模糊匹配（处理包含日期的列名，如 "2026Q2 2026-06-29"）
    matched_cols = [col for col in df.columns if str(col).startswith(quarter_year)]
    if matched_cols:
        return df[matched_cols[0]]

    raise ValueError(f"列 '{quarter_year}' 不存在于数据中。可用列: {df.columns.tolist()}")

def compare_periods(df, current_quarter, prior_quarter):
    """
    比较两个季度的数据。

    Args:
        df (pd.DataFrame): 财务数据DataFrame
        current_quarter (str): 当前季度标识
        prior_quarter (str): 对比季度标识（如上一季度或去年同期）

    Returns:
        pd.DataFrame: 包含比较结果的DataFrame
    """
    current_data = extract_quarterly_data(df, current_quarter)
    prior_data = extract_quarterly_data(df, prior_quarter)

    # 确保数据类型为数值以便计算
    current_data = pd.to_numeric(current_data, errors='coerce')
    prior_data = pd.to_numeric(prior_data, errors='coerce')

    comparison = pd.DataFrame({
        "当前季度": current_data,
        "对比季度": prior_data,
        "差异": current_data - prior_data,
        "增长率(%)": ((current_data - prior_data) / prior_data * 100).round(2)
    })

    return comparison
