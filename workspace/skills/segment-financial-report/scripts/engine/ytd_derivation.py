# -*- coding: utf-8 -*-
"""从 YTD 合计推导单季度数据：Q4 = FY − QTD9，Q2 = QTD6 − Q1。

Microsoft 等公司的 10-K（FY 年报）只披露全年合计数；Q2（H1 第二季度的）和 Q3
(9-month 第三季度)分别在半年报/三季报里是 H1 和 9M YTD。这些单季度数字通过减法
即可得出，避免下游用户手工计算。

规则：
- Q4 = FY − 9-month YTD（同一财年）
- Q2 = QTD6 − Q1（同一财年）
- Q4 和 Q2 只有在被减数（FY / QTD6）以及减数（QTD9 / Q1）都存在时才会被推导。
- 派生出来的 metric 标记 sourceType=DERIVED 和 confidenceScore=60。
- 派生结果若已有直接抽取值则保留原值（不覆盖）。
"""
from __future__ import annotations

import logging
from typing import Dict, List, Optional, Tuple

from .model import Segment, SegmentMetric

logger = logging.getLogger(__name__)


_YTD_SUFFIX_TO_DERIVED = (
    # (ytd_suffix, prior_quarter_suffix, derived_quarter_suffix)
    # QTD9 is 9-month YTD (Q1+Q2+Q3), so Q4 = FY - QTD9 of same FY
    ("QTD9", "Q3", "Q4"),
    # QTD6 is 6-month YTD (H1 = Q1+Q2), so Q2 = QTD6 - Q1 of same FY
    ("QTD6", "Q1", "Q2"),
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

    for (ytd_suffix, prior_suffix, q_suffix) in _YTD_SUFFIX_TO_DERIVED:
        added += _derive_one(seg, idx, ytd_suffix, prior_suffix, q_suffix)
    return added


def _derive_one(seg: Segment, idx: Dict[Tuple[str, str], SegmentMetric],
                ytd_suffix: str, prior_suffix: str, q_suffix: str) -> int:
    """Derive single quarters from YTD for all years present.

    Logic for QTD9 -> Q4:
      For each FYyyyy metric, find QTD9 for the same yyyy; if both exist and
      yyyyQ4 is not already present, compute Q4 = FY - QTD9.

    For QTD6 -> Q2:
      For each yyyyQTD6 metric, find yyyyQ1; compute Q2 = QTD6 - Q1.
    """
    added = 0
    # Determine which base suffix to iterate: FY for Q4, QTD6 for Q2
    if q_suffix == "Q4":
        base_suffix = "FY"
    else:
        base_suffix = ytd_suffix

    for (code, period), base_m in list(idx.items()):
        if not period.endswith(base_suffix):
            continue
        year = period[:-len(base_suffix)]
        if not year.isdigit():
            continue
        ytd_period = f"{year}{ytd_suffix}"
        prior_period = f"{year}{prior_suffix}"
        target_period = f"{year}{q_suffix}"
        if (code, target_period) in idx:
            continue  # already extracted directly
        ytd_m = idx.get((code, ytd_period))
        if ytd_m is None:
            continue
        # For Q4 we also need prior quarter (Q3) to validate, but we really just
        # need FY - QTD9. For Q2 we need Q1.
        prior_m = idx.get((code, prior_period))
        if q_suffix == "Q2" and prior_m is None:
            continue
        # Compute
        if base_m.value is None or ytd_m.value is None:
            continue
        derived_value = base_m.value - ytd_m.value
        m = SegmentMetric()
        m.metricCode = code
        m.metricName = base_m.metricName
        m.period = target_period
        m.value = derived_value
        m.currency = base_m.currency
        m.unit = base_m.unit or "million"
        m.sourceType = "DERIVED"
        m.sourceLocation = f"derived:{base_m.sourceLocation}:{period}-{ytd_period}"
        m.confidenceScore = 60
        seg.addMetric(m)
        idx[(code, target_period)] = m
        added += 1
        logger.debug("derived %s %s = %s (%.0f) - %s (%.0f) = %.0f",
                     seg.segmentCode, target_period, period, base_m.value,
                     ytd_period, ytd_m.value, derived_value)
    return added
