# -*- coding: utf-8 -*-
"""Config loader — ported from io.invest.iagent.service.extraction.config.CompanyConfigLoader."""
from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Optional

from .model import CompanyConfig, MetricMappingRule, SegmentConfig

logger = logging.getLogger(__name__)


DEFAULT_CONFIG_DIR = (
    Path(__file__).resolve().parents[2] / "config" / "extraction"
)


class CompanyConfigLoader:
    def __init__(self, config_dir: Optional[Path] = None):
        self.config_dir: Path = Path(config_dir) if config_dir else DEFAULT_CONFIG_DIR

    def loadConfig(self, companyCode: str) -> Optional[CompanyConfig]:
        logger.info("Loading config for company: %s", companyCode)
        for candidate in (companyCode, companyCode.lower(), companyCode.upper()):
            fpath = self.config_dir / f"{candidate}.json"
            if fpath.exists():
                with fpath.open("r", encoding="utf-8") as f:
                    data = json.load(f)
                return self._from_dict(data)
        logger.warning("Config file not found for company: %s", companyCode)
        return None

    def loadFromFile(self, config_file: Path) -> Optional[CompanyConfig]:
        with Path(config_file).open("r", encoding="utf-8") as f:
            return self._from_dict(json.load(f))

    def _from_dict(self, data: dict) -> CompanyConfig:
        cfg = CompanyConfig(
            companyCode=data.get("companyCode"),
            companyName=data.get("companyName"),
            market=data.get("market"),
            defaultCurrency=data.get("defaultCurrency"),
            defaultUnit=data.get("defaultUnit"),
            includePeriodTypes=list(data.get("includePeriodTypes") or []),
            htmlLayout=data.get("htmlLayout"),
        )
        for sc in data.get("segments") or []:
            cfg.segments.append(SegmentConfig(
                segmentCode=sc.get("segmentCode"),
                segmentName=sc.get("segmentName"),
                aliases=list(sc.get("aliases") or []),
                level=int(sc.get("level") or 1),
                parentCode=sc.get("parentCode"),
            ))
        for rule in data.get("metricMappingRules") or []:
            cfg.metricMappingRules.append(MetricMappingRule(
                standardMetricCode=rule.get("standardMetricCode"),
                rawMetricNames=list(rule.get("rawMetricNames") or []),
                formula=rule.get("formula"),
            ))
        return cfg
