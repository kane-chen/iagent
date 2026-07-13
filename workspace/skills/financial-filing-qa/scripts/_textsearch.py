"""TextSearch (BM25F + dictionary + LLM keyword extraction + LLM rerank) retrieval backend.

Mirrors Java TextSearchFilingRagBackend + QueryRewriter + KeywordScorer + SemanticReranker.
Auto-processes filings that exist in filings/ but lack processed/ chunks.json.
"""
from __future__ import annotations

import json
import math
import os
import re
import time
from pathlib import Path

from _llm import chat
from _process import ensure_processed


# ------------------------------------------------------------------
# 财报术语同义词词典（从 Java FinancialTermDictionary 移植）
# ------------------------------------------------------------------

TERM_GROUPS: list[list[str]] = [
    # 收入类
    ["收入", "营收", "营业收入", "revenue", "sales", "revenues", "营业额", "收益", "turnover"],
    ["净收入", "net revenue", "net sales"],
    # 利润类
    ["利润", "profit", "收益", "盈利", "gain"],
    ["净利润", "净利", "净收益", "net income", "net profit", "纯利", "纯利润", "淨利潤"],
    ["毛利", "gross profit", "毛利润"],
    ["毛利率", "gross margin", "gross profit margin"],
    ["营业利润", "经营利润", "经营亏损", "operating profit", "operating income", "营业收益", "营业亏损"],
    ["EBITDA", "ebitda"],
    ["EBITA", "ebita"],
    ["EBIT", "ebit", "息税前利润"],
    # 增长/下降类
    ["增长", "增加", "上升", "growth", "increase", "grow", "提升", "上涨"],
    ["下滑", "下降", "减少", "下跌", "降低", "decline", "decrease", "drop", "fall", "reduce"],
    ["同比", "去年同期", "year-over-year", "yoy", "compared to last year", "较上年"],
    ["环比", "上季度", "quarter-over-quarter", "qoq", "较上季"],
    # 现金流类
    ["现金流", "现金流量", "cash flow", "cash flows", "現金流"],
    ["经营现金流", "经营性现金流", "operating cash flow", "经营活动现金流"],
    ["自由现金流", "free cash flow", "fcf"],
    # 资产负债类
    ["资产", "asset", "assets", "資產"],
    ["负债", "liability", "liabilities", "debt", "債務", "負債"],
    ["资产负债表", "balance sheet"],
    ["权益", "equity", "股东权益", "shareholders equity", "所有者权益"],
    ["负债率", "负债比率", "debt ratio", "leverage", "杠杆率", "gearing ratio"],
    ["现金及等价物", "现金等价物", "cash and equivalents", "cash and cash equivalents"],
    # 费用类
    ["研发费用", "研发", "r&d", "research and development", "研发开支", "研发投入"],
    ["营销费用", "销售费用", "marketing expense", "selling expense", "营销开支", "销售及营销费用"],
    ["管理费用", "管理开支", "administrative expense", "g&a", "general and administrative"],
    ["运营费用", "营业费用", "operating expense", "opex"],
    ["资本支出", "capital expenditure", "capex", "资本开支"],
    ["所得税", "income tax", "tax expense"],
    # 业务分部
    ["分部", "部门", "业务分部", "segment", "segments", "分业务", "业务板块", "业务线"],
    ["业务", "business", "业务线", "业务板块"],
    # 指引展望
    ["指引", "展望", "guidance", "outlook", "forecast", "预期", "预计", "未来展望"],
    # 回购分红
    ["回购", "repurchase", "buyback", "share repurchase", "股份回购", "回購"],
    ["分红", "股息", "股利", "dividend", "dividends", "派息", "分红派息"],
    # 风险
    ["风险", "不确定性", "risk", "risks", "risk factors", "風險"],
    ["挑战", "challenge", "challenges", "困境"],
    # 用户/客户指标
    ["用户", "客户", "user", "users", "customer", "customers", "活跃用户", "月活"],
    ["MAU", "mau", "月活跃用户", "monthly active users"],
    ["DAU", "dau", "日活跃用户", "daily active users"],
    ["ARPU", "arpu", "每用户平均收入", "average revenue per user"],
    ["GMV", "gmv", "商品交易总额", "成交总额"],
    ["订阅用户", "付费用户", "subscriber", "subscribers", "paying users", "付费会员"],
    # 市场竞争
    ["市场份额", "市占率", "market share", "marketshare", "市场占有率"],
    ["竞争", "竞争对手", "competitor", "competitors", "competition", "competitive"],
    # 成本类
    ["成本", "cost", "costs", "cost of revenue", "营业成本", "收入成本"],
    # 运营指标
    ["利润率", "profit margin", "margin", "净利率", "net margin"],
    ["经营利润率", "operating margin", "营业利润率"],
    # 员工
    ["员工", "雇员", "employee", "employees", "staff", "headcount", "人员"],
    ["裁员", "layoff", "layoffs", "headcount reduction", "人员优化"],
]

