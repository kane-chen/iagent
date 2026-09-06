# -*- coding: utf-8 -*-
"""从 YTD 累计数推导单季度数据：单季 = 截至该季累计 − 截至上一季累计。

Microsoft 等公司的 10-K（FY 年报）只披露全年合计数；H1 报披露 6 个月累计（QTD6）、
三季报披露 9 个月累计（QTD9）。缺报的单季度数字通过相邻累计数相减即可得出：

规则（同一财年）：
- Q2 = QTD6（H1 累计）− Q1
- Q3 = QTD9（9M 累计）− QTD6（H1 累计）
- Q4 = FY（全年）− QTD9（9M 累计）
- 只有在被减数与减数都存在、且目标单季尚无直接抽取值时才推导（不覆盖直接值）。
- 派生出来的 metric 标记 sourceType=DERIVED 和 confidenceScore=60。
"""
from __future__ import annotations

import logging
from typing import Dict, List, Optional, Tuple

from .model import Segment, SegmentMetric

logger = logging.getLogger(__name__)


# (目标单季后缀, 被减数累计后缀, 减数累计后缀)
# 单季 = 截至该季累计 − 截至上一季累计（同一财年）
_DERIVATIONS = (
    # Q2 = H1 累计(QTD6) − Q1
    ("Q2", "QTD6", "Q1"),
    # Q3 = 9M 累计(QTD9) − H1 累计(QTD6)
    ("Q3", "QTD9", "QTD6"),
    # Q4 = 全年(FY) − 9M 累计(QTD9)
    ("Q4", "FY", "QTD9"),
)


def derive_ytd_quarters(segments: List[Segment]) -> int:
    """对 segments 做就地补全，返回新派生的 metric 总数。

    递归处理 children。
    """
    total_added = 0
    for seg in segments:
        total_added += _derive_for_segment(seg)
        if seg.children:
            total_added += derive_ytd_quarters(seg.children)
    return total_added


def _derive_for_segment(seg: Segment) -> int:
    # Index metrics by (metricCode, period)
    idx: Dict[Tuple[str, str], SegmentMetric] = {}
    for m in seg.metrics:
        if m.metricCode and m.period:
            idx[(m.metricCode, m.period)] = m
    added = 0

    for (q_suffix, base_suffix, sub_suffix) in _DERIVATIONS:
        added += _derive_one(seg, idx, q_suffix, base_suffix, sub_suffix)
    return added


def _derive_one(seg: Segment, idx: Dict[Tuple[str, str], SegmentMetric],
                q_suffix: str, base_suffix: str, sub_suffix: str) -> int:
    """对所有年份推导单季：target_q = base(累计至本季) − sub(累计至上季)。

    例如 Q4: 遍历每个 yyyyFY，取同财年 yyyyQTD9，若 yyyyQ4 尚无直接值，
    则 Q4 = FY − QTD9；Q2 = QTD6 − Q1；Q3 = QTD9 − QTD6。
    """
    added = 0
    for (code, period), base_m in list(idx.items()):
        if not period.endswith(base_suffix):
            continue
        year = period[:-len(base_suffix)]
        if not year.isdigit():
            continue
        target_period = f"{year}{q_suffix}"
        if (code, target_period) in idx:
            continue  # 已有直接抽取值，不覆盖
        sub_period = f"{year}{sub_suffix}"
        sub_m = idx.get((code, sub_period))
        if sub_m is None:
            continue
        if base_m.value is None or sub_m.value is None:
            continue
        derived_value = base_m.value - sub_m.value
        m = SegmentMetric()
        m.metricCode = code
        m.metricName = base_m.metricName
        m.period = target_period
        m.value = derived_value
        m.currency = base_m.currency
        m.unit = base_m.unit or "million"
        m.sourceType = "DERIVED"
        m.sourceLocation = f"derived:{base_m.sourceLocation}:{period}-{sub_period}"
        m.confidenceScore = 60
        seg.addMetric(m)
        idx[(code, target_period)] = m
        added += 1
        logger.debug("derived %s %s = %s (%.0f) - %s (%.0f) = %.0f",
                     seg.segmentCode, target_period, period, base_m.value,
                     sub_period, sub_m.value, derived_value)
    return added
