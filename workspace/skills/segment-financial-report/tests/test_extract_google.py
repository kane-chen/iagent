# -*- coding: utf-8 -*-
from __future__ import annotations

from pathlib import Path

from engine.extraction_service import FinancialExtractionService

from conftest import local_value, sub_segments


def test_extract_google(workspace: Path):
    file = workspace / "portfolio/GOOG/filings/fil_0001652044-25-000043/goog-20250331.htm"
    svc = FinancialExtractionService(companyCode="GOOG", workspace=workspace)
    segments = svc.extractFromHtmlFile(file)
    assert segments is not None
    assert local_value(segments, "GOOGLE_CLOUD", "REVENUE", "2025Q1") == 12260
    assert local_value(segments, "GOOGLE_CLOUD", "REVENUE", "2024Q1") == 9574
    subs = sub_segments(segments, "GOOGLE_SERVICES")
    assert local_value(subs, "GOOGLE_ADVERTISING", "REVENUE", "2025Q1") == 66885
    assert local_value(subs, "GOOGLE_ADVERTISING", "REVENUE", "2024Q1") == 61659