_EXPANSION_MAP: dict[str, set[str]] = {}
for _group in TERM_GROUPS:
    _terms = set(_group)
    for _term in _group:
        _EXPANSION_MAP[_term.lower()] = _terms


def _expand_terms(keywords: list[str]) -> set[str]:
    """Dictionary expansion: add synonyms for each keyword (mirrors FinancialTermDictionary.expand)."""
    result: set[str] = set()
    for kw in keywords:
        if not kw or not kw.strip():
            continue
        trimmed = kw.strip()
        result.add(trimmed)
        synonyms = _EXPANSION_MAP.get(trimmed.lower())
        if synonyms:
            result.update(synonyms)
    return result


def _format_term_groups_for_prompt() -> str:
    """Format term dictionary for LLM prompt (mirrors formatTermGroupsForPrompt)."""
    lines: list[str] = []
    for group in TERM_GROUPS:
        if not group:
            continue
        line = group[0]
        if len(group) > 1:
            line += "（" + "、".join(group[1:]) + "）"
        lines.append(line)
    return "\n".join(lines)


# ------------------------------------------------------------------
# Workspace 路径
# ------------------------------------------------------------------

def _resolve_workspace(cli_workspace: str | None) -> Path:
    """解析 workspace 根目录：CLI参数 > 环境变量 > 脚本位置向上推导。"""
    if cli_workspace:
        return Path(cli_workspace).resolve()
    env_ws = os.environ.get("IAGENT_WORKSPACE_DIR")
    if env_ws:
        return Path(env_ws).resolve()
    # scripts/ -> financial-filing-qa/ -> skills/ -> workspace/
    return Path(__file__).resolve().parent.parent.parent.parent


def _portfolio_dir(workspace: Path, ticker: str) -> Path:
    return workspace / "portfolio" / ticker.upper()


def _filings_dir(workspace: Path, ticker: str) -> Path:
    return _portfolio_dir(workspace, ticker) / "filings"


def _processed_dir(workspace: Path, ticker: str, document_id: str) -> Path:
    return _portfolio_dir(workspace, ticker) / "processed" / document_id


# ------------------------------------------------------------------
# 文档发现与chunk加载（mirrors discoverMatchingDocuments + chunkStore.loadChunks）
# ------------------------------------------------------------------

_FISCAL_PERIOD_SUFFIXES = [
    ("_FY", "FY"),
    ("Q1", "Q1"), ("Q2", "Q2"), ("Q3", "Q3"), ("Q4", "Q4"),
    ("_H1", "H1"), ("_H2", "H2"),
]


def _derive_fiscal_period(document_id: str) -> str | None:
    upper = document_id.upper()
    if upper.endswith("_FY"):
        return "FY"
    for substr, period in [("Q1", "Q1"), ("Q2", "Q2"), ("Q3", "Q3"), ("Q4", "Q4"),
                            ("_H1", "H1"), ("_H2", "H2")]:
        if substr in upper:
            return period
    return None


