# -*- coding: utf-8 -*-
"""PdfExtractionSupport — port of io.invest.iagent.service.extraction.parser.PdfExtractionSupport.

Shared numeric/segment/verification helpers for PDF layout handlers.
"""
from __future__ import annotations

from typing import Dict, List, Optional

from .html_support import normalizeToMillion
from .model import CompanyConfig, Segment, SegmentConfig, SegmentMetric


class PdfExtractionSupport:
    def __init__(self, companyConfig: CompanyConfig):
        self.companyConfig = companyConfig

    # ------------- numeric / placeholder helpers -------------

    def parseNumber(self, text: Optional[str]) -> Optional[float]:
        if text is None:
            return None
        t = text.strip()
        if not t:
            return None
        if t.endswith("%") or t in ("-", "–", "—", "*"):
            return None
        neg = False
        if t.startswith("(") and t.endswith(")"):
            neg = True
            t = t[1:-1]
        t = t.replace(",", "").replace(" ", "")
        if t.startswith("-"):
            neg = True
            t = t[1:]
        if not t:
            return None
        try:
            v = float(t)
            return -v if neg else v
        except ValueError:
            return None

    @staticmethod
    def isPlaceholderCell(c: Optional[str]) -> bool:
        if c is None:
            return True
        s = c.strip()
        return s == "" or s in ("-", "–", "—", "*")

    @staticmethod
    def isSkipColumn(segCode: Optional[str]) -> bool:
        if segCode is None or segCode == "":
            return True
        return segCode.upper() in ("TOTAL", "SKIP")

    def isAllNumericRow(self, row: List[str]) -> bool:
        numeric = 0
        for c in row:
            if self.parseNumber(c) is not None:
                numeric += 1
            elif not self.isPlaceholderCell(c):
                return False
        return numeric >= 2

    # ------------- TOTAL consistency verification -------------

    @staticmethod
    def lastIndexOfTotal(segmentCodes: List[str]) -> int:
        for i in range(len(segmentCodes) - 1, -1, -1):
            c = segmentCodes[i]
            if c is not None and c.upper() == "TOTAL":
                return i
        return -1

    def verifyTotalCell(self, row: List[str], totalIdx: int, segmentCodes: List[str]) -> bool:
        if totalIdx < 0 or totalIdx >= len(row):
            return False
        totalVal = self.parseNumber(row[totalIdx])
        if totalVal is None:
            return False
        s = 0.0
        contributors = 0
        for col in range(min(len(segmentCodes), len(row))):
            if col == totalIdx:
                continue
            code = segmentCodes[col]
            if code is None or code == "" or code.upper() == "TOTAL":
                continue
            v = self.parseNumber(row[col])
            if v is None:
                continue
            s += v
            contributors += 1
        if contributors < 2:
            return False
        diff = abs(s - totalVal)
        tol = max(1.0, abs(totalVal) * 0.005)
        return diff <= tol

    def verifyTotalRow(self, qualifiedRows: List[List[str]], totalIdx: int,
                        dataCols: int, dataStartCol: int,
                        segmentCodes: List[str]) -> bool:
        totalRow = qualifiedRows[totalIdx]
        passed = 0
        for col in range(dataCols):
            totalVal = self.parseNumber(totalRow[col + dataStartCol])
            if totalVal is None:
                continue
            s = 0.0
            contributors = 0
            for r in range(min(len(segmentCodes), len(qualifiedRows))):
                if r == totalIdx:
                    continue
                code = segmentCodes[r]
                if code is None or code == "" or code.upper() == "TOTAL":
                    continue
                v = self.parseNumber(qualifiedRows[r][col + dataStartCol])
                if v is None:
                    continue
                s += v
                contributors += 1
            if contributors < 2:
                continue
            diff = abs(s - totalVal)
            tol = max(1.0, abs(totalVal) * 0.005)
            if diff <= tol:
                passed += 1
        return passed >= 1

    # ------------- Segment assembly -------------

    def addMetric(self, sink: Dict[str, Segment],
                  segCode: str, metricCode: str, period: str, value: float,
                  tableId: str, currency: Optional[str], unit: Optional[str],
                  take_abs: bool = False) -> None:
        if take_abs:
            value = abs(value)
        segment = self._getOrCreate(sink, segCode)
        if segment.getMetric(metricCode, period) is not None:
            return
        m = SegmentMetric()
        m.metricCode = metricCode
        m.metricName = metricCode
        m.value = normalizeToMillion(value, unit)
        m.period = period
        m.sourceType = "TABLE_EXTRACT"
        m.sourceLocation = tableId
        m.currency = currency
        m.unit = "million"
        m.confidenceScore = 80
        segment.addMetric(m)

    def addMetricToSegment(self, segment: Segment, metricCode: str, period: str, value: float,
                           tableId: str, currency: Optional[str], unit: Optional[str],
                           take_abs: bool = False) -> None:
        if segment is None:
            return
        if segment.getMetric(metricCode, period) is not None:
            return
        v = abs(value) if take_abs else value
        m = SegmentMetric()
        m.metricCode = metricCode
        m.metricName = metricCode
        m.value = normalizeToMillion(v, unit)
        m.period = period
        m.sourceType = "TABLE_EXTRACT"
        m.sourceLocation = tableId
        m.currency = currency
        m.unit = "million"
        m.confidenceScore = 80
        segment.addMetric(m)

    def findSegmentConfig(self, segCode: str) -> Optional[SegmentConfig]:
        if self.companyConfig is None or not self.companyConfig.segments:
            return None
        for sc in self.companyConfig.segments:
            if sc.segmentCode is not None and sc.segmentCode.lower() == segCode.lower():
                return sc
        return None

    def _getOrCreate(self, sink: Dict[str, Segment], segCode: str) -> Segment:
        if segCode in sink:
            return sink[segCode]
        s = Segment()
        s.segmentCode = segCode
        sc = self.findSegmentConfig(segCode)
        if sc is not None:
            s.segmentName = sc.segmentName
            s.level = sc.level if (sc.level and sc.level > 0) else 1
        else:
            s.segmentName = segCode
            s.level = 1
        sink[segCode] = s
        return s
