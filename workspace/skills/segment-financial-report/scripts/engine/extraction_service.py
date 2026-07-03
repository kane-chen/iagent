# -*- coding: utf-8 -*-
"""FinancialExtractionService (Python port).

Ported from io.invest.iagent.service.extraction.service.FinancialExtractionService.
Supports both HTML (US SEC filings) and PDF (HK/CN filings) via a parser registry.
"""
from __future__ import annotations

import logging
from pathlib import Path
from typing import List, Optional

from .config_loader import CompanyConfigLoader
from .data_extractor import DataExtractor
from .file_filter import FinancialFileFilter
from .html_orchestrator import HtmlReportOrchestrator
from .html_segment_parser import HtmlFileSegmentParser
from .html_support import HtmlExtractionSupport
from .metric_mapper import MetricMapper
from .model import CompanyConfig, Segment
from .pdf_parser import PdfFileSegmentParser
from .segment_recognizer import SegmentRecognizer

logger = logging.getLogger(__name__)


class FinancialExtractionService:
    def __init__(self, companyCode: Optional[str] = None,
                 workspace: Optional[Path] = None,
                 config_dir: Optional[Path] = None,
                 companyConfig: Optional[CompanyConfig] = None):
        self.workspace = workspace
        self.metricMapper = MetricMapper()
        self.configLoader = CompanyConfigLoader(config_dir)
        self.fileFilter = FinancialFileFilter(workspace) if workspace else None
        self.companyConfig: Optional[CompanyConfig] = None
        self.segmentRecognizer: Optional[SegmentRecognizer] = None
        self.dataExtractor: Optional[DataExtractor] = None
        self.htmlOrchestrator: Optional[HtmlReportOrchestrator] = None

        # Build parser list
        self.htmlParser = HtmlFileSegmentParser()
        self.pdfParser = PdfFileSegmentParser(workspace)
        self.parsers = [self.htmlParser, self.pdfParser]

        if companyConfig is not None:
            self.configure(companyConfig)
        elif companyCode is not None:
            cfg = self.configLoader.loadConfig(companyCode)
            if cfg is None:
                raise FileNotFoundError(f"No company config for {companyCode}")
            self.configure(cfg)

    def configure(self, cfg: CompanyConfig) -> None:
        """Build cfg-dependent collaborators. Single init point (like Java configure(cfg))."""
        self.companyConfig = cfg
        self.segmentRecognizer = SegmentRecognizer(cfg)
        self.dataExtractor = DataExtractor(self.segmentRecognizer, self.metricMapper)
        self.htmlOrchestrator = HtmlReportOrchestrator.standard(
            HtmlExtractionSupport(self.metricMapper),
            self.segmentRecognizer,
            self.dataExtractor,
        )
        self.htmlParser.setOrchestrator(self.htmlOrchestrator)
        self.pdfParser.setCompanyConfig(cfg)

    def setCompanyConfig(self, cfg: CompanyConfig) -> None:
        self.configure(cfg)

    # --- public API: single file -----------------------------------------------

    def extractFromFile(self, file: Path) -> List[Segment]:
        p = Path(file)
        logger.info("Extracting financial data from file: %s", p.name)
        for parser in self.parsers:
            if parser.supports(p):
                return parser.parse(p, self.companyConfig)
        logger.warning("No parser supports file: %s", p.name)
        return []

    def extractFromHtmlContent(self, html_content: str) -> List[Segment]:
        return self.htmlParser.parseHtml(html_content, self.companyConfig)

    # --- public API: batch (ticker + fiscal year range) -----------------------

    def extractSegments(self, ticker: str,
                        fiscal_year_start: Optional[str] = None,
                        fiscal_year_end: Optional[str] = None) -> List[Segment]:
        if self.fileFilter is None:
            logger.warning("extractSegments requires workspace")
            return []
        files = self.fileFilter.filter(ticker, fiscal_year_start, fiscal_year_end)
        if not files:
            return []
        all_segments: List[Segment] = []
        for f in files:
            try:
                segs = self.extractFromFile(f)
                if segs:
                    all_segments.extend(segs)
            except Exception as e:  # noqa: BLE001
                logger.error("extract failed: %s", f, exc_info=e)
        return _merge_segments(all_segments)

    # --- backward-compat aliases ----------------------------------------------

    def extractFromHtmlFile(self, file: Path) -> List[Segment]:
        return self.extractFromFile(file)


def _merge_segments(segments: List[Segment]) -> List[Segment]:
    """Group by segmentCode and merge metrics/children recursively (port of SegmentMetricUtil.merge)."""
    by_code: dict[str, Segment] = {}
    for seg in segments:
        code = seg.segmentCode
        if code not in by_code:
            by_code[code] = seg
        else:
            existing = by_code[code]
            # merge metrics (dedup by (metricCode, period))
            existing_keys = {
                (m.metricCode, m.period) for m in existing.metrics
            }
            for m in seg.metrics:
                if (m.metricCode, m.period) not in existing_keys:
                    existing.addMetric(m)
            # merge children recursively
            _merge_children(existing, seg)
    return list(by_code.values())


def _merge_children(parent: Segment, incoming: Segment) -> None:
    existing_by_code = {c.segmentCode: c for c in parent.children}
    for child in incoming.children:
        code = child.segmentCode
        if code in existing_by_code:
            existing = existing_by_code[code]
            existing_keys = {(m.metricCode, m.period) for m in existing.metrics}
            for m in child.metrics:
                if (m.metricCode, m.period) not in existing_keys:
                    existing.addMetric(m)
            _merge_children(existing, child)
        else:
            parent.addChild(child)