def _load_doc_meta(doc_dir: Path, ticker: str, document_id: str) -> dict:
    """Load document metadata from meta.json, falling back to documentId parsing."""
    form_type = None
    fiscal_year = None
    fiscal_period = None
    meta_file = doc_dir / "meta.json"
    if meta_file.is_file():
        try:
            data = json.loads(meta_file.read_text(encoding="utf-8"))
            form_type = data.get("formType")
            fiscal_year = data.get("fiscalYear")
            fiscal_period = data.get("fiscalPeriod")
            if not fiscal_period:
                fiscal_period = _derive_fiscal_period(document_id)
        except Exception:
            pass
    if not fiscal_period:
        fiscal_period = _derive_fiscal_period(document_id)
    return {
        "ticker": ticker.upper(),
        "documentId": document_id,
        "formType": form_type,
        "fiscalYear": fiscal_year,
        "fiscalPeriod": fiscal_period,
    }


def _matches_doc_filters(meta: dict, form_type: str | None, fiscal_period: str | None,
                         from_year: int | None, to_year: int | None) -> bool:
    """Document-level filtering (mirrors matchesDocumentFilters)."""
    if form_type and meta.get("formType"):
        if str(meta["formType"]).upper() != form_type.upper():
            return False
    if fiscal_period and meta.get("fiscalPeriod"):
        if str(meta["fiscalPeriod"]).upper() != fiscal_period.upper():
            return False
    fy = meta.get("fiscalYear")
    if fy is not None:
        if from_year is not None and int(fy) < from_year:
            return False
        if to_year is not None and int(fy) > to_year:
            return False
    return True


def _passes_chunk_filters(c: dict, form_type: str | None, fiscal_period: str | None,
                          from_year: int | None, to_year: int | None) -> bool:
    """Chunk-level filtering (mirrors passesChunkFilters)."""
    fy = c.get("fiscalYear")
    if fy is not None:
        if from_year is not None and int(fy) < from_year:
            return False
        if to_year is not None and int(fy) > to_year:
            return False
    if form_type and c.get("formType"):
        if str(c["formType"]).upper() != form_type.upper():
            return False
    if fiscal_period and c.get("fiscalPeriod"):
        if str(c["fiscalPeriod"]).upper() != fiscal_period.upper():
            return False
    return True


def _discover_and_load_chunks(workspace: Path, ticker: str, form_type: str | None,
                               fiscal_period: str | None, from_year: int | None,
                               to_year: int | None) -> list[dict]:
    """Discover matching documents and load chunks (mirrors discoverMatchingDocuments + load).

    Auto-processes filings that exist in filings/ but lack processed/ chunks.json.
    """
    ticker = ticker.upper()
    filings_root = _filings_dir(workspace, ticker)
    if not filings_root.is_dir():
        return []

    all_chunks: list[dict] = []
    try:
        doc_dirs = [p for p in filings_root.iterdir() if p.is_dir()]
    except OSError:
        return []

    for doc_dir in sorted(doc_dirs):
        document_id = doc_dir.name
        meta = _load_doc_meta(doc_dir, ticker, document_id)
        if not _matches_doc_filters(meta, form_type, fiscal_period, from_year, to_year):
            continue

        # Auto-process if chunks.json doesn't exist
        chunks_file = _processed_dir(workspace, ticker, document_id) / "chunks.json"
        if not chunks_file.is_file():
            try:
                was_processed = ensure_processed(workspace, ticker, document_id)
                if was_processed:
                    # Re-read to get chunk count
                    try:
                        processed_chunks = json.loads(chunks_file.read_text(encoding="utf-8"))
                        n_chunks = len(processed_chunks) if isinstance(processed_chunks, list) else 0
                    except Exception:
                        n_chunks = "?"
                    print(f"[textsearch] Auto-processed {document_id}: {n_chunks} chunks", flush=True)
            except Exception as e:
                print(f"[textsearch] Failed to process {document_id}: {e}", flush=True)
                continue

        if not chunks_file.is_file():
            continue

        try:
            doc_chunks = json.loads(chunks_file.read_text(encoding="utf-8"))
        except Exception:
            continue
        if not isinstance(doc_chunks, list):
            continue

        for c in doc_chunks:
            if _passes_chunk_filters(c, form_type, fiscal_period, from_year, to_year):
                all_chunks.append(c)
    return all_chunks


