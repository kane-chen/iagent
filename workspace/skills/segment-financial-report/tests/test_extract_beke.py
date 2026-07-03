# -*- coding: utf-8 -*-
from __future__ import annotations

from pathlib import Path

from engine.extraction_service import FinancialExtractionService

from conftest import locate


def test_extract_beke(workspace: Path):
    file = workspace / "portfolio/BEKE/filings/fil_0001104659-26-029843/tm268951d1_ex99-1.htm"
    svc = FinancialExtractionService(companyCode="BEKE", workspace=workspace)
    segments = svc.extractFromHtmlFile(file)
    assert segments is not None
    assert locate(segments, "Existing_Home").getMetric("REVENUE", "2025Q4").value == 5439
    assert locate(segments, "New_Home").getMetric("REVENUE", "2025Q4").value == 7263
    assert locate(segments, "Existing_Home").getMetric("COST", "2025Q4").value == -3240
    assert locate(segments, "Existing_Home").getMetric("OPERATING_INCOME", "2025Q4").value == 2198
    assert locate(segments, "Existing_Home").getMetric("REVENUE", "2024Q4").value == 8922
    assert locate(segments, "New_Home").getMetric("REVENUE", "2024Q4").value == 13076


def test_extract_beke2(workspace: Path):
    file = workspace / "portfolio/BEKE/filings/fil_0001104659-25-049798/tm2515232d1_ex99-1.htm"
    svc = FinancialExtractionService(companyCode="BEKE", workspace=workspace)
    segments = svc.extractFromHtmlFile(file)
    assert segments is not None
