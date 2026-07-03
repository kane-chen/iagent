# -*- coding: utf-8 -*-
"""Data models — ported from io.invest.iagent.service.extraction.model.

Fields intentionally use camelCase to match the JSON produced by the Java engine
and to keep JSON configs cross-compatible.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import List, Optional


# ---------------------------------------------------------------------------
# TableCell — ported from TableCell.java
# ---------------------------------------------------------------------------
class TableCell:
    __slots__ = ("text", "numericValue", "isNegative", "isParentheses", "unit")

    def __init__(self, text: Optional[str] = None):
        self.text: Optional[str] = None
        self.numericValue: Optional[float] = None
        self.isNegative: bool = False
        self.isParentheses: bool = False
        self.unit: Optional[str] = None
        if text is not None:
            self.setText(text)

    def setText(self, text: Optional[str]):
        self.text = text
        self._parse()

    def getText(self) -> Optional[str]:
        return self.text

    def getNumericValue(self) -> Optional[float]:
        return self.numericValue

    def isNumeric(self) -> bool:
        return self.numericValue is not None

    def _parse(self):
        # ported from TableCell.parseNumericValue
        if self.text is None or not self.text.strip():
            self.numericValue = None
            return
        clean = self.text.strip()
        neg = False
        parens = False
        if clean.startswith("(") and clean.endswith(")"):
            parens = True
            neg = True
            clean = clean[1:-1]
        if clean.startswith("-"):
            neg = True
            clean = clean[1:]
        for token in ("$", "¥", "RMB", "US$", ",", "%"):
            clean = clean.replace(token, "")
        clean = clean.strip()
        self.isParentheses = parens
        self.isNegative = neg
        try:
            v = float(clean)
            self.numericValue = -v if neg else v
        except ValueError:
            self.numericValue = None


# ---------------------------------------------------------------------------
# TableRow — ported from TableRow.java
# ---------------------------------------------------------------------------
class TableRow:
    __slots__ = ("label", "cells", "indentLevel", "isTotalRow", "isSubtotalRow", "isBold")

    def __init__(self):
        self.label: Optional[str] = None
        self.cells: List[TableCell] = []
        self.indentLevel: int = 0
        self.isTotalRow: bool = False
        self.isSubtotalRow: bool = False
        self.isBold: bool = False

    def addCell(self, cell: TableCell):
        self.cells.append(cell)

    def getLabel(self) -> Optional[str]:
        return self.label

    def getCells(self) -> List[TableCell]:
        return self.cells


# ---------------------------------------------------------------------------
# FinancialTable — ported from FinancialTable.java
# ---------------------------------------------------------------------------
class FinancialTable:
    def __init__(self):
        self.tableId: Optional[str] = None
        self.title: Optional[str] = None
        self.headers: List[str] = []
        self.rows: List[TableRow] = []
        self.footnotes: List[str] = []
        self.currency: Optional[str] = None
        self.unit: Optional[str] = None
        self.period: Optional[str] = None

    def getTitle(self):
        return self.title

    def getHeaders(self):
        return self.headers

    def getRows(self):
        return self.rows

    def getUnit(self):
        return self.unit

    def getCurrency(self):
        return self.currency

    def getTableId(self):
        return self.tableId


# ---------------------------------------------------------------------------
# SegmentMetric — ported from SegmentMetric.java
# ---------------------------------------------------------------------------
class SegmentMetric:
    def __init__(self):
        self.metricCode: Optional[str] = None
        self.metricName: Optional[str] = None
        self.value: Optional[float] = None
        self.yoyGrowth: Optional[float] = None
        self.confidenceScore: Optional[int] = 80
        self.sourceType: Optional[str] = None
        self.sourceLocation: Optional[str] = None
        self.currency: Optional[str] = None
        self.unit: Optional[str] = None
        self.period: Optional[str] = None
        self.segment: Optional["Segment"] = None

    def getMetricCode(self): return self.metricCode
    def getPeriod(self): return self.period
    def getValue(self): return self.value
    def getMetricName(self): return self.metricName
    def getCurrency(self): return self.currency
    def getUnit(self): return self.unit


# ---------------------------------------------------------------------------
# Segment — ported from Segment.java
# ---------------------------------------------------------------------------
class Segment:
    def __init__(self, segmentName: Optional[str] = None, level: int = 1):
        self.segmentId: Optional[str] = None
        self.segmentName: Optional[str] = segmentName
        self.segmentCode: Optional[str] = None
        self.level: int = level
        self.sortOrder: int = 0
        self.parent: Optional["Segment"] = None
        self.children: List["Segment"] = []
        self.metrics: List[SegmentMetric] = []

    def addChild(self, child: "Segment"):
        child.parent = self
        self.children.append(child)

    def addMetric(self, metric: SegmentMetric):
        metric.segment = self
        self.metrics.append(metric)

    def getSegmentCode(self):
        return self.segmentCode

    def getSegmentName(self):
        return self.segmentName

    def getLevel(self):
        return self.level

    def getMetrics(self):
        return self.metrics

    def getChildren(self):
        return self.children

    def setMetrics(self, metrics: List[SegmentMetric]):
        self.metrics = metrics

    def getMetric(self, metricCode: str, period: Optional[str] = None) -> Optional[SegmentMetric]:
        # ported from Segment.getMetric
        if metricCode is None:
            return None
        for m in self.metrics:
            if m.metricCode is not None and m.metricCode.lower() == metricCode.lower():
                if period is None or period == "" or period == m.period:
                    return m
        return None


# ---------------------------------------------------------------------------
# MetricDict — ported from MetricDict.java
# ---------------------------------------------------------------------------
@dataclass
class MetricDict:
    metricCode: Optional[str] = None
    metricName: Optional[str] = None
    metricCategory: Optional[str] = None
    synonyms: List[str] = field(default_factory=list)
    formula: Optional[str] = None
    isStandard: bool = True

    def matches(self, text: Optional[str]) -> bool:
        if text is None:
            return False
        lower = text.lower().strip()
        if self.metricName and lower.find(self.metricName.lower()) >= 0:
            return True
        if self.metricCode and lower.find(self.metricCode.lower()) >= 0:
            return True
        for s in self.synonyms:
            if lower.find(s.lower()) >= 0:
                return True
        return False


# ---------------------------------------------------------------------------
# CompanyConfig — ported from CompanyConfig.java (nested SegmentConfig / MetricMappingRule)
# ---------------------------------------------------------------------------
@dataclass
class SegmentConfig:
    segmentCode: Optional[str] = None
    segmentName: Optional[str] = None
    aliases: List[str] = field(default_factory=list)
    level: int = 1
    parentCode: Optional[str] = None

    def matches(self, text: Optional[str]) -> bool:
        # ported from CompanyConfig.SegmentConfig.matches
        if text is None:
            return False
        lower = text.lower().strip()
        if self.segmentName and lower.find(self.segmentName.lower()) >= 0:
            return True
        if self.segmentCode and lower.find(self.segmentCode.lower()) >= 0:
            return True
        for alias in self.aliases:
            if lower.find(alias.lower()) >= 0:
                return True
        return False


@dataclass
class MetricMappingRule:
    standardMetricCode: Optional[str] = None
    rawMetricNames: List[str] = field(default_factory=list)
    formula: Optional[str] = None


@dataclass
class CompanyConfig:
    companyCode: Optional[str] = None
    companyName: Optional[str] = None
    market: Optional[str] = None
    defaultCurrency: Optional[str] = None
    defaultUnit: Optional[str] = None
    includePeriodTypes: List[str] = field(default_factory=list)
    htmlLayout: Optional[str] = None
    segments: List[SegmentConfig] = field(default_factory=list)
    metricMappingRules: List[MetricMappingRule] = field(default_factory=list)