# ------------------------------------------------------------------
# CJK detection (mirrors Java isCJK with matching Unicode blocks)
# ------------------------------------------------------------------

def _is_cjk_char(ch: str) -> bool:
    if len(ch) != 1:
        return False
    cp = ord(ch)
    return (
        (0x4E00 <= cp <= 0x9FFF)   # CJK_UNIFIED_IDEOGRAPHS
        or (0x3400 <= cp <= 0x4DBF)  # CJK_UNIFIED_IDEOGRAPHS_EXT_A
        or (0x20000 <= cp <= 0x2A6DF)  # CJK_UNIFIED_IDEOGRAPHS_EXT_B
        or (0xF900 <= cp <= 0xFAFF)  # CJK_COMPATIBILITY_IDEOGRAPHS
        or (0x3000 <= cp <= 0x303F)  # CJK_SYMBOLS_AND_PUNCTUATION
        or (0xFF00 <= cp <= 0xFFEF)  # HALFWIDTH_AND_FULLWIDTH_FORMS
        or (0x3040 <= cp <= 0x309F)  # HIRAGANA
        or (0x30A0 <= cp <= 0x30FF)  # KATAKANA
        or (0xAC00 <= cp <= 0xD7AF)  # HANGUL_SYLLABLES
    )


# ------------------------------------------------------------------
# 查询改写：QueryRewriter (mirrors Java QueryRewriter)
# ------------------------------------------------------------------

MAX_KEYWORDS = 15
MIN_KW_LEN_CJK = 2
MIN_KW_LEN_ASCII = 3

REWRITE_SYSTEM_PROMPT_TEMPLATE = """从用户的问题中提取问题的关键词列表，以用作后续的知识库检索。
注意：
1、不要与用户交互。
2、只做关键词提取，不要尝试分析、理解和回答用户的问题。
3、直接输出关键字列表，不要思考过程，不要解释。
规则：
1、返回3-10个关键词
2、优先业务术语（见[业务术语]）
3、排除停用词（的、了、公司、什么、the、a、of等）。
4、输出格式：{{"keywords": ["词1","词2"]}}
---业务术语---
%s
---
"""

# Lazy init the prompt (embed term dictionary once)
_REWRITE_SYSTEM_PROMPT: str | None = None


def _get_rewrite_prompt() -> str:
    global _REWRITE_SYSTEM_PROMPT
    if _REWRITE_SYSTEM_PROMPT is None:
        _REWRITE_SYSTEM_PROMPT = REWRITE_SYSTEM_PROMPT_TEMPLATE % _format_term_groups_for_prompt()
    return _REWRITE_SYSTEM_PROMPT


def _extract_time_terms(question: str, keywords: set[str]) -> None:
    """Extract time-related terms: 4-digit years starting with 20, Q1-Q4, H1, H2, FY."""
    if not question:
        return
    for m in re.finditer(r"(20\d{2})", question):
        keywords.add(m.group(1))
    upper = question.upper()
    for p in ("Q1", "Q2", "Q3", "Q4", "H1", "H2", "FY"):
        if p in upper:
            keywords.add(p)


def _add_cjk_ngrams(text: str, terms: set[str]) -> None:
    """Add 2/3/4-char n-grams and full run (if 2-8 chars) from a CJK run."""
    n = len(text)
    for i in range(n - 1):
        terms.add(text[i:i + 2])
    for i in range(n - 2):
        terms.add(text[i:i + 3])
    for i in range(n - 3):
        terms.add(text[i:i + 4])
    if 2 <= n <= 8:
        terms.add(text)


def _extract_base_terms(question: str) -> set[str]:
    """Extract base terms (ASCII tokens, CJK n-grams). Used only as expansion seeds."""
    terms: set[str] = set()
    if not question:
        return terms
    # ASCII tokens
    for tok in re.split(r"[^a-z0-9]+", question.lower()):
        if len(tok) >= MIN_KW_LEN_ASCII:
            terms.add(tok)
    # CJK n-grams
    buf: list[str] = []
    for ch in question:
        if _is_cjk_char(ch):
            buf.append(ch)
        else:
            if buf:
                _add_cjk_ngrams("".join(buf), terms)
                buf = []
    if buf:
        _add_cjk_ngrams("".join(buf), terms)
    return terms


