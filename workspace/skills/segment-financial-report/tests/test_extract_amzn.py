# -*- coding: utf-8 -*-
"""Amazon 8-K 业绩新闻稿季度趋势附表回归。

8-K 附带的季度趋势表列头为显式的 "Q4 2023" / "Q1 2024" / ... / "Q1 2025"。
引擎必须逐列采用该显式季/年标记；若只抽年份、再按默认季度兜底，会把多个季度列
错标成同一季（曾把 Q1 值打成 Q3，并在跨文件合并时挤掉 10-Q 的正确 Q3 值）。
"""
from __future__ import annotations

from pathlib import Path

from engine.extraction_service import FinancialExtractionService

from conftest import local_value


def test_extract_amzn_8k_quarterly_trend(workspace: Path):
    file = workspace / "financial_reports/US/AMZN/AMZN_20250501_8-K.htm"
    svc = FinancialExtractionService(companyCode="AMZN", workspace=workspace)
    segments = svc.extractFromHtmlFile(file)
    assert segments is not None

    # 趋势附表各列应映射到各自真实季度（AWS 净销售额，单位：百万美元）
    assert local_value(segments, "AWS", "REVENUE", "2023Q4") == 24204
    assert local_value(segments, "AWS", "REVENUE", "2024Q1") == 25037
    assert local_value(segments, "AWS", "REVENUE", "2024Q2") == 26281
    assert local_value(segments, "AWS", "REVENUE", "2024Q3") == 27452
    assert local_value(segments, "AWS", "REVENUE", "2024Q4") == 28786
    assert local_value(segments, "AWS", "REVENUE", "2025Q1") == 29267

    # 关键回归：Q1 新闻稿不得把 Q1 数值泄漏到 Q3（修复前此处错误地等于 2025Q1）
    assert local_value(segments, "AWS", "REVENUE", "2025Q3") is None
