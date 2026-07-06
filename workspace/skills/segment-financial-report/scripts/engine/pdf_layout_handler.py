# -*- coding: utf-8 -*-
"""PDF layout handler 策略接口。

不同公司港股 PDF 表格结构差异大（腾讯=segments-as-columns / segments-as-rows；
美团=subsegment-matrix），每种布局对应一个 handler。
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