def _filter_by_length(keywords: set[str]) -> set[str]:
    """Filter out keywords that are too short."""
    result: set[str] = set()
    for kw in keywords:
        if not kw or not kw.strip():
            continue
        trimmed = kw.strip()
        if len(trimmed) < MIN_KW_LEN_CJK:
            continue
        all_ascii = all(ord(c) < 128 for c in trimmed)
        if all_ascii and len(trimmed) < MIN_KW_LEN_ASCII:
            continue
        result.add(trimmed)
    return result


def _parse_keywords_json(text: str) -> list[str]:
    """Parse keywords list from LLM JSON output."""
    if not text or not text.strip():
        return []
    j = text.strip()
    if j.startswith("```json"):
        j = j[7:]
    elif j.startswith("```"):
        j = j[3:]
    if j.endswith("```"):
        j = j[:-3]
    j = j.strip()
    try:
        obj = json.loads(j)
        arr = obj.get("keywords")
        if isinstance(arr, list):
            return [str(k).strip() for k in arr if k and str(k).strip()]
    except Exception:
        pass
    return []


def rewrite_query(question: str, keyword: str | None, llm_cfg: dict) -> set[str]:
    """Rewrite query to keyword set (mirrors QueryRewriter.rewrite).

    Priority order: explicit keyword -> LLM keywords -> dict expansion -> time terms.
    Returns ordered dict keys (preserving priority).
    """
    # Use dict to preserve insertion order (like Java LinkedHashSet)
    keywords: dict[str, None] = {}

    # 1. Explicit user keyword (highest priority)
    if keyword and keyword.strip():
        keywords[keyword.strip()] = None

    # 2. Extract time terms
    time_terms: set[str] = set()
    _extract_time_terms(question or "", time_terms)

    # 3. Extract base terms (for dictionary expansion seeds)
    base_terms = _extract_base_terms(question or "")

    # 4. LLM keyword extraction
    llm_keywords: list[str] = []
    if question and llm_cfg:
        try:
            llm_result = chat(
                _get_rewrite_prompt(),
                "用户问题：" + question,
                {**llm_cfg, "temperature": 0.0, "maxTokens": 1024},
                think=False,
            )
            llm_keywords = _parse_keywords_json(llm_result)
            for kw in llm_keywords:
                if kw.strip():
                    keywords[kw.strip()] = None
        except Exception:
            pass

    # 5. Dictionary expansion: seeds = LLM keywords ∪ base terms
    expand_seeds: list[str] = list(llm_keywords) + list(base_terms)
    expanded = _expand_terms(expand_seeds)
    for kw in expanded:
        keywords[kw] = None

    # 6. Time terms (lowest priority among generated keywords)
    for t in time_terms:
        keywords[t] = None

    # 7. Filter by length
    filtered = _filter_by_length(set(keywords.keys()))

    # 8. Cap at MAX_KEYWORDS, preserving priority (insertion order)
    result: set[str] = set()
    for i, kw in enumerate(keywords.keys()):
        if i >= MAX_KEYWORDS:
            break
        if kw in filtered:
            result.add(kw)
    return result


# ------------------------------------------------------------------
# BM25F 关键词评分 (mirrors KeywordScorer)
# ------------------------------------------------------------------

TITLE_WEIGHT = 3.0
CONTENT_WEIGHT = 1.0
K1 = 1.2
TITLE_B = 0.35
CONTENT_B = 0.75

# 预编译正则：4位年份 + 周期标识（Q1-Q4 / H1-H2 / FY），严格匹配整串
_FISCAL_PERIOD_PATTERN = re.compile(r"^(\d{4})(Q[1-4]|H[12]|FY)$")


