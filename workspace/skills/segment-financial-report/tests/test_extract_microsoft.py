# -*- coding: utf-8 -*-
from __future__ import annotations

from pathlib import Path

from engine.extraction_service import FinancialExtractionService

from conftest import locate


def test_extract_microsoft(workspace: Path):
    file = workspace / "portfolio/MSFT/filings/fil_0000950170-25-061046/msft-20250331.htm"
    svc = FinancialExtractionService(companyCode="MSFT", workspace=workspace)
    segments = svc.extractFromHtmlFile(file)
    assert segments is not None
    assert locate(segments, "PRODUCTIVITY_BUSINESS").getMetric("REVENUE", "2025Q1").value == 29944
    assert locate(segments, "PRODUCTIVITY_BUSINESS").getMetric("REVENUE", "2024Q1").value == 27113
    assert locate(segments, "PRODUCTIVITY_BUSINESS").getMetric("OPERATING_INCOME", "2025Q1").value == 17379
    assert locate(segments, "PRODUCTIVITY_BUSINESS").getMetric("OPERATING_INCOME", "2024Q1").value == 15143
    assert locate(segments, "INTELLIGENT_CLOUD").getMetric("REVENUE", "2025Q1").value == 26751
    assert locate(segments, "INTELLIGENT_CLOUD").getMetric("OPERATING_INCOME", "2025Q1").value == 11095
    assert locate(segments, "PERSONAL_COMPUTING").getMetric("OPERATING_INCOME", "2025Q1").value == 3526
