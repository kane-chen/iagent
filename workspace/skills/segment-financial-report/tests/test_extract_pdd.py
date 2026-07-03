# -*- coding: utf-8 -*-
from __future__ import annotations

from pathlib import Path

from engine.extraction_service import FinancialExtractionService

from conftest import locate


def test_extract_pdd(workspace: Path):
    file = workspace / "portfolio/PDD/filings/fil_0001104659-25-026115/tm259886d1_ex99-1.htm"
    svc = FinancialExtractionService(companyCode="PDD", workspace=workspace)
    segments = svc.extractFromHtmlFile(file)
    assert segments is not None
    assert locate(segments, "Online_Marketing_Services").getMetric("REVENUE", "2024Q4").value == 57011
    assert locate(segments, "Transaction_Services").getMetric("REVENUE", "2023Q4").value == 40205


def test_extract_pdd2(workspace: Path):
    file = workspace / "portfolio/PDD/filings/fil_0001104659-26-067186/tm2615739d1_ex99-1.htm"
    svc = FinancialExtractionService(companyCode="PDD", workspace=workspace)
    segments = svc.extractFromHtmlFile(file)
    assert segments is not None
    assert locate(segments, "Online_Marketing_Services").getMetric("REVENUE", "2025Q1").value == 48722
    assert locate(segments, "Transaction_Services").getMetric("REVENUE", "2025Q1").value == 46950
