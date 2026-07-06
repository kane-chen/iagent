# -*- coding: utf-8 -*-
"""Section 标题检测器：启发式识别冒号/加粗/空行等 section 标题行。"""
from __future__ import annotations

from .model import TableRow


def isStandaloneLabel(label: str) -> bool:
    if label is None:
        return False
    if len(label) >= 50:
        return False
    digit_count = sum(1 for c in label if c.isdigit())
    return digit_count == 0 or (digit_count / len(label)) < 0.2


def hasNoDataCells(row: TableRow) -> bool:
    if row is None or not row.getCells():
        return True
    for c in row.getCells():
        t = c.getText()
        if t is not None and t.strip():
            return False
    return True


def isSectionTitleRow(row: TableRow, lower_label: str) -> bool:
    if not lower_label:
        return False
    if lower_label.endswith(":") or "total" in lower_label:
        return True
    if not isStandaloneLabel(lower_label):
        return False
    if row.isBold:
        return True
    return hasNoDataCells(row)
