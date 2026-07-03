# -*- coding: utf-8 -*-
"""FinancialExtractionService (Python port, HTML only).

Ported from io.invest.iagent.service.extraction.service.FinancialExtractionService.
PDF handling raises NotImplementedError (Stage 2).
"""
from __future__ import annotations

import logging
from pathlib import Path
from typing import List, Optional

from .config_loader import CompanyConfigLoader
from .data_extractor import DataExtractor
from .html_orchestrator import HtmlReportOrchestrator
from .html_parser import HtmlReportParser
from .html_support import HtmlExtractionSupport
from .metric_mapper import MetricMapper
from .model import CompanyConfig, Segment
from .segment_recognizer import SegmentRecognizer

logger = logging.getLogger(__name__)


class FinancialExtractionService:
    def __init__(self, companyCode: Optional[str] = None,
                 workspace: Optional[Path] = None,
                 config_dir: Optional[Path] = None,
                 companyConfig: Optional[CompanyConfig] = None):
        self.workspace = workspace
        self.metricMapper = MetricMapper()
        self.htmlReportParser = HtmlReportParser()
        self.configLoader = CompanyConfigLoader(config_dir)
        self.companyConfig: Optional[CompanyConfig] = None
        self.segmentRecognizer: Optional[SegmentRecognizer] = None
        self.dataExtractor: Optional[DataExtractor] = None
        self.htmlOrchestrator: Optional[HtmlReportOrchestrator] = None
        if companyConfig is not None:
            self.setCompanyConfig(companyConfig)
        elif companyCode is not None:
            cfg = self.configLoader.loadConfig(companyCode)
            if cfg is None:
                raise FileNotFoundError(f"No company config for {companyCode}")
            self.setCompanyConfig(cfg)

    def setCompanyConfig(self, cfg: CompanyConfig) -> None:
        self.companyConfig = cfg
        self.segmentRecognizer = SegmentRecognizer(cfg)
        self.dataExtractor = DataExtractor(self.segmentRecognizer, self.metricMapper)
        self.htmlOrchestrator = HtmlReportOrchestrator.standard(
            HtmlExtractionSupport(self.metricMapper),
            self.segmentRecognizer,
            self.dataExtractor,
        )

    def extractFromHtmlFile(self, file: Path) -> List[Segment]:
        p = Path(file)
        name = p.name.lower()
        logger.info("Extracting financial data from file: %s", name)
        if name.endswith(".pdf"):
            raise NotImplementedError("PDF extraction is Stage 2 in the Python port")
        tables = self.htmlReportParser.parse(p)
        return self.htmlOrchestrator.extractFromTables(tables, self.companyConfig)

    def extractFromHtmlContent(self, html_content: str) -> List[Segment]:
        tables = self.htmlReportParser.parseHtml(html_content)
        return self.htmlOrchestrator.extractFromTables(tables, self.companyConfig)
