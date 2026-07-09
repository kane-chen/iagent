"""RAGFlow retrieval backend for financial-qa skill.

One dataset per ticker (prefix + ticker). Uses POST /api/v1/retrieval for hybrid search.
Meta fields include ticker, formType, fiscalYear, fiscalPeriod, documentId, etc.
"""
from __future__ import annotations

from typing import Any

import requests

from _ollama import embed


def _headers(api_key: str) -> dict[str, str]:
    return {"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"}


def _strip_section_prefix(content: str | None) -> str:
    if not content:
        return ""
    if content.startswith("Section: "):
        nl = content.find("\n")
        if nl > 0:
            return content[nl + 1:]
    return content


def _chunk_from_result(c: dict) -> dict:
    meta = (c.get("document") or {}).get("meta_fields") or c.get("meta_fields") or {}
    content = c.get("content") or c.get("content_with_weight") or ""
    content = _strip_section_prefix(content)
    sim = c.get("similarity")
    if sim is None:
        sim = c.get("vector_similarity", 0.0) * 0.7 + c.get("term_similarity", 0.0) * 0.3
    return {
        "chunkId": meta.get("chunkId"),
        "ticker": meta.get("ticker"),
        "documentId": meta.get("documentId"),
        "formType": meta.get("formType"),
        "fiscalYear": meta.get("fiscalYear"),
        "fiscalPeriod": meta.get("fiscalPeriod"),
        "filingDate": meta.get("filingDate"),
        "sourceFileName": meta.get("sourceFileName"),
        "sectionTitle": meta.get("sectionTitle"),
        "pageNumber": meta.get("pageNumber"),
        "content": content,
        "score": sim,
        "metadata": {},
    }


def search(query: str, ticker: str, top_k: int, form_type: str | None, fiscal_period: str | None,
           from_year: int | None, to_year: int | None, keyword: str | None,
           similarity_threshold: float, recall_multiplier: int,
           ragflow_cfg: dict, embedding_cfg: dict) -> list[dict]:
    base = ragflow_cfg.get("baseUrl", "http://localhost:9380").rstrip("/")
    api_key = ragflow_cfg.get("apiKey", "")
    if not api_key:
        raise RuntimeError("ragflow.apiKey is not configured (set RAGFLOW_API_KEY env var or config)")
    prefix = ragflow_cfg.get("datasetPrefix", "filing_rag_")
    kw_weight = float(ragflow_cfg.get("keywordWeight", 0.3))
    sim_thresh_cfg = float(ragflow_cfg.get("similarityThreshold", 0.3))
    timeout = int(ragflow_cfg.get("requestTimeoutSeconds", 60))

    dataset_name = prefix + (ticker or "").upper()

    # Find dataset by name (paginate)
    dataset_id = None
    page = 1
    page_size = 100
    while True:
        resp = requests.get(f"{base}/api/v1/datasets",
                            headers=_headers(api_key),
                            params={"page": page, "page_size": page_size}, timeout=timeout)
        if resp.status_code < 200 or resp.status_code >= 300:
            raise RuntimeError(f"RAGFlow list datasets HTTP {resp.status_code}: {resp.text[:300]}")
        data = (resp.json() or {}).get("data") or []
        if not data:
            break
        for ds in data:
            if ds.get("name") == dataset_name:
                dataset_id = ds.get("id")
                break
        if dataset_id or len(data) < page_size:
            break
        page += 1
        if page > 10:
            break
    if not dataset_id:
        return []  # dataset does not exist yet

    # Build meta filter
    meta_filter = {"ticker": (ticker or "").upper()}
    if fiscal_period:
        meta_filter["fiscalPeriod"] = fiscal_period

    recall = max(1, top_k * max(1, recall_multiplier))
    body = {
        "question": query,
        "dataset_ids": [dataset_id],
        "top_k": recall,
        "similarity_threshold": sim_thresh_cfg,
        "vector_similarity_weight": 1.0 - kw_weight,
        "keyword": True,
        "meta": meta_filter,
    }
    resp = requests.post(f"{base}/api/v1/retrieval", headers=_headers(api_key),
                         json=body, timeout=timeout)
    if resp.status_code < 200 or resp.status_code >= 300:
        raise RuntimeError(f"RAGFlow retrieval HTTP {resp.status_code}: {resp.text[:400]}")
    payload = resp.json() or {}
    data = payload.get("data") or {}
    chunks = data.get("chunks") if isinstance(data, dict) else data
    if not isinstance(chunks, list):
        chunks = payload.get("chunks") or []

    results: list[dict] = []
    for c in chunks:
        cc = _chunk_from_result(c)
        if keyword:
            hay = ((cc.get("sectionTitle") or "") + " " + (cc.get("content") or "")).lower()
            if keyword.lower() not in hay:
                continue
        fy = cc.get("fiscalYear")
        if from_year is not None and fy is not None and int(fy) < from_year:
            continue
        if to_year is not None and fy is not None and int(fy) > to_year:
            continue
        if form_type and cc.get("formType") and cc.get("formType").lower() != form_type.lower():
            continue
        if (cc.get("score") or 0.0) < similarity_threshold:
            continue
        results.append(cc)
    return results[:top_k]
