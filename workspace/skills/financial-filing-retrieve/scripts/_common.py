"""Shared helpers for financial-filing-retrieve skill.

Config loading, category inference (parity with Java FilingQueryCategoryResolver /
FilingContentCategory), and small utilities used by both backends.
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Any


VALID_CATEGORIES = {
    "financial_statements",
    "financial_metrics",
    "business_operations",
    "financial_operations",
    "operating_risks",
    "governance_legal",
    "market_strategy",
    "esg_human_capital",
    "other",
}


def load_config(path: str | Path | None) -> dict:
    """Load retrieve.json; falls back to skill-local default when path is None."""
    if path is None:
        here = Path(__file__).resolve().parent
        path = here.parent / "config" / "retrieve.json"
    path = Path(path)
    if not path.is_file():
        raise FileNotFoundError(f"config not found: {path}")
    with path.open("r", encoding="utf-8") as fh:
        return json.load(fh)


def normalize_category(value: str | None) -> str | None:
    """Mirror FilingContentCategory.normalizeCode: blank → None, hyphen→underscore, lowercase."""
    if not value:
        return None
    normalized = value.strip().lower().replace("-", "_")
    if not normalized:
        return None
    return normalized  # unknown codes are passed through, same as Java behaviour


def infer_category(query: str, keyword_map: dict[str, list[str]]) -> str | None:
    """Match FilingQueryCategoryResolver.resolve — first-hit wins, insertion order matters.

    keyword_map must be an ordered dict as produced by json.load (Python 3.7+ preserves order).
    """
    if not query:
        return None
    lower = query.lower()
    for category, needles in keyword_map.items():
        for needle in needles:
            if needle and needle.lower() in lower:
                return category
    return None


def resolve_category(explicit: str | None, query: str, keyword_map: dict[str, list[str]]) -> tuple[str | None, str | None]:
    """Return (effective, inferred). effective = explicit or inferred; both may be None."""
    normalized = normalize_category(explicit)
    inferred = None
    if not normalized:
        inferred = infer_category(query, keyword_map)
    effective = normalized or inferred
    return effective, inferred


def fail(query: str, ticker: str, message: str, code: int = 2) -> None:
    """Emit a structured failure JSON to stdout and exit."""
    payload = {
        "success": False,
        "query": query,
        "ticker": (ticker or "").upper() if ticker else ticker,
        "error": message,
    }
    print(json.dumps(payload, ensure_ascii=False))
    sys.exit(code)


def dump(payload: dict, pretty: bool) -> None:
    if pretty:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        print(json.dumps(payload, ensure_ascii=False))


def env_override(config: dict) -> dict:
    """Allow a few env vars to override config for local testing without touching the JSON.

    Only keys that already exist in config are respected; unknown env vars are ignored.
    """
    overrides = {
        "IAGENT_KB_BACKEND": ("backend",),
        "IAGENT_KB_RESULT_TOPK": ("resultTopK",),
        "IAGENT_RAGFLOW_BASE_URL": ("ragflow", "baseUrl"),
        "IAGENT_RAGFLOW_API_KEY": ("ragflow", "apiKey"),
        "IAGENT_MILVUS_ENDPOINT": ("milvus", "endpoint"),
        "IAGENT_MILVUS_TOKEN": ("milvus", "token"),
        "IAGENT_MILVUS_COLLECTION": ("milvus", "collection"),
        "IAGENT_EMBEDDING_BASE_URL": ("embedding", "baseUrl"),
        "IAGENT_EMBEDDING_API_KEY": ("embedding", "apiKey"),
        "IAGENT_EMBEDDING_MODEL": ("embedding", "model"),
    }
    for env_key, path in overrides.items():
        val = os.environ.get(env_key)
        if not val:
            continue
        node: Any = config
        for key in path[:-1]:
            node = node.setdefault(key, {})
        leaf = path[-1]
        # Coerce numeric fields; strings pass through.
        current = node.get(leaf)
        if isinstance(current, int) and not isinstance(current, bool):
            try:
                val = int(val)
            except ValueError:
                pass
        node[leaf] = val
    return config
