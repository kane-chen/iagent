"""Milvus retrieval backend for financial-filing-qa skill.

Uses Milvus v2 REST API (/v2/vectordb/entities/search) with COSINE metric.
Java-side collections use fields: chunk_id/ticker/document_id/form_type/fiscal_year/
fiscal_period/filing_date/source_file_name/section_title/page_number/content/vector/metadata_json.
"""
from __future__ import annotations

from typing import Any

import requests

from _ollama import embed


OUTPUT_FIELDS = [
    "chunk_id", "ticker", "document_id", "form_type", "fiscal_year",
    "fiscal_period", "filing_date", "source_file_name", "section_title",
    "page_number", "content", "metadata_json",
]


def _quote(v: Any) -> str:
    s = "" if v is None else str(v)
    s = s.replace("\\", "\\\\").replace('"', '\\"')
    return f'"{s}"'


def _build_filter(ticker: str | None, form_type: str | None, fiscal_period: str | None,
                  from_year: int | None, to_year: int | None) -> str:
    parts: list[str] = []
    if ticker:
        parts.append(f"ticker == {_quote(ticker.upper())}")
    if form_type:
        parts.append(f"form_type == {_quote(form_type)}")
    if fiscal_period:
        parts.append(f"fiscal_period == {_quote(fiscal_period)}")
    if from_year is not None and to_year is not None and from_year == to_year:
        parts.append(f"fiscal_year == {from_year}")
    else:
        if from_year is not None:
            parts.append(f"fiscal_year >= {from_year}")
        if to_year is not None:
            parts.append(f"fiscal_year <= {to_year}")
    return " && ".join(parts)


def _from_row(row: dict) -> dict:
    entity = row.get("entity") if isinstance(row.get("entity"), dict) else row
    meta_json = entity.get("metadata_json")
    metadata: dict = {}
    if meta_json:
        try:
            import json as _json
            metadata = _json.loads(meta_json)
        except Exception:
            metadata = {}
    return {
        "chunkId": entity.get("chunk_id"),
        "ticker": entity.get("ticker"),
        "documentId": entity.get("document_id"),
        "formType": entity.get("form_type"),
        "fiscalYear": entity.get("fiscal_year"),
        "fiscalPeriod": entity.get("fiscal_period"),
        "filingDate": entity.get("filing_date"),
        "sourceFileName": entity.get("source_file_name"),
        "sectionTitle": entity.get("section_title"),
        "pageNumber": entity.get("page_number"),
        "content": entity.get("content"),
        "score": row.get("distance", 0.0),
        "metadata": metadata,
    }


def search(query: str, ticker: str, top_k: int, form_type: str | None, fiscal_period: str | None,
           from_year: int | None, to_year: int | None, keyword: str | None,
           similarity_threshold: float, recall_multiplier: int,
           milvus_cfg: dict, embedding_cfg: dict) -> list[dict]:
    endpoint = milvus_cfg.get("endpoint", "http://127.0.0.1:19530").rstrip("/")
    token = milvus_cfg.get("token", "")
    collection = milvus_cfg.get("collection", "invest_filing")
    timeout = int(milvus_cfg.get("requestTimeoutSeconds", 60))
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"

    qemb = embed(query, embedding_cfg)
    recall = max(1, top_k * max(1, recall_multiplier))
    body: dict[str, Any] = {
        "collectionName": collection,
        "data": [qemb],
        "limit": recall,
        "outputFields": OUTPUT_FIELDS,
    }
    filter_expr = _build_filter(ticker, form_type, fiscal_period, from_year, to_year)
    if filter_expr:
        body["filter"] = filter_expr
    resp = requests.post(f"{endpoint}/v2/vectordb/entities/search",
                         headers=headers, json=body, timeout=timeout)
    if resp.status_code < 200 or resp.status_code >= 300:
        raise RuntimeError(f"Milvus search failed: HTTP {resp.status_code} {resp.text[:400]}")
    rows = (resp.json() or {}).get("data") or []

    results: list[dict] = []
    for r in rows:
        c = _from_row(r)
        # Post-filter by keyword
        if keyword:
            hay = ((c.get("sectionTitle") or "") + " " + (c.get("content") or "")).lower()
            if keyword.lower() not in hay:
                continue
        # Post-filter fiscal year (range)
        fy = c.get("fiscalYear")
        if from_year is not None and fy is not None and int(fy) < from_year:
            continue
        if to_year is not None and fy is not None and int(fy) > to_year:
            continue
        # Similarity threshold (COSINE distance: higher = more similar)
        score = c.get("score") or 0.0
        if score is not None and score < similarity_threshold:
            continue
        results.append(c)
    return results[:top_k]
