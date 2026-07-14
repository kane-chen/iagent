"""Ollama / OpenAI-compatible embedding helpers.

Chat/completions 功能已迁移到 _llm 模块（通用 LLM 客户端，对齐 Java LlmClient）。
本模块仅保留 embedding 相关函数：embed / embed_batch。

向后兼容：从 _llm 重新导出 chat，旧代码 `from _ollama import chat` 仍可工作，
新代码应使用 `from _llm import chat`。
"""
from __future__ import annotations

import json

import requests

# 向后兼容 re-export（新代码请直接 from _llm import chat）
from _llm import chat  # noqa: F401


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


# 私有 helper 模块——被直接当命令执行时立即报错，避免 LLM 误当作 CLI 入口
if __name__ == "__main__":
    import sys
    sys.stderr.write(
        "ERROR: _ollama.py 是 financial-filing-qa skill 的内部 helper 模块（Ollama embed/chat 封装），不是 CLI 入口。\n"
        "正确用法：python workspace/skills/financial-filing-qa/scripts/qa.py --question <Q> --ticker <TICKER>\n"
    )
    sys.exit(2)
