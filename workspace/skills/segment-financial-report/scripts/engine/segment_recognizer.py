# -*- coding: utf-8 -*-
"""Segment 识别器：优先按公司配置匹配，配置未命中时走结构启发式回退。"""
from __future__ import annotations

import logging
from typing import List, Optional

from .model import CompanyConfig, FinancialTable, Segment, SegmentConfig, TableRow

logger = logging.getLogger(__name__)


class SegmentRecognizer:
    def __init__(self, companyConfig: Optional[CompanyConfig]):
        self.companyConfig = companyConfig

    def getCompanyConfig(self) -> Optional[CompanyConfig]:
        return self.companyConfig

    def recognizeSegments(self, table: FinancialTable) -> List[Segment]:
        logger.info("Recognizing segments from table: %s", table.getTitle())
        config_based = self._recognize_by_config(table)
        if config_based:
            self._build_hierarchy(config_based)
            return config_based
        return self._recognize_by_structure(table)

    def _recognize_by_config(self, table: FinancialTable) -> List[Segment]:
        segments: List[Segment] = []
        if self.companyConfig is None or not self.companyConfig.segments:
            return segments
        rows = table.getRows()
        for sc in self.companyConfig.segments:
            matched_row = self._find_matching_row(table, sc)
            if matched_row is not None:
                seg = Segment()
                seg.segmentCode = sc.segmentCode
                seg.segmentName = sc.segmentName
                seg.level = sc.level
                seg.sortOrder = rows.index(matched_row)
                segments.append(seg)
        return segments

    def _find_matching_row(self, table: FinancialTable, sc: SegmentConfig) -> Optional[TableRow]:
        for row in table.getRows():
            if sc.matches(row.getLabel()):
                return row
        return None

    def _recognize_by_structure(self, table: FinancialTable) -> List[Segment]:
        segments: List[Segment] = []
        stack: List[Segment] = []
        rows = table.getRows()
        for row in rows:
            label = row.getLabel()
            if not label or not label.strip():
                continue
            if row.isTotalRow or row.isSubtotalRow:
                continue
            if not self._is_likely_segment_name(label):
                continue
            indent = row.indentLevel
            seg = Segment()
            seg.segmentName = label.strip()
            seg.level = indent + 1
            seg.sortOrder = rows.index(row)
            while stack and stack[-1].level >= seg.level:
                stack.pop()
            if stack:
                stack[-1].addChild(seg)
            else:
                segments.append(seg)
            stack.append(seg)
        return segments

    _FINANCIAL_KEYWORDS = [
        "revenue", "income", "profit", "loss", "expense", "cost", "margin",
        "earnings", "per share", "eps", "ads", "cash", "asset", "liability",
        "equity", "depreciation", "amortization", "tax", "interest",
        "ebit", "ebitda", "ebita", "adjusted", "non-gaap", "gaap",
        "收入", "利润", "亏损", "成本", "费用", "支出", "收益", "每股",
        "现金", "资产", "负债", "权益", "折旧", "摊销", "税", "利息",
        "经调整", "调整后", "非公认", "公认会计",
        "numerator", "denominator", "basic", "diluted", "share", "shares",
        "months", "year", "quarter", "0-3", "3-6", "6-12", "over",
        "accounts receivable", "accounts payable", "current", "deferred",
        "impairment", "goodwill", "intangible", "buyer protection",
        "less:", "add:", "for the", "for the three", "for the six",
        "减值", "商誉", "无形资产", "买家保障", "存款",
    ]

    def _is_likely_segment_name(self, label: str) -> bool:
        if not label or not label.strip():
            return False
        trimmed = label.strip()
        if len(trimmed) < 2 or len(trimmed) > 100:
            return False
        lower = trimmed.lower()
        for kw in self._FINANCIAL_KEYWORDS:
            if kw in lower:
                return False
        digit_count = sum(1 for c in trimmed if c.isdigit())
        if digit_count > 0 and digit_count / len(trimmed) > 0.2:
            return False
        return True

    def _build_hierarchy(self, segments: List[Segment]) -> None:
        if self.companyConfig is None or not self.companyConfig.segments:
            return
        seg_map = {s.segmentCode: s for s in segments}
        for s in segments:
            cfg = self._find_segment_config(s.segmentCode)
            if cfg and cfg.parentCode:
                parent = seg_map.get(cfg.parentCode)
                if parent is not None:
                    parent.addChild(s)

    # exposed for handlers
    def match(self, label: Optional[str], segmentCode: Optional[str]) -> bool:
        cfg = self._find_segment_config(segmentCode)
        if cfg is None:
            return False
        return cfg.matches(label)

    def _find_segment_config(self, segmentCode: Optional[str]) -> Optional[SegmentConfig]:
        if self.companyConfig is None or not self.companyConfig.segments or segmentCode is None:
            return None
        for c in self.companyConfig.segments:
            if c.segmentCode == segmentCode:
                return c
        return None
