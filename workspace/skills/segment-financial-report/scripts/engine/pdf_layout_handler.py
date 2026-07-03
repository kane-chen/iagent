# -*- coding: utf-8 -*-
"""PdfLayoutHandler — port of io.invest.iagent.service.extraction.parser.PdfLayoutHandler.

Strategy interface for PDF segment table layouts. Different companies' HK report PDFs
have very different table structures (Tencent = segments-as-columns or segments-as-rows;
Meituan = subsegment-matrix); each layout gets its own handler.
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, Dict, List

from .model import CompanyConfig, Layout, Segment


class PdfLayoutHandler(ABC):
    @abstractmethod
    def layout(self) -> Layout:
        ...

    @abstractmethod
    def apply(self,
              mapping: CompanyConfig.PdfColumnMapping if False else Any,
              data_rows: List[List[str]],
              table_id: str,
              currency: str,
              unit: str,
              context: "FilingContext",
              sink: Dict[str, Segment]) -> int:
        """Try to consume one table with one mapping. Returns number of metrics written;
        0 means "this table didn't match, move on"."""
        ...

    @staticmethod
    def as_rows(data_rows_node: Any) -> List[List[str]]:
        """Normalize a python-extracted dataRows list (already List[List[str]] when
        called in-process, but defensively handle JsonNode-like dicts from subprocess)."""
        if data_rows_node is None:
            return []
        rows: List[List[str]] = []
        for row_node in data_rows_node:
            if isinstance(row_node, list):
                cells = [("" if c is None else str(c)) for c in row_node]
            else:
                cells = [cell.asText("") for cell in row_node]
            rows.append(cells)
        return rows


# FilingContext lives in its own module to avoid a circular import with pdf_parser.
from .filing_context import FilingContext  # noqa: E402
