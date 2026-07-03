# -*- coding: utf-8 -*-
"""HtmlFileSegmentParser — wraps HtmlReportParser + HtmlReportOrchestrator.

Mirrors io.invest.iagent.service.extraction.extractor.HtmlFileSegmentParser.
"""
from __future__ import annotations

import logging
from pathlib import Path
from typing import List, Optional

from .html_orchestrator import HtmlReportOrchestrator
from .html_parser import HtmlReportParser
from .model import CompanyConfig, Segment

logger = logging.getLogger(__name__)


class HtmlFileSegmentParser:
    def __init__(self):
        self._html_parser = HtmlReportParser()
        self._orchestrator: Optional[HtmlReportOrchestrator] = None

    def setOrchestrator(self, orchestrator: HtmlReportOrchestrator) -> None:
        self._orchestrator = orchestrator

    def supports(self, file: Path) -> bool:
        if file is None:
            return False
        suffix = file.suffix.lower()
        return suffix in (".html", ".htm")

    def parse(self, file: Path, cfg: Optional[CompanyConfig]) -> List[Segment]:
        tables = self._html_parser.parse(file)
        logger.info("Parsed HTML file %s into %d financial tables", file.name, len(tables))
        orch = self._orchestrator
        if orch is None:
            logger.warning("HtmlFileSegmentParser: no orchestrator configured, returning empty")
            return []
        segments = orch.extractFromTables(tables, cfg)
        logger.info("Extracted %d segments with financial data from %s",
                    len(segments), file.name)
        return segments

    def parseHtml(self, html_content: str, cfg: Optional[CompanyConfig]) -> List[Segment]:
        tables = self._html_parser.parseHtml(html_content)
        orch = self._orchestrator
        if orch is None:
            return []
        return orch.extractFromTables(tables, cfg)
