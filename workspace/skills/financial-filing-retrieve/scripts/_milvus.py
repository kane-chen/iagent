"""Milvus retrieval backend.

Port of the vector-recall path in Java MilvusKnowledgeBaseBackend#retrieve. The
Java implementation also runs a BM25F + semantic-profile rerank on the candidate
set; that rerank layer is NOT ported here. We rely on Milvus's own vector search
for ranking and return the top-K as-is. This keeps the skill self-contained and
matches the "recall-first" behaviour agents actually need.

If summary candidates are requested, we first search the summary collection to
gather chunk_ids, then look up the full text in the primary collection filtered
by those chunk_ids — same behaviour as the Java retrieveWithSummaryCandidates
path.
"""

from __future__ import annotations

import json
from typing import Any

import requests


def _headers(token: str) -> dict[str, str]:
    h = {"Content-Type": "application/json"}
    if token:
        h["Authorization"] = f"Bearer {token}"
    return h


def _quote(v: Any) -> str:
    s = "" if v is None else str(v)
    s = s.replace("\\", "\\\\").replace('"', '\\"')
    return f'"{s}"'


def _build_filter(ticker: str, fiscal_year: str | None, form_type: str | None,
                  category: str | None, chunk_ids: list[str] | None) -> str:
    parts: list[str] = []
    if ticker:
        parts.append(f"ticker == {_quote(ticker.upper())}")
    if fiscal_year:
        # Java allows numeric year; keep raw for numeric column, quote as fallback.
        try:
            int(fiscal_year)
            parts.append(f"fiscal_year == {fiscal_year}")
        except ValueError:
            parts.append(f"fiscal_year == {_quote(fiscal_year)}")
    if form_type:
        parts.append(f"form_type == {_quote(form_type)}")
    if category:
        parts.append(f"category == {_quote(category)}")
    if chunk_ids:
        joined = ",".join(_quote(x) for x in chunk_ids)
        parts.append(f"chunk_id in [{joined}]")
    return " && ".join(parts)


def _embed(text: str, embedding_cfg: dict) -> list[float]:
    """Talk to the embedding endpoint. Handles Ollama /api/embed and OpenAI-compatible /embeddings."""
    base_url = embedding_cfg.get("baseUrl", "").rstrip("/")
    api_key = embedding_cfg.get("apiKey", "")
    model = embedding_cfg.get("model", "")
    timeout = int(embedding_cfg.get("requestTimeoutSeconds", 60))
    if not base_url:
        raise RuntimeError("embedding.baseUrl is not configured")

    def _post(url: str, body: dict) -> requests.Response:
        headers = {"Content-Type": "application/json"}
        if api_key:
            headers["Authorization"] = f"Bearer {api_key}"
        return requests.post(url, headers=headers, data=json.dumps(body), timeout=timeout)

    # First attempt mirrors Java ModelEmbeddingService: {model, input}
    resp = _post(base_url, {"model": model, "input": text})
    # Ollama legacy fallback: switch endpoint and payload key
    if resp.status_code == 404 and base_url.endswith("/api/embed"):
        legacy_url = base_url.rsplit("/api/embed", 1)[0] + "/api/embeddings"
        resp = _post(legacy_url, {"model": model, "prompt": text})
    if resp.status_code < 200 or resp.status_code >= 300:
        raise RuntimeError(f"Embedding request failed: HTTP {resp.status_code} {resp.text[:400]}")
    data = resp.json() or {}
    return _extract_embedding(data)


def _extract_embedding(payload: dict) -> list[float]:
    embeddings = payload.get("embeddings")
    if isinstance(embeddings, list) and embeddings:
        first = embeddings[0]
        if isinstance(first, list):
            return [float(x) for x in first]
        return [float(x) for x in embeddings]
    embedding = payload.get("embedding")
    if isinstance(embedding, list):
        return [float(x) for x in embedding]
    data = payload.get("data")
    if isinstance(data, list) and data:
        first = data[0]
        if isinstance(first, dict) and isinstance(first.get("embedding"), list):
            return [float(x) for x in first["embedding"]]
    raise RuntimeError("Embedding response does not contain embeddings/embedding/data[0].embedding")


OUTPUT_FIELDS = [
    "chunk_id", "ticker", "document_id", "form_type", "fiscal_year", "fiscal_period",
    "filing_date", "chunk_type", "source_file_name", "section_title", "category",
    "text", "metadata_json",
]


