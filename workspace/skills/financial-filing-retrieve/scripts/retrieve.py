#!/usr/bin/env python3
"""financial-filing-retrieve skill entry.

Dispatches to milvus or ragflow backend based on config. Output JSON schema is
stable regardless of backend so agents can treat both as one data source.
"""

from __future__ import annotations

import argparse
import json
import sys
import traceback
from pathlib import Path

# On Windows the default stdout encoding is the system codepage (e.g. GBK),
# which would corrupt Chinese fields in the JSON output. Force UTF-8 so the
# emitted JSON is always safe to parse by downstream consumers.
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:  # noqa: BLE001 -- best effort; fall back to default
        pass

# Allow "python scripts/retrieve.py" from any cwd.
_HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(_HERE))

from _common import (  # noqa: E402
    dump,
    env_override,
    fail,
    load_config,
    resolve_category,
)


def _parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Retrieve filing chunks from KB backend")
    p.add_argument("--query", required=True, help="检索问题")
    p.add_argument("--ticker", required=True, help="股票代码，例如 BABA")
    p.add_argument("--top-k", type=int, default=None, help="返回条数，默认取 config.resultTopK")
    p.add_argument("--fiscal-year", default=None, help="财年过滤，例如 2024")
    p.add_argument("--form-type", default=None, help="表单类型，例如 10-K / 20-F / 6-K")
    p.add_argument("--category", default=None, help="内容分类；不传则从 query 自动推断")
    p.add_argument("--use-summary-candidates", action="store_true",
                   help="Milvus 后端：先在 chunk_summary 候选中召回再取原文")
    p.add_argument("--backend", default=None, choices=("milvus", "ragflow"),
                   help="覆盖 config.backend")
    p.add_argument("--config", default=None, help="config 文件路径")
    p.add_argument("--pretty", action="store_true", help="缩进输出")
    return p.parse_args()


def main() -> None:
    args = _parse_args()
    ticker = (args.ticker or "").strip().upper()
    query = (args.query or "").strip()
    if not query:
        fail(query, ticker, "query 不能为空")
    if not ticker:
        fail(query, ticker, "ticker 不能为空")

    try:
        config = env_override(load_config(args.config))
    except Exception as e:
        fail(query, ticker, f"加载配置失败: {e}")
        return  # unreachable, keeps type-checkers happy

    backend = (args.backend or config.get("backend") or "milvus").lower()
    result_top_k_default = int(config.get("resultTopK", 5))
    top_k = args.top_k if args.top_k and args.top_k > 0 else result_top_k_default
    vector_top_k = max(top_k * int(config.get("summaryCandidateMultiplier", 4)),
                       int(config.get("vectorTopK", 50)))

    keyword_map = config.get("categoryKeywords") or {}
    effective_category, inferred_category = resolve_category(args.category, query, keyword_map)

    try:
        if backend == "ragflow":
            from _ragflow import retrieve as ragflow_retrieve  # local import
            outcome = ragflow_retrieve(
                query=query,
                ticker=ticker,
                top_k=top_k,
                fiscal_year=args.fiscal_year,
                form_type=args.form_type,
                category=effective_category,
                ragflow_cfg=config.get("ragflow") or {},
            )
            payload = {
                "success": True,
                "query": query,
                "ticker": ticker,
                "top_k": top_k,
                "backend": "ragflow",
                "category": effective_category,
                "inferred_category": inferred_category,
                "results": outcome.get("results") or [],
                "count": len(outcome.get("results") or []),
                "metadata": {
                    "dataset_id": outcome.get("dataset_id"),
                    "dataset_name": outcome.get("dataset_name"),
                    "meta_filter": outcome.get("meta_filter") or {},
                },
            }
            if outcome.get("message"):
                payload["message"] = outcome["message"]
            elif not payload["results"]:
                payload["message"] = "未检索到相关内容"
            else:
                payload["message"] = f"检索到 {payload['count']} 条相关内容"
        elif backend == "milvus":
            from _milvus import retrieve as milvus_retrieve  # local import
            outcome = milvus_retrieve(
                query=query,
                ticker=ticker,
                top_k=top_k,
                fiscal_year=args.fiscal_year,
                form_type=args.form_type,
                category=effective_category,
                use_summary_candidates=args.use_summary_candidates,
                milvus_cfg=config.get("milvus") or {},
                embedding_cfg=config.get("embedding") or {},
                vector_top_k=vector_top_k,
            )
            results = outcome.get("results") or []
            payload = {
                "success": True,
                "query": query,
                "ticker": ticker,
                "top_k": top_k,
                "backend": "milvus",
                "category": effective_category,
                "inferred_category": inferred_category,
                "results": results,
                "count": len(results),
                "metadata": {
                    "vector_candidates": outcome.get("recall", len(results)),
                    "use_summary_candidates": bool(args.use_summary_candidates),
                },
                "message": f"检索到 {len(results)} 条相关内容" if results else "未检索到相关内容",
            }
        else:
            fail(query, ticker, f"未知 backend: {backend}")
            return  # unreachable
    except Exception as e:
        detail = f"{type(e).__name__}: {e}"
        traceback.print_exc(file=sys.stderr)
        fail(query, ticker, detail)
        return  # unreachable

    dump(payload, args.pretty)


if __name__ == "__main__":
    main()
