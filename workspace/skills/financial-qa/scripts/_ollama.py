"""Ollama / OpenAI-compatible embedding + chat helpers."""
from __future__ import annotations

import json
from typing import Any

import requests


def _post_json(url: str, body: dict, api_key: str = "", timeout: int = 120) -> dict:
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    resp = requests.post(url, headers=headers, data=json.dumps(body), timeout=timeout)
    if resp.status_code < 200 or resp.status_code >= 300:
        raise RuntimeError(f"HTTP {resp.status_code} from {url}: {resp.text[:500]}")
    return resp.json() or {}


def embed(text: str, embedding_cfg: dict) -> list[float]:
    base = embedding_cfg.get("baseUrl", "http://localhost:11434/api/embed").rstrip("/")
    model = embedding_cfg.get("model", "qwen3-embedding:4b")
    api_key = embedding_cfg.get("apiKey", "")
    timeout = int(embedding_cfg.get("requestTimeoutSeconds", 120))
    try:
        data = _post_json(base, {"model": model, "input": text}, api_key, timeout)
    except RuntimeError:
        if base.endswith("/api/embed"):
            legacy = base.rsplit("/api/embed", 1)[0] + "/api/embeddings"
            data = _post_json(legacy, {"model": model, "prompt": text}, api_key, timeout)
        else:
            raise
    return _extract_embedding(data)


def embed_batch(texts: list[str], embedding_cfg: dict) -> list[list[float]]:
    """Batch embed; falls back to one-by-one if /api/embed is unavailable."""
    base = embedding_cfg.get("baseUrl", "http://localhost:11434/api/embed").rstrip("/")
    model = embedding_cfg.get("model", "qwen3-embedding:4b")
    api_key = embedding_cfg.get("apiKey", "")
    timeout = int(embedding_cfg.get("requestTimeoutSeconds", 120))
    try:
        data = _post_json(base, {"model": model, "input": texts}, api_key, timeout)
    except RuntimeError:
        return [embed(t, embedding_cfg) for t in texts]
    embeddings = data.get("embeddings")
    if isinstance(embeddings, list) and embeddings and isinstance(embeddings[0], list):
        return [[float(x) for x in e] for e in embeddings]
    # Single-vector response wrapped in embeddings
    if isinstance(embeddings, list) and embeddings:
        return [[float(x) for x in embeddings]]
    dlist = data.get("data")
    if isinstance(dlist, list) and dlist:
        return [[float(x) for x in (d.get("embedding") or [])] for d in dlist]
    raise RuntimeError(f"Unrecognized embedding batch response: {list(data.keys())}")


def _extract_embedding(payload: dict) -> list[float]:
    embeddings = payload.get("embeddings")
    if isinstance(embeddings, list) and embeddings:
        first = embeddings[0]
        if isinstance(first, list):
            return [float(x) for x in first]
        return [float(x) for x in embeddings]
    emb = payload.get("embedding")
    if isinstance(emb, list):
        return [float(x) for x in emb]
    dlist = payload.get("data")
    if isinstance(dlist, list) and dlist:
        first = dlist[0]
        if isinstance(first, dict) and isinstance(first.get("embedding"), list):
            return [float(x) for x in first["embedding"]]
    raise RuntimeError("Embedding response missing embeddings/embedding/data[0].embedding")


def chat(system_prompt: str, user_prompt: str, llm_cfg: dict) -> str:
    """Call /chat/completions and return the assistant content string."""
    base = llm_cfg.get("baseUrl", "http://localhost:11434/v1").rstrip("/")
    model = llm_cfg.get("model", "qwen3:4b")
    api_key = llm_cfg.get("apiKey", "")
    temperature = float(llm_cfg.get("temperature", 0.2))
    max_tokens = int(llm_cfg.get("maxTokens", 2048))
    timeout = int(llm_cfg.get("requestTimeoutSeconds", 180))
    body = {
        "model": model,
        "temperature": temperature,
        "max_tokens": max_tokens,
        "stream": False,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
    }
    data = _post_json(base + "/chat/completions", body, api_key, timeout)
    try:
        return data["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as e:
        raise RuntimeError(f"Unexpected chat response: {list(data.keys()) if isinstance(data, dict) else data}") from e
