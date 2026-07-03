# -*- coding: utf-8 -*-
from __future__ import annotations

from pathlib import Path

from engine.extraction_service import FinancialExtractionService

from conftest import locate


def test_extract_tcom(workspace: Path):
    file = workspace / "portfolio/TCOM/filings/fil_0001193125-25-285260/d17038dex991.htm"
    svc = FinancialExtractionService(companyCode="TCOM", workspace=workspace)
    segments = svc.extractFromHtmlFile(file)
    assert segments is not None
    assert locate(segments, "Hotel").getMetric("REVENUE", "2025Q3").value == 8047
    assert locate(segments, "Hotel").getMetric("REVENUE", "2025Q2").value == 6225
    assert locate(segments, "Hotel").getMetric("REVENUE", "2024Q3").value == 6802
    assert locate(segments, "Ticket").getMetric("REVENUE", "2025Q3").value == 6306
    assert locate(segments, "Ticket").getMetric("REVENUE", "2024Q3").value == 5650
    assert locate(segments, "Tour").getMetric("REVENUE", "2025Q3").value == 1606