def _parse_fiscal_period(text: str) -> tuple[int, str] | None:
    """校验并解析财年周期字符串，示例：2001Q1、2020H1、2026FY。

    返回 (fiscalYear, period)；输入为空或格式非法时返回 None。
    """
    if not text or not text.strip():
        return None
    m = _FISCAL_PERIOD_PATTERN.match(text.strip())
    if not m:
        return None
    return int(m.group(1)), m.group(2)


def _filter_by_fiscal_period(chunks: list[dict], keywords: set[str]) -> list[dict]:
    """基于 keywords 中的财年周期串（如 2024Q1、2023FY）过滤 chunks。

    - 若 keywords 中不含任何合法财年周期串，直接返回原 chunks（不过滤）。
    - 否则仅保留 fiscalYear/fiscalPeriod 同时匹配的 chunk。
    """
    if not chunks or not keywords:
        return chunks
    periods: dict[int, list[str]] = {}
    for kw in keywords:
        parsed = _parse_fiscal_period(kw)
        if parsed is None:
            continue
        year, period = parsed
        periods.setdefault(year, []).append(period)
    if not periods:
        return chunks
    result: list[dict] = []
    for c in chunks:
        if c is None:
            continue
        fy = c.get("fiscalYear")
        fp = c.get("fiscalPeriod")
        if fy is None or not fp or not str(fp).strip():
            continue
        try:
            fy_int = int(fy)
        except (TypeError, ValueError):
            continue
        if fy_int in periods and fp in periods[fy_int]:
            result.append(c)
    return result


def _count_occurrences(s: str, sub: str) -> int:
    """Count non-overlapping occurrences of sub in s (case-insensitive)."""
    if not s or not sub:
        return 0
    count = 0
    idx = 0
    sl = s.lower()
    subl = sub.lower()
    while True:
        idx = sl.find(subl, idx)
        if idx == -1:
            break
        count += 1
        idx += len(subl)
    return count


def _normalized_tf(tf: int, field_len: int, avg_len: float, b: float) -> float:
    if tf <= 0:
        return 0.0
    if field_len <= 0 or avg_len <= 0:
        return float(tf)
    denom = 1.0 - b + b * (field_len / avg_len)
    if denom <= 0:
        return float(tf)
    return tf / denom


def _normalize_score(raw: float) -> float:
    """Normalize BM25F raw score to (0,1) via tanh."""
    if raw <= 0:
        return 0.0
    return math.tanh(raw / 5.0)


def bm25f_score(chunks: list[dict], keywords: set[str], top_n: int,
                min_score: float) -> list[tuple[dict, float]]:
    """Score chunks using BM25F over title+content fields, return top-n sorted by score desc."""
    if not chunks or not keywords:
        return []

    # 0. 预处理：基于财年周期关键词过滤（如 2024Q1、2023FY）
    chunks = _filter_by_fiscal_period(chunks, keywords)
    if not chunks:
        return []

    n = len(chunks)
    fields_list: list[tuple[dict, str, str]] = []
    title_df: dict[str, int] = {}
    content_df: dict[str, int] = {}
    total_title_len = 0
    total_content_len = 0

    for c in chunks:
        title = (c.get("sectionTitle") or "").lower()
        content = (c.get("content") or "").lower()
        fields_list.append((c, title, content))
        total_title_len += len(title)
        total_content_len += len(content)
        seen_title: set[str] = set()
        seen_content: set[str] = set()
        for kw in keywords:
            kwl = kw.lower()
            if not kwl:
                continue
            if _count_occurrences(title, kwl) > 0 and kwl not in seen_title:
                title_df[kwl] = title_df.get(kwl, 0) + 1
                seen_title.add(kwl)
            if _count_occurrences(content, kwl) > 0 and kwl not in seen_content:
                content_df[kwl] = content_df.get(kwl, 0) + 1
                seen_content.add(kwl)

    avg_title_len = total_title_len / n if n > 0 else 0.0
    avg_content_len = total_content_len / n if n > 0 else 0.0

    scored: list[tuple[dict, float]] = []
    for c, title, content in fields_list:
        title_len = len(title)
        content_len = len(content)
        bm25f = 0.0

        for kw in keywords:
            kwl = kw.lower()
            if not kwl:
                continue
            df = max(title_df.get(kwl, 0), content_df.get(kwl, 0))
            if df <= 0:
                continue
            idf = math.log(1.0 + (n - df + 0.5) / (df + 0.5))
            tf_title = _count_occurrences(title, kwl)
            tf_content = _count_occurrences(content, kwl)
            weighted_tf = 0.0
            if tf_title > 0:
                weighted_tf += TITLE_WEIGHT * _normalized_tf(tf_title, title_len, avg_title_len, TITLE_B)
            if tf_content > 0:
                weighted_tf += CONTENT_WEIGHT * _normalized_tf(tf_content, content_len, avg_content_len, CONTENT_B)
            if weighted_tf <= 0:
                continue
            bm25f += idf * ((K1 + 1.0) * weighted_tf) / (K1 + weighted_tf)

        normalized = _normalize_score(bm25f)
        if normalized > min_score:
            c["score"] = normalized
            scored.append((c, normalized))

    scored.sort(key=lambda x: -x[1])
    return scored[:top_n]


