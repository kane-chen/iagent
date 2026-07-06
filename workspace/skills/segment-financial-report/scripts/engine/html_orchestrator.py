# -*- coding: utf-8 -*-
"""HTML 报告解析编排器：按优先级分派到各 layout handler。"""
from __future__ import annotations

import logging
from typing import Dict, List, Optional

from .data_extractor import DataExtractor
from .handlers.generic_html import GenericHtmlLayoutHandler
from .handlers.segment_contribution import SegmentContributionHandler
from .handlers.segment_row_period_column import SegmentRowPeriodColumnHandler
from .html_support import HtmlExtractionSupport
from .model import CompanyConfig, FinancialTable, Segment
from .segment_recognizer import SegmentRecognizer

logger = logging.getLogger(__name__)


class HtmlReportOrchestrator:
    def __init__(self, handlers):
        self.handlers = sorted(handlers, key=lambda h: h.priority())

    @staticmethod
    def standard(support: HtmlExtractionSupport,
                 recognizer: SegmentRecognizer,
                 data_extractor: DataExtractor) -> "HtmlReportOrchestrator":
        return HtmlReportOrchestrator([
            SegmentContributionHandler(support, recognizer),
            SegmentRowPeriodColumnHandler(support),
            GenericHtmlLayoutHandler(data_extractor),
        ])

    def extractFromTables(self, tables: List[FinancialTable],
                          cfg: Optional[CompanyConfig]) -> List[Segment]:
        sink: Dict[str, Segment] = {}
        if not tables:
            return []
        for table in tables:
            for handler in self.handlers:
                if not handler.supports(table, cfg):
                    continue
                hits = handler.apply(table, cfg, sink)
                if hits > 0:
                    logger.debug("Handler %s consumed table %s → +%d metrics",
                                 handler.__class__.__name__,
                                 table.getTableId(), hits)
                    break
        return list(sink.values())
