"""RAGFlow retrieval backend.

Mirrors Java RagflowKnowledgeBaseBackend.retrieve + RagflowClient.retrieve:

 - dataset name = ragflow.datasetPrefix + upper(ticker)
 - POST /api/v1/retrieval with question / top_k / similarity_threshold /
   vector_similarity_weight / keyword / meta filter
 - RAGFlow standard response wrapper: {code: 0, data: {...}}
"""

from __future__ import annotations

from typing import Any

import requests


def _headers(api_key: str) -> dict[str, str]:
    return {
        "Accept": "application/json",
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }


def _get_dataset_id(base_url: str, api_key: str, dataset_name: str, timeout: int) -> str | None:
    """Find dataset by name via pagination (RAGFlow's /datasets doesn't take name filter reliably)."""
    page, page_size = 1, 100
    while page <= 10:  # safety cap: ~1000 datasets
        url = f"{base_url.rstrip('/')}/api/v1/datasets?page={page}&page_size={page_size}"
        resp = requests.get(url, headers=_headers(api_key), timeout=timeout)
        resp.raise_for_status()
        body = resp.json() or {}
        if body.get("code", 0) != 0:
            raise RuntimeError(f"RAGFlow list datasets error: code={body.get('code')} msg={body.get('message')}")
        data = body.get("data") or []
        if not isinstance(data, list) or not data:
            return None
        for ds in data:
            if ds.get("name") == dataset_name:
                return ds.get("id")
        if len(data) < page_size:
            return None
        page += 1
    return None


def retrieve(
    *,
    query: str,
    ticker: str,
    top_k: int,
    fiscal_year: str | None,
    form_type: str | None,
    category: str | None,
    ragflow_cfg: dict,
) -> dict:
    base_url = ragflow_cfg.get("baseUrl", "http://localhost")
    api_key = ragflow_cfg.get("apiKey", "")
    if not api_key:
        raise RuntimeError("ragflow.apiKey is not configured")
    dataset_prefix = ragflow_cfg.get("datasetPrefix", "filing_kb_")
    similarity_threshold = float(ragflow_cfg.get("similarityThreshold", 0.2))
    keyword_weight = float(ragflow_cfg.get("keywordSimilarityWeight", 0.3))
    timeout = int(ragflow_cfg.get("requestTimeoutSeconds", 60))

    dataset_name = f"{dataset_prefix}{ticker}"
    dataset_id = _get_dataset_id(base_url, api_key, dataset_name, timeout)
    if not dataset_id:
        return {
            "results": [],
            "dataset_id": None,
            "dataset_name": dataset_name,
            "meta_filter": {},
            "message": "知识库尚未建立，请先构建",
        }

    meta_filter: dict[str, str] = {}
    if fiscal_year:
        meta_filter["fiscal_year"] = fiscal_year
    if form_type:
        meta_filter["form_type"] = form_type
    if category:
        meta_filter["category"] = category

    payload: dict[str, Any] = {
        "question": query,
        "dataset_ids": [dataset_id],
        "top_k": top_k,
        "similarity_threshold": similarity_threshold,
        "vector_similarity_weight": 1.0 - keyword_weight,
        "keyword": True,
    }
    if meta_filter:
        payload["meta"] = meta_filter

    resp = requests.post(
        f"{base_url.rstrip('/')}/api/v1/retrieval",
        headers=_headers(api_key),
        json=payload,
        timeout=timeout,
    )
    resp.raise_for_status()
    body = resp.json() or {}
    if body.get("code", 0) != 0:
        raise RuntimeError(f"RAGFlow retrieval error: code={body.get('code')} msg={body.get('message')}")
    data = body.get("data")
    chunks_raw = []
    if isinstance(data, dict):
        chunks_raw = data.get("chunks") or []
    elif isinstance(data, list):
        chunks_raw = data

    results = []
    for c in chunks_raw:
        if len(results) >= top_k:
            break
        results.append(_to_chunk(c, ticker))
    return {
        "results": results,
        "dataset_id": dataset_id,
        "dataset_name": dataset_name,
        "meta_filter": meta_filter,
        "message": None,
    }


def _to_chunk(chunk: dict, ticker: str) -> dict:
    """Faithful port of RagflowKnowledgeBaseBackend#toChunkDTO."""
    meta_node = chunk.get("document_meta_fields") or chunk.get("meta_fields") or {}
    metadata: dict[str, Any] = {}
    if isinstance(meta_node, dict):
        for k, v in meta_node.items():
            metadata[k] = "" if v is None else str(v)
    metadata["similarity"] = float(chunk.get("similarity", 0) or 0)
    metadata["vector_similarity"] = float(chunk.get("vector_similarity", 0) or 0)
    metadata["term_similarity"] = float(chunk.get("term_similarity", 0) or 0)
    metadata["document_id_ragflow"] = chunk.get("document_id") or ""

    doc_id = metadata.get("document_id") or chunk.get("document_id") or ""
    fiscal_year = _parse_year(metadata.get("fiscal_year"))
    doc_name = chunk.get("document_name")

    return {
        "chunk_id": chunk.get("id") or "",
        "score": float(chunk.get("similarity", 0) or 0),
        "text": chunk.get("content") or "",
        "ticker": ticker,
        "document_id": doc_id,
        "form_type": metadata.get("form_type"),
        "fiscal_year": fiscal_year,
        "fiscal_period": metadata.get("fiscal_period"),
        "filing_date": metadata.get("filing_date"),
        "source_file_name": doc_name,
        "section_title": chunk.get("document_keyword"),
        "chunk_type": "ragflow",
        "category": metadata.get("category"),
        "citation": doc_name,
        "metadata": metadata,
    }


def _parse_year(v: Any) -> int | None:
    if v is None or v == "":
        return None
    try:
        return int(v)
    except (TypeError, ValueError):
        return None
