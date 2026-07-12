"""Shared helpers for financial-filing-qa skill.

Config loading, period parsing (mirrors Java QueryParser), output utilities.
"""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path
from typing import Any


def load_config(path: str | Path | None = None) -> dict:
    if path is None:
        here = Path(__file__).resolve().parent
        path = here.parent / "config" / "qa.json"
    path = Path(path)
    if not path.is_file():
        raise FileNotFoundError(f"config not found: {path}")
    with path.open("r", encoding="utf-8") as fh:
        cfg = json.load(fh)
    return env_override(cfg)


def env_override(cfg: dict) -> dict:
    overrides = {
        "IAGENT_FILING_RAG_BACKEND": ("backend",),
        "RAGFLOW_API_KEY": ("ragflow", "apiKey"),
        "IAGENT_RAGFLOW_BASE_URL": ("ragflow", "baseUrl"),
        "IAGENT_MILVUS_ENDPOINT": ("milvus", "endpoint"),
        "IAGENT_MILVUS_TOKEN": ("milvus", "token"),
        "IAGENT_MILVUS_COLLECTION": ("milvus", "collection"),
        "IAGENT_EMBEDDING_BASE_URL": ("embedding", "baseUrl"),
        "IAGENT_EMBEDDING_MODEL": ("embedding", "model"),
        "IAGENT_LLM_BASE_URL": ("llm", "baseUrl"),
        "IAGENT_LLM_MODEL": ("llm", "model"),
    }
    for env_key, keypath in overrides.items():
        val = os.environ.get(env_key)
        if not val:
            continue
        node: Any = cfg
        for k in keypath[:-1]:
            node = node.setdefault(k, {})
        leaf = keypath[-1]
        cur = node.get(leaf)
        if isinstance(cur, int) and not isinstance(cur, bool):
            try:
                val = int(val)
            except ValueError:
                pass
        elif isinstance(cur, float):
            try:
                val = float(val)
            except ValueError:
                pass
        node[leaf] = val
    return cfg


# --- Period parsing (mirrors Java QueryParser) ---

_PERIOD_PATTERNS = [
    (re.compile(r"FY(\d{4})", re.IGNORECASE), lambda m: (int(m.group(1)), "FY")),
    (re.compile(r"(\d{4})FY", re.IGNORECASE), lambda m: (int(m.group(1)), "FY")),
    (re.compile(r"Q([1-4])(\d{4})", re.IGNORECASE), lambda m: (int(m.group(2)), "Q" + m.group(1))),
    (re.compile(r"(\d{4})Q([1-4])", re.IGNORECASE), lambda m: (int(m.group(1)), "Q" + m.group(2))),
    (re.compile(r"(\d{4})-?Q([1-4])", re.IGNORECASE), lambda m: (int(m.group(1)), "Q" + m.group(2))),
    (re.compile(r"(\d{4})H([12])", re.IGNORECASE), lambda m: (int(m.group(1)), "H" + m.group(2))),
    (re.compile(r"(\d{4})"), lambda m: (int(m.group(1)), None)),
]


def parse_period(text: str | None) -> tuple[int | None, str | None]:
    """Parse a period string like '2025Q1' / '2024' / 'FY2025' → (fiscal_year, fiscal_period)."""
    if not text or not text.strip():
        return None, None
    s = text.strip().upper().replace("-", "").replace(" ", "")
    for pat, fn in _PERIOD_PATTERNS:
        m = pat.search(s)
        if m:
            return fn(m)
    return None, None


def fail(message: str, code: int = 2) -> None:
    print(json.dumps({"success": False, "error": message}, ensure_ascii=False))
    sys.exit(code)


def dump_json(payload: Any, pretty: bool) -> None:
    if pretty:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        print(json.dumps(payload, ensure_ascii=False))
