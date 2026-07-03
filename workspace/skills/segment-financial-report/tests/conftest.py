# -*- coding: utf-8 -*-
"""Common pytest fixtures / helpers for the Python engine tests."""
from __future__ import annotations

import sys
from pathlib import Path
from typing import List, Optional

import pytest

SCRIPTS_DIR = Path(__file__).resolve().parents[1] / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

PROJECT_ROOT = Path(__file__).resolve().parents[4]  # iagent project root
WORKSPACE_DIR = PROJECT_ROOT / "workspace"


@pytest.fixture
def workspace() -> Path:
    return WORKSPACE_DIR


def locate(segments, segment_code):
    """Recursive lookup by segmentCode — mirrors FinancialExtractionServiceTest.locale."""
    if not segments:
        return None
    for s in segments:
        if s.segmentCode == segment_code:
            return s
    for s in segments:
        found = locate(s.children, segment_code)
        if found is not None:
            return found
    return None


def sub_segments(segments, parent_code) -> list:
    out = []
    for s in segments:
        if s.segmentCode == parent_code:
            out.extend(s.children)
    return out


def local_value(segments, segment_code, metric_code, period):
    for s in segments:
        if s.segmentCode == segment_code:
            m = s.getMetric(metric_code, period)
            if m is not None:
                return m.value
    return None