# ------------------------------------------------------------------
# LLM 语义重排序 (mirrors SemanticReranker)
# ------------------------------------------------------------------

CHUNK_EXCERPT_CHARS = 600

RERANK_SYSTEM_PROMPT = """你是一个精准的文档相关性评分器。根据用户问题，评估每个财报片段的语义相关性。
评分标准（0-10分）：
- 9-10分：直接回答用户问题的核心数据/事实
- 7-8分：高度相关，包含问题所需的关键背景信息
- 5-6分：部分相关，提及相关主题但缺少关键数据
- 3-4分：弱相关，仅涉及同一大类话题
- 0-2分：不相关
直接输出JSON，不要思考过程，不要解释。
必须为每个片段评分，index从1开始，按输入顺序编号。
格式：{"ranked":[{"index":1,"score":8},{"index":2,"score":5},...]}"""


def _build_rerank_prompt(question: str, chunks: list[dict]) -> str:
    """Build user prompt for reranking."""
    parts = [f"用户问题：{question}\n\n", "待评估的财报片段（请对每个片段评分0-10）：\n"]
    for i, c in enumerate(chunks, start=1):
        parts.append(f"[{i}] ")
        if c.get("sectionTitle"):
            parts.append(f"Section: {c['sectionTitle']} ")
        if c.get("pageNumber") is not None:
            parts.append(f"(p.{c['pageNumber']}) ")
        parts.append("\n")
        content = c.get("content") or ""
        excerpt = content[:CHUNK_EXCERPT_CHARS] if len(content) > CHUNK_EXCERPT_CHARS else content
        parts.append(excerpt)
        parts.append("\n\n")
    parts.append(f"\n请按格式输出JSON：{{\"ranked\":[{{\"index\":1,\"score\":8}},...]}}，包含全部{len(chunks)}个片段。")
    return "".join(parts)


def _parse_ranked_items(llm_output: str) -> list[tuple[int, float]]:
    """Parse ranked items from LLM JSON output."""
    if not llm_output or not llm_output.strip():
        return []
    j = llm_output.strip()
    if j.startswith("```json"):
        j = j[7:]
    elif j.startswith("```"):
        j = j[3:]
    if j.endswith("```"):
        j = j[:-3]
    j = j.strip()
    items: list[tuple[int, float]] = []
    try:
        obj = json.loads(j)
        ranked = obj.get("ranked")
        if not isinstance(ranked, list):
            return items
        for r in ranked:
            idx = r.get("index")
            score = r.get("score")
            if idx is not None and score is not None:
                clamped = max(0.0, min(10.0, float(score)))
                items.append((int(idx), clamped))
    except Exception:
        pass
    return items


