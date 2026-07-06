# -*- coding: utf-8 -*-
"""港股 PDF 解析器。

调用多引擎表格抽取（camelot/pdfplumber），通过 PdfLayoutHandler 策略
基于位置映射（PdfColumnMapping）解析，因为港股 PDF 中文字体编码常出现乱码。
"""
from __future__ import annotations

import importlib.util
import logging
import sys
from enum import Enum
from pathlib import Path
from typing import Dict, List, Optional

from .filing_context import FilingContext
from .model import CompanyConfig, Segment
from .pdf_handlers.segments_as_columns import SegmentsAsColumnsHandler
from .pdf_handlers.segments_as_rows import SegmentsAsRowsHandler
from .pdf_handlers.subsegment_matrix import SubsegmentMatrixHandler
from .pdf_layout_handler import PdfLayoutHandler
from .pdf_support import PdfExtractionSupport

logger = logging.getLogger(__name__)


# --- Import extract_pdf_tables from scripts/ (sibling directory of engine/) -------
_SCRIPTS_DIR = Path(__file__).resolve().parents[1]  # engine/.. = scripts/


def _load_extract_pdf_tables():
    """Load extract_pdf_tables.py as a module (lives in scripts/, not in engine/)."""
    mod_name = "extract_pdf_tables"
    if mod_name in sys.modules:
        return sys.modules[mod_name]
    script_path = _SCRIPTS_DIR / "extract_pdf_tables.py"
    if not script_path.exists():
        return None
    spec = importlib.util.spec_from_file_location(mod_name, script_path)
    if spec is None or spec.loader is None:
        return None
    mod = importlib.util.module_from_spec(spec)
    sys.modules[mod_name] = mod
    try:
        spec.loader.exec_module(mod)
    except Exception as e:  # noqa: BLE001
        logger.warning("Failed to load extract_pdf_tables: %s", e)
        sys.modules.pop(mod_name, None)
        return None
    return mod


# --- PdfFileSegmentParser --------------------------------------------------------
class PdfFileSegmentParser:
    """HK PDF parser: runs extract_pdf_tables.extract_tables() and applies PdfColumnMapping."""

    def __init__(self, workspace: Optional[Path] = None):
        self.workspace = workspace
        self.companyConfig: Optional[CompanyConfig] = None
        self.handlers: Dict[Enum, PdfLayoutHandler] = {}

    def setCompanyConfig(self, cfg: CompanyConfig) -> None:
        self.companyConfig = cfg
        support = PdfExtractionSupport(cfg)
        self.handlers = {h.layout(): h for h in [
            SegmentsAsColumnsHandler(support),
            SegmentsAsRowsHandler(support),
            SubsegmentMatrixHandler(support),
        ]}

    def supports(self, file: Path) -> bool:
        if file is None:
            return False
        return file.suffix.lower() == ".pdf"

    def parse(self, file: Path, cfg: Optional[CompanyConfig] = None) -> List[Segment]:
        if cfg is not None and cfg is not self.companyConfig:
            self.setCompanyConfig(cfg)

        logger.info("Parsing PDF report via multi-engine extractor: %s", file.name)

        extract_mod = _load_extract_pdf_tables()
        if extract_mod is None:
            logger.warning("extract_pdf_tables module unavailable for: %s", file.name)
            return []

        try:
            result = extract_mod.extract_tables(str(file), max_pages=100)
        except Exception as e:  # noqa: BLE001
            logger.warning("extract_pdf_tables failed for %s: %s", file.name, e)
            return []

        tables = result.get("tables") or []
        if not tables:
            logger.warning("No tables returned from PDF extractor for: %s", file.name)
            return []

        engines = result.get("engines")
        if engines:
            logger.info("PDF extractor used engines: %s", engines)

        context = FilingContext.parse(file)
        logger.info("Filing context for %s: %s", file.name, context)

        sink: Dict[str, Segment] = {}
        consumed: set[int] = set()
        total_matched = 0

        if self.companyConfig is not None and self.companyConfig.pdfColumnMappings:
            for mapping in self.companyConfig.pdfColumnMappings:
                if not self._filing_period_matches(mapping, context):
                    continue
                for i, table_json in enumerate(tables):
                    if i in consumed:
                        continue
                    hits = self._apply_single_mapping(mapping, table_json, context, sink)
                    if hits > 0:
                        consumed.add(i)
                        total_matched += 1
                        logger.debug("Mapping %s (%s) consumed table %s → +%d metrics",
                                     mapping.layout, self._metric_label(mapping),
                                     table_json.get("tableId", "?"), hits)
                        break

        logger.info("PDF mapping consumed %d tables out of %d, produced %d segments",
                    total_matched, len(tables), len(sink))
        return list(sink.values())

    # --- internal helpers -------------------------------------------------------

    def _apply_single_mapping(self, mapping, table_json, context, sink) -> int:
        table_id = table_json.get("tableId", "unknown")
        currency = table_json.get("currency")
        unit = table_json.get("unit")
        data_rows_node = table_json.get("dataRows") or []
        if not isinstance(data_rows_node, list) or len(data_rows_node) == 0:
            return 0
        data_rows = PdfLayoutHandler.as_rows(data_rows_node)

        bucket = {} if mapping.discardValues else sink
        handler = self.handlers.get(mapping.layout)
        if handler is None:
            logger.warning("No handler registered for layout: %s", mapping.layout)
            return 0
        return handler.apply(mapping, data_rows, table_id, currency, unit, context, bucket)

    @staticmethod
    def _filing_period_matches(mapping, context: FilingContext) -> bool:
        allowed = mapping.filingPeriods
        if not allowed:
            return True
        if context is None or not context.period:
            return False
        return any(p and p.upper() == context.period for p in allowed)

    @staticmethod
    def _metric_label(mapping) -> str:
        if mapping.metricCode:
            return mapping.metricCode
        if mapping.metricCodesByRow:
            return "+".join(mapping.metricCodesByRow)
        if mapping.rowDescriptors:
            codes = [rd.metricCode for rd in mapping.rowDescriptors if rd.metricCode]
            return "+".join(codes) if codes else "?"
        return "?"
