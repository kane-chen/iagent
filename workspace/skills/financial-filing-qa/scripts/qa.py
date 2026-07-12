#!/usr/bin/env python3
"""CLI entry for financial-filing-qa skill.

Modes:
  search  — emit single-line JSON with ranked chunks.
  answer  — retrieve chunks, synthesize Chinese answer via LLM with [Cn] citations.

Examples:
  python qa.py --question "腾讯云收入增长" --ticker 00700 --mode answer
  python qa.py --question "EBITA 下滑原因" --ticker BABA --from-period 2025Q1 --pretty
"""
from __future__ import annotations

import argparse
import os
import sys
import time
from pathlib import Path

# Allow running from any cwd by adding the scripts dir to sys.path
sys.path.insert(0, str(Path(__file__).resolve().parent))

from _common import load_config, parse_period, fail, dump_json
from _llm import chat
import _milvus
import _ragflow
import _textsearch


SYSTEM_PROMPT = """你是一个严谨的投研助理。你必须仅基于下面[引用片段]中的信息回答用户的问题，不得使用外部知识。
回答要求：
1. 所有数字、结论、关键判断必须在句末标注引用编号，例如"收入同比增长12%[C1][C2]"。
2. 如果不同片段信息冲突，请分别列出并说明差异。
3. 如果引用片段不足以回答问题，明确说明"在提供的财报片段中未找到相关信息"。
4. 回答使用简体中文，条理清晰，先给结论再给依据。
5. 回答末尾必须单独起一行写"## 引用来源"，随后逐条列出每个实际引用到的片段的≤80字摘要，格式：
   [Cn] {ticker} {formType}{fiscalYear}{fiscalPeriod} {sectionTitle} p.{page}: {摘要}
   只列出在正文中实际被引用到的编号。
"""


def build_citation_prefix(idx: int, c: dict) -> str:
    parts = []
    if c.get("ticker"): parts.append(str(c["ticker"]))
    if c.get("formType"): parts.append(str(c["formType"]))
    if c.get("fiscalYear"): parts.append(str(c["fiscalYear"]))
    if c.get("fiscalPeriod"): parts.append(str(c["fiscalPeriod"]))
    if c.get("sectionTitle"): parts.append(str(c["sectionTitle"]))
    if c.get("pageNumber") is not None: parts.append(f"p.{c['pageNumber']}")
    return f"[{idx}] " + " ".join(parts)


def render_citation_line(idx: int, c: dict) -> str:
    s = build_citation_prefix(idx, c)
    content = (c.get("content") or "").replace("\n", " ").strip()
    if len(content) > 80:
        content = content[:77] + "..."
    return f"[C{idx}] {s}: {content}" if s.endswith(":") or ": " not in s else s + ": " + content


