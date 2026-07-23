# scripts/financial_analysis.py
"""
财报分析脚本：计算关键财务指标和增长率。
"""
import pandas as pd
import numpy as np

def calculate_metrics(balance_sheet, income_statement, cash_flow, quarter):
    """
    计算指定季度的关键财务指标。

    Args:
        balance_sheet (pd.DataFrame): 资产负债表数据
        income_statement (pd.DataFrame): 利润表数据
        cash_flow (pd.DataFrame): 现金流量表数据
        quarter (str): 季度标识

    Returns:
        dict: 计算出的财务指标
    """
    # 提取各表该季度的数据
    bs_data = extract_quarterly_data(balance_sheet, quarter)
    is_data = extract_quarterly_data(income_statement, quarter)
    cf_data = extract_quarterly_data(cash_flow, quarter)

    metrics = {}

    # 盈利能力指标
    metrics['gross_profit'] = is_data['毛利']
    metrics['gross_margin'] = is_data['毛利率']
    metrics['operating_profit'] = is_data['营业利润']
    metrics['operating_margin'] = is_data['营业利润率']
    metrics['net_income'] = is_data['归属于母公司股东净利润']
    metrics['net_margin'] = is_data['净利润率']
    metrics['roe'] = is_data['ROE(净资产收益率, 年化)']
    metrics['roa'] = is_data['ROA(总资产收益率, 年化)']

    # 现金流指标
    metrics['operating_cash_flow'] = cf_data['经营活动现金流量净额']
    metrics['free_cash_flow'] = cf_data['自由现金流']
    metrics['fcf_conversion'] = cf_data['自由现金流净利润比(FCF/NI)']

    # 资产负债表指标
    metrics['total_assets'] = bs_data['资产合计']
    metrics['total_liabilities'] = bs_data['资产合计'] - bs_data['归属于母公司股东权益合计']
    metrics['shareholders_equity'] = bs_data['归属于母公司股东权益合计']
    metrics['cash_and_equivalents'] = bs_data['现金和现金等价物']
    metrics['total_debt'] = 0  # 需要从更详细的资产负债表中获取

    return metrics

def analyze_quarterly_trend(df, metric_name, periods=8):
    """
    分析指定指标的季度趋势。

    Args:
        df (pd.DataFrame): 包含历史季度数据的DataFrame
        metric_name (str): 要分析的指标名称
        periods (int): 要分析的季度数

    Returns:
        dict: 趋势分析结果（增长率、标准差等）
    """
    # 提取最近N个季度的数据
    recent_quarters = df.columns[-periods:]
    metric_data = df.loc[metric_name, recent_quarters]

    # 计算环比和同比
    if len(metric_data) > 1:
        qoq_growth = metric_data.pct_change() * 100
        yoy_growth = metric_data.pct_change(4) * 100 if len(metric_data) > 4 else None

    trend_analysis = {
        'data_points': metric_data.tolist(),
        'qoq_growth': qoq_growth.tolist() if qoq_growth is not None else None,
        'yoy_growth': yoy_growth.tolist() if yoy_growth is not None else None,
        'mean_growth': qoq_growth.mean() if qoq_growth is not None else None,
        'volatility': qoq_growth.std() if qoq_growth is not None else None,
        'trend': 'increasing' if qoq_growth.mean() > 0 else 'decreasing' if qoq_growth.mean() < 0 else 'stable'
    }

    return trend_analysis
