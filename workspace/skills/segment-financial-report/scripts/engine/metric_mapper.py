# -*- coding: utf-8 -*-
"""指标映射器：从 config/extraction/metric_dict.json 加载指标字典，
用于将原始文本中的指标名映射到标准 metricCode。
"""
from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import List, Optional

from .model import MetricDict

logger = logging.getLogger(__name__)

DEFAULT_DICT_PATH = (
    Path(__file__).resolve().parents[2] / "config" / "extraction" / "metric_dict.json"
)


class MetricMapper:
    def __init__(self, dict_path: Optional[Path] = None):
        path = Path(dict_path) if dict_path else DEFAULT_DICT_PATH
        self.metricDictionaries: List[MetricDict] = []
        self._load(path)

    def _load(self, path: Path):
        if not path.exists():
            logger.warning("Metric dict file not found: %s", path)
            return
        with path.open("r", encoding="utf-8") as f:
            data = json.load(f)
        for entry in data:
            self.metricDictionaries.append(MetricDict(
                metricCode=entry.get("metricCode"),
                metricName=entry.get("metricName"),
                metricCategory=entry.get("metricCategory"),
                synonyms=list(entry.get("synonyms") or []),
                formula=entry.get("formula"),
                isStandard=bool(entry.get("isStandard", True)),
            ))

    def mapMetric(self, rawName: Optional[str]) -> Optional[MetricDict]:
        """将原始指标名映射到标准 MetricDict。"""
        if not rawName or not rawName.strip():
            return None
        clean = rawName.strip()
        for d in self.metricDictionaries:
            if d.matches(clean):
                return d
        for d in self.metricDictionaries:
            if self._contains_metric(clean, d):
                return d
        return None

    def _contains_metric(self, text: str, d: MetricDict) -> bool:
        lower = text.lower()
        if d.metricName and lower.find(d.metricName.lower()) >= 0:
            return True
        for syn in d.synonyms:
            if lower.find(syn.lower()) >= 0:
                if self._is_valid_match(text, syn):
                    return True
        return False

    def _is_valid_match(self, text: str, synonym: str) -> bool:
        lower_text = text.lower()
        lower_syn = synonym.lower()
        if "operating income" in lower_syn or "经营利润" in lower_syn:
            if "expense" in lower_text or "费用" in lower_text:
                return False
        return True

    def getMetricByCode(self, metricCode: str) -> Optional[MetricDict]:
        if metricCode is None:
            return None
        for d in self.metricDictionaries:
            if d.metricCode and d.metricCode.lower() == metricCode.lower():
                return d
        return None