def truncate_chunks(chunks: list[dict], budget_tokens: int = 12000) -> list[dict]:
    """Rough char-based truncation to fit LLM context (4 chars ≈ 1 token for ASCII, 1.5 CJK ≈ 1)."""
    out = []
    total = 0
    for c in chunks:
        text = c.get("content") or ""
        # rough estimate
        est = max(1, len(text) // 3)
        if total + est > budget_tokens and out:
            break
        out.append(c)
        total += est
    return out


def _resolve_workspace(args) -> Path:
    """解析 workspace 根目录。"""
    if args.workspace:
        return Path(args.workspace).resolve()
    env_ws = os.environ.get("IAGENT_WORKSPACE_DIR")
    if env_ws:
        return Path(env_ws).resolve()
    # scripts/ -> financial-filing-qa/ -> skills/ -> workspace/
    return Path(__file__).resolve().parent.parent.parent.parent


def _do_search_backend(args, cfg, backend, top_k, sim_thresh, recall_mult,
                        ticker, fiscal_period, from_year, to_year, workspace):
    if backend == "ragflow":
        return _ragflow.search(
            query=args.question, ticker=ticker, top_k=top_k,
            form_type=args.form_type, fiscal_period=fiscal_period,
            from_year=from_year, to_year=to_year, keyword=args.keyword,
            similarity_threshold=sim_thresh, recall_multiplier=recall_mult,
            ragflow_cfg=cfg["ragflow"], embedding_cfg=cfg["embedding"],
        )
    if backend == "textsearch":
        return _textsearch.search(
            query=args.question, ticker=ticker, top_k=top_k,
            form_type=args.form_type, fiscal_period=fiscal_period,
            from_year=from_year, to_year=to_year, keyword=args.keyword,
            similarity_threshold=sim_thresh, recall_multiplier=recall_mult,
            ts_cfg=cfg.get("textsearch", {}), llm_cfg=cfg.get("llm", {}),
            workspace=workspace,
        )
    return _milvus.search(
        query=args.question, ticker=ticker, top_k=top_k,
        form_type=args.form_type, fiscal_period=fiscal_period,
        from_year=from_year, to_year=to_year, keyword=args.keyword,
        similarity_threshold=sim_thresh, recall_multiplier=recall_mult,
        milvus_cfg=cfg["milvus"], embedding_cfg=cfg["embedding"],
    )


def do_search(args, cfg):
    ticker = args.ticker.upper() if args.ticker else None
    from_year, _ = parse_period(args.from_period)
    to_year, to_period = parse_period(args.to_period)
    fiscal_period = None
    if args.from_period:
        _, fp = parse_period(args.from_period)
        if fp:
            fiscal_period = fp
    if to_period:
        fiscal_period = to_period
    backend = args.backend or cfg.get("backend", "milvus")
    top_k = args.top_k or int(cfg.get("topK", 5))
    sim_thresh = float(cfg.get("similarityThreshold", 0.3))
    recall_mult = int(cfg.get("vectorMultiplier", 3)) if "vectorMultiplier" in cfg else 3
    workspace = _resolve_workspace(args)
    t0 = time.time()
    chunks = _do_search_backend(args, cfg, backend, top_k, sim_thresh, recall_mult,
                                 ticker, fiscal_period, from_year, to_year, workspace)
    result = {
        "queryId": str(time.time_ns()),
        "question": args.question,
        "ticker": ticker,
        "backend": backend,
        "elapsedMs": int((time.time() - t0) * 1000),
        "chunks": chunks,
    }
    dump_json(result, args.pretty)


def do_answer(args, cfg):
    ticker = args.ticker.upper() if args.ticker else None
    from_year, _ = parse_period(args.from_period)
    to_year, to_period = parse_period(args.to_period)
    fiscal_period = None
    if args.from_period:
        _, fp = parse_period(args.from_period)
        if fp: fiscal_period = fp
    if to_period:
        fiscal_period = to_period
    backend = args.backend or cfg.get("backend", "milvus")
    top_k = args.top_k or int(cfg.get("topK", 5))
    sim_thresh = float(cfg.get("similarityThreshold", 0.3))
    recall_mult = int(cfg.get("vectorMultiplier", 3)) if "vectorMultiplier" in cfg else 3
    workspace = _resolve_workspace(args)
    t0 = time.time()

    chunks = _do_search_backend(args, cfg, backend, top_k * 2, sim_thresh, recall_mult,
                                 ticker, fiscal_period, from_year, to_year, workspace)

    # Sort by score desc then truncate
    chunks.sort(key=lambda c: -(c.get("score") or 0.0))
    chunks = truncate_chunks(chunks, 12000)
    chunks = chunks[:top_k * 2]

    if not chunks:
        answer_text = "在提供的财报片段中未找到相关信息。"
        if args.json:
            dump_json({
                "queryId": str(time.time_ns()),
                "question": args.question, "answer": answer_text,
                "backend": backend, "model": cfg["llm"].get("model"),
                "elapsedMs": int((time.time() - t0) * 1000), "citations": [],
            }, args.pretty)
        else:
            print(answer_text)
        return

    context_parts = []
    for i, c in enumerate(chunks, start=1):
        header = build_citation_prefix(i, c)
        context_parts.append(f"{header}\n{c.get('content', '')}")
    context = "\n\n".join(context_parts)
    user_prompt = f"[引用片段]\n{context}\n\n用户问题：{args.question}"

    model = cfg["llm"].get("model", "qwen3.5:4b")
    elapsed_ms = 0
    try:
        ans = chat(SYSTEM_PROMPT, user_prompt, cfg["llm"])
        # Normalize [n] markers to [Cn]
        import re as _re
        ans = _re.sub(r"\[(\d+)\]", lambda m: f"[C{m.group(1)}]", ans)
        elapsed_ms = int((time.time() - t0) * 1000)
        if args.json:
            dump_json({
                "queryId": str(time.time_ns()),
                "question": args.question, "answer": ans,
                "backend": backend, "model": model,
                "elapsedMs": elapsed_ms,
                "citations": [
                    {**c, "citationIndex": i + 1} for i, c in enumerate(chunks)
                ],
            }, args.pretty)
        else:
            print(ans)
            if "## 引用来源" not in ans:
                print("\n## 引用来源")
                for i, c in enumerate(chunks, start=1):
                    print(render_citation_line(i, c))
            print(f"\n(backend={backend}, model={model}, elapsed={elapsed_ms}ms)")
    except Exception as e:
        fail(f"LLM call failed: {e}")


def main():
    p = argparse.ArgumentParser(description="Financial Filing QA CLI")
    p.add_argument("--question", required=True, help="问题")
    p.add_argument("--ticker", required=True, help="股票代码，如 BABA")
    p.add_argument("--from-period", default=None, help="起始报告期")
    p.add_argument("--to-period", default=None, help="结束报告期")
    p.add_argument("--form-type", default=None, help="FY/Q1/H1 等")
    p.add_argument("--keyword", default=None, help="关键词过滤")
    p.add_argument("--mode", default="answer", choices=["search", "answer"])
    p.add_argument("--top-k", type=int, default=None)
    p.add_argument("--backend", default=None, choices=["milvus", "ragflow", "textsearch"])
    p.add_argument("--pretty", action="store_true")
    p.add_argument("--json", action="store_true", help="answer 模式下输出完整 JSON")
    p.add_argument("--config", default=None, help="自定义配置文件路径")
    p.add_argument("--workspace", default=None, help="workspace根目录路径，默认从环境变量IAGENT_WORKSPACE_DIR或脚本位置推导")
    args = p.parse_args()

    try:
        cfg = load_config(args.config)
    except Exception as e:
        fail(f"failed to load config: {e}")

    if args.mode == "search":
        do_search(args, cfg)
    else:
        do_answer(args, cfg)


if __name__ == "__main__":
    main()