def _milvus_search(*, endpoint: str, token: str, collection: str,
                   embedding: list[float], filter_expr: str, top_k: int,
                   timeout: int) -> list[dict]:
    body = {
        "collectionName": collection,
        "data": [embedding],
        "limit": max(1, top_k),
        "outputFields": OUTPUT_FIELDS,
    }
    if filter_expr:
        body["filter"] = filter_expr
    resp = requests.post(
        f"{endpoint.rstrip('/')}/v2/vectordb/entities/search",
        headers=_headers(token),
        json=body,
        timeout=timeout,
    )
    if resp.status_code < 200 or resp.status_code >= 300:
        raise RuntimeError(f"Milvus search failed: HTTP {resp.status_code} {resp.text[:400]}")
    data = (resp.json() or {}).get("data") or []
    return list(data) if isinstance(data, list) else []


def _from_row(row: dict) -> dict:
    entity = row.get("entity") if isinstance(row.get("entity"), dict) else row
    metadata_json = entity.get("metadata_json")
    metadata: dict = {}
    if metadata_json:
        try:
            metadata = json.loads(metadata_json)
        except (TypeError, ValueError):
            metadata = {}
    ticker = entity.get("ticker") or ""
    doc_id = entity.get("document_id") or ""
    src_name = entity.get("source_file_name") or ""
    return {
        "chunk_id": entity.get("chunk_id"),
        "score": row.get("distance"),
        "text": entity.get("text"),
        "ticker": ticker,
        "document_id": doc_id,
        "form_type": entity.get("form_type"),
        "fiscal_year": entity.get("fiscal_year"),
        "fiscal_period": entity.get("fiscal_period"),
        "filing_date": entity.get("filing_date"),
        "source_file_name": src_name,
        "section_title": entity.get("section_title"),
        "chunk_type": entity.get("chunk_type"),
        "category": entity.get("category"),
        "citation": f"{ticker} {doc_id} {src_name}".strip(),
        "metadata": metadata,
    }


def retrieve(
    *,
    query: str,
    ticker: str,
    top_k: int,
    fiscal_year: str | None,
    form_type: str | None,
    category: str | None,
    use_summary_candidates: bool,
    milvus_cfg: dict,
    embedding_cfg: dict,
    vector_top_k: int,
) -> dict:
    endpoint = milvus_cfg.get("endpoint", "http://127.0.0.1:19530")
    token = milvus_cfg.get("token", "")
    collection = milvus_cfg.get("collection", "invest_filing_test")
    summary_collection = milvus_cfg.get(
        "summaryCollection", collection + "_summaries"
    )
    timeout = int(milvus_cfg.get("requestTimeoutSeconds", 60))

    embedding = _embed(query, embedding_cfg)
    limit = max(1, top_k)
    recall = max(limit, int(vector_top_k))

    base_filter = _build_filter(ticker, fiscal_year, form_type, category, None)

    if use_summary_candidates:
        candidate_rows = _milvus_search(
            endpoint=endpoint, token=token, collection=summary_collection,
            embedding=embedding, filter_expr=base_filter,
            top_k=max(recall, 20), timeout=timeout,
        )
        chunk_ids: list[str] = []
        summary_scores: dict[str, float] = {}
        for r in candidate_rows:
            entity = r.get("entity") if isinstance(r.get("entity"), dict) else r
            cid = entity.get("chunk_id")
            if cid:
                chunk_ids.append(cid)
                score = r.get("distance")
                if score is not None:
                    summary_scores[cid] = float(score)
        if chunk_ids:
            detail_filter = _build_filter(ticker, fiscal_year, form_type, category, chunk_ids)
            rows = _milvus_search(
                endpoint=endpoint, token=token, collection=collection,
                embedding=embedding, filter_expr=detail_filter,
                top_k=limit, timeout=timeout,
            )
            if rows:
                results = [_from_row(r) for r in rows]
                for r in results:
                    s = summary_scores.get(r.get("chunk_id"))
                    if s is not None:
                        meta = r.get("metadata") or {}
                        meta["summary_candidate_score"] = s
                        r["metadata"] = meta
                return {"results": results[:limit], "recall": len(rows)}
        # Fall through to primary search if no summary candidates.

    rows = _milvus_search(
        endpoint=endpoint, token=token, collection=collection,
        embedding=embedding, filter_expr=base_filter,
        top_k=recall, timeout=timeout,
    )
    results = [_from_row(r) for r in rows[:limit]]
    return {"results": results, "recall": len(rows)}