def semantic_rerank(question: str, candidate_chunks: list[dict], llm_cfg: dict) -> list[dict]:
    """LLM semantic reranking (mirrors SemanticReranker.rerank).

    Returns chunks reordered by LLM score (descending). Unmentioned chunks appended at end.
    On failure, returns original order.
    """
    if not candidate_chunks or not llm_cfg:
        return list(candidate_chunks)

    try:
        user_prompt = _build_rerank_prompt(question, candidate_chunks)
        llm_output = chat(
            RERANK_SYSTEM_PROMPT,
            user_prompt,
            {**llm_cfg, "temperature": 0.0, "maxTokens": 4096},
            think=False,
        )
        ranked_items = _parse_ranked_items(llm_output)
        if not ranked_items:
            return list(candidate_chunks)

        # Sort by score desc
        ranked_items.sort(key=lambda x: -x[1])
        result: list[dict] = []
        placed: set[int] = set()
        for idx, score in ranked_items:
            zero_idx = idx - 1
            if 0 <= zero_idx < len(candidate_chunks) and zero_idx not in placed:
                chunk = candidate_chunks[zero_idx]
                chunk["score"] = score / 10.0
                result.append(chunk)
                placed.add(zero_idx)
        # Unmentioned chunks at end with score 0
        for i, c in enumerate(candidate_chunks):
            if i not in placed:
                c["score"] = 0.0
                result.append(c)
        return result
    except Exception:
        return list(candidate_chunks)


# ------------------------------------------------------------------
# 输出chunk格式转换
# ------------------------------------------------------------------

def _normalize_chunk(c: dict) -> dict:
    """Ensure chunk has all expected fields."""
    return {
        "chunkId": c.get("chunkId"),
        "ticker": c.get("ticker"),
        "documentId": c.get("documentId"),
        "formType": c.get("formType"),
        "fiscalYear": c.get("fiscalYear"),
        "fiscalPeriod": c.get("fiscalPeriod"),
        "filingDate": c.get("filingDate"),
        "sourceFileName": c.get("sourceFileName"),
        "sectionTitle": c.get("sectionTitle"),
        "pageNumber": c.get("pageNumber"),
        "content": c.get("content"),
        "score": c.get("score"),
        "metadata": c.get("metadata") or {},
    }


# ------------------------------------------------------------------
# 主检索入口 (mirrors TextSearchFilingRagBackend.search)
# ------------------------------------------------------------------

def search(query: str, ticker: str, top_k: int, form_type: str | None,
           fiscal_period: str | None, from_year: int | None, to_year: int | None,
           keyword: str | None, similarity_threshold: float, recall_multiplier: int,
           ts_cfg: dict, llm_cfg: dict, workspace: Path) -> list[dict]:
    """TextSearch main retrieval flow, aligned with Java TextSearchFilingRagBackend.search.

    Pipeline:
      1. discoverMatchingDocuments + auto-process if needed
      2. load chunks with chunk-level filtering
      3. query rewrite (explicit kw -> LLM kws -> dict expansion -> time terms)
      4. BM25F scoring (recallN = max(rerankTopN, topK*3))
      5. LLM semantic reranking
      6. truncate to topK
    """
    t0 = time.time()
    ticker = (ticker or "").upper()

    rerank_top_n = int(ts_cfg.get("rerankTopN", 15))
    min_kw_score = float(ts_cfg.get("minKeywordScore", 0.01))

    # 1. Discover documents + auto-process, then load chunks
    all_chunks = _discover_and_load_chunks(
        workspace, ticker, form_type, fiscal_period, from_year, to_year)
    if not all_chunks:
        return []

    # 2. Query rewrite
    keywords = rewrite_query(query, keyword, llm_cfg)

    # 3. BM25F keyword scoring
    recall_n = max(rerank_top_n, top_k * 3)
    scored = bm25f_score(all_chunks, keywords, recall_n, min_kw_score)
    top_chunks = [c for c, _ in scored]

    # 4. LLM semantic reranking
    if top_chunks:
        final_chunks = semantic_rerank(query, top_chunks, llm_cfg)
    else:
        final_chunks = []

    # 5. Truncate to topK
    if len(final_chunks) > top_k:
        final_chunks = final_chunks[:top_k]

    return [_normalize_chunk(c) for c in final_chunks]
