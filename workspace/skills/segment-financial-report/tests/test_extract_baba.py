# -*- coding: utf-8 -*-
from __future__ import annotations

from pathlib import Path

from engine.extraction_service import FinancialExtractionService

from conftest import local_value, locate, sub_segments


def test_extract_baba(workspace: Path):
    file = workspace / "portfolio/BABA/filings/fil_0001104659-25-049400/tm2515233d1_ex99-1.htm"
    svc = FinancialExtractionService(companyCode="BABA", workspace=workspace)
    segments = svc.extractFromHtmlFile(file)
    assert segments is not None
    assert local_value(segments, "CLOUD_INTELLIGENCE", "ADJUSTED_EBITA", "2025Q1") == 2420
    assert local_value(segments, "CLOUD_INTELLIGENCE", "ADJUSTED_EBITA", "2024Q1") == 1432
    subs = sub_segments(segments, "TAOBAO_TMALL")
    assert local_value(subs, "CHINA_COMMERCE_RETAIL", "REVENUE", "2025Q1") == 95581
    assert local_value(subs, "CHINA_COMMERCE_RETAIL", "REVENUE", "2024Q1") == 88264


def test_extract_baba2(workspace: Path):
    file = workspace / "portfolio/BABA/filings/fil_0001104659-26-060224/tm2614494d1_ex99-1.htm"
    svc = FinancialExtractionService(companyCode="BABA", workspace=workspace)
    segments = svc.extractFromHtmlFile(file)
    assert segments is not None
    assert locate(segments, "CHINA_COMMERCE_WHOLESALE").getMetric("REVENUE", "2026Q1").value == 5940
    assert locate(segments, "CUSTOMER_MANAGEMENT").getMetric("REVENUE", "2026Q1").value == 73024
    assert locate(segments, "INTERNATIONAL_DIGITAL_COMMERCE").getMetric("ADJUSTED_EBITA", "2026Q1").value == -138
