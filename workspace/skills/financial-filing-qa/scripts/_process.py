"""Filing processing: HTML/PDF text extraction + overlap-window chunking.

Ported from Java:
- HtmlTextExtractor (Jsoup → BeautifulSoup4)
- PdfTextExtractor (PDFBox → pdfplumber)
- OverlapWindowChunker (sliding window with overlap)

Entry point: process_document(workspace, ticker, documentId) → int (number of chunks).
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

# ------------------------------------------------------------------
# Constants (mirror Java FilingRagConfig defaults)
# ------------------------------------------------------------------

TARGET_TOKENS = 400
MAX_TOKENS = 600
OVERLAP_TOKENS = 80

MAX_TABLE_COLS = 15
MAX_TABLE_CHARS = 4000
MAX_HEADING_LENGTH = 200
MAX_BOLD_HEADING_LENGTH = 120
MAX_ALLCAPS_HEADING_LENGTH = 60

# ------------------------------------------------------------------
# Section data model (mirrors Java RawSection)
# ------------------------------------------------------------------

@dataclass
class RawSection:
    title: str | None = None
    content: str = ""
    page_number: int | None = None


# ------------------------------------------------------------------
# HTML extraction (BeautifulSoup4, mirrors HtmlTextExtractor)
# ------------------------------------------------------------------

_HEADER_TAG_RE = re.compile(r"^h[1-4]$", re.IGNORECASE)
_BOLD_TITLE_RE = re.compile(
    r"^(PART\s+[IVX]+|Item\s+\d+[A-Za-z]?\.|"
    r"[一二三四五六七八九十]+[、．.]|"
    r"（[一二三四五六七八九十]+）|"
    r"第[一二三四五六七八九十]+[节章部分])",
    re.IGNORECASE,
)
_PARAGRAPH_CONTAINERS = {"p", "div", "li", "tr", "ul", "ol", "blockquote", "pre"}
_BOILERPLATE_TAGS = ["script", "style", "nav", "header", "footer", "noscript"]


def _is_toc_block(el) -> bool:
    """TOC block heuristic: >=5 links and link text >80% of total text."""
    links = el.find_all("a", href=True)
    if len(links) < 5:
        return False
    text = el.get_text()
    if not text or not text.strip():
        return False
    link_text_len = sum(len(a.get_text()) for a in links)
    total_len = len(text)
    if total_len == 0:
        return False
    return (link_text_len / total_len) > 0.8


def _render_table(table) -> str | None:
    """Render table as pipe-delimited rows; skip navigation/TOC tables."""
    rows = table.find_all("tr")
    if not rows:
        return None
    grid: list[list[str]] = []
    max_cols = 0
    total_chars = 0
    for tr in rows:
        cells: list[str] = []
        for c in tr.find_all(["td", "th"]):
            ct = re.sub(r"\s+", " ", c.get_text()).strip()
            cells.append(ct)
            total_chars += len(ct)
        if cells:
            grid.append(cells)
            max_cols = max(max_cols, len(cells))
    if not grid:
        return None
    if max_cols > MAX_TABLE_COLS or total_chars > MAX_TABLE_CHARS:
        return None
    return "\n".join(" | ".join(row) for row in grid)


def _is_section_heading(el, text: str, tag: str) -> bool:
    """Detect if an element is a section heading."""
    if not text or len(text) > MAX_HEADING_LENGTH:
        return False
    if _HEADER_TAG_RE.match(tag):
        return True
    # Bold/enlarged short line
    is_bold = el.find(["b", "strong"]) is not None or tag in ("b", "strong")
    is_enlarged = el.find("font", attrs={"size": re.compile(r"\+")}) is not None
    if (is_bold or is_enlarged) and len(text) <= MAX_BOLD_HEADING_LENGTH:
        if _BOLD_TITLE_RE.search(text.strip()):
            return True
        # All-caps short line
        if (len(text) <= MAX_ALLCAPS_HEADING_LENGTH
                and text == text.upper()
                and re.search(r"[A-Z]{3,}", text)):
            return True
    return False


def _normalize_whitespace(s: str) -> str:
    s = re.sub(r"[ \t]+", " ", s)
    s = re.sub(r"\n{3,}", "\n\n", s)
    return s.strip()


def _extract_html(file_path: Path) -> list[RawSection]:
    """Extract sections from an HTML filing."""
    from bs4 import BeautifulSoup, NavigableString, Tag

    html = file_path.read_text(encoding="utf-8", errors="replace")
    soup = BeautifulSoup(html, "html.parser")

    # Strip boilerplate tags
    for tag in _BOILERPLATE_TAGS:
        for el in soup.find_all(tag):
            el.decompose()

    # Strip TOC-like blocks
    for el in list(soup.find_all(["div", "p", "td", "tr"])):
        if _is_toc_block(el):
            el.decompose()

    body = soup.body or soup
    sections: list[RawSection] = []
    current_parts: list[str] = []
    current_title: str | None = None

    def _flush():
        nonlocal current_parts, current_title
        content = _normalize_whitespace("".join(current_parts))
        if content or current_title:
            sections.append(RawSection(
                title=current_title.strip() if current_title else None,
                content=content,
                page_number=None,
            ))
        current_parts = []

    # Walk direct children of body in DOM order
    for node in body.children:
        if isinstance(node, NavigableString):
            t = str(node).strip()
            if t:
                current_parts.append(t + " ")
            continue
        if not isinstance(node, Tag):
            continue

        tag = node.name.lower()

        # Table handling
        if tag == "table":
            table_text = _render_table(node)
            if table_text:
                current_parts.append(table_text + "\n\n")
            continue

        text = node.get_text()
        if not text or not text.strip():
            continue
        trimmed = text.strip()

        # Section heading
        if _is_section_heading(node, trimmed, tag):
            _flush()
            current_title = trimmed
            continue

        if tag in _PARAGRAPH_CONTAINERS:
            current_parts.append(trimmed + "\n\n")
        else:
            current_parts.append(trimmed + " ")

    _flush()
    # Remove empty sections
    sections = [s for s in sections if s.content or s.title]
    return sections


# ------------------------------------------------------------------
# PDF extraction (pdfplumber, mirrors PdfTextExtractor)
# ------------------------------------------------------------------

_PDF_SECTION_TITLE_RE = re.compile(
    r"^\s*(PART\s+[IVX0-9]+|Item\s+\d+[A-Za-z]?\.?"
    r"|[一二三四五六七八九十百零〇]+[、．.节章部分]"
    r"|（[一二三四五六七八九十百零〇]+）"
    r"|\([一二三四五六七八九十]+\)"
    r"|第[一二三四五六七八九十百零〇0-9]+[节章部分条]"
    r"|[A-Z][A-Z /&-]{4,})",
    re.IGNORECASE,
)


def _extract_pdf(file_path: Path) -> list[RawSection]:
    """Extract sections from a PDF filing using pdfplumber."""
    import pdfplumber

    sections: list[RawSection] = []
    current_title: str | None = None
    current_parts: list[str] = []
    current_start_page: int | None = None

    def _flush():
        nonlocal current_parts, current_title, current_start_page
        content = _normalize_whitespace("\n".join(current_parts))
        if content or current_title:
            sections.append(RawSection(
                title=current_title.strip() if current_title else None,
                content=content,
                page_number=current_start_page,
            ))
        current_parts = []

    with pdfplumber.open(str(file_path)) as pdf:
        for page_idx, page in enumerate(pdf.pages, start=1):
            page_text = page.extract_text() or ""
            if not page_text.strip():
                continue
            lines = page_text.split("\n")
            for raw_line in lines:
                line = raw_line.strip()
                if not line:
                    current_parts.append("")
                    continue
                # Detect heading candidate: short line matching section pattern
                if len(line) <= MAX_BOLD_HEADING_LENGTH and _PDF_SECTION_TITLE_RE.search(line):
                    _flush()
                    current_title = line
                    current_parts = []
                    current_start_page = page_idx
                else:
                    current_parts.append(line)

    _flush()
    sections = [s for s in sections if s.content or s.title]
    return sections


# ------------------------------------------------------------------
# Token estimation (mirrors OverlapWindowChunker.estimateTokens)
# ------------------------------------------------------------------

def _is_cjk(ch: str) -> bool:
    if len(ch) != 1:
        return False
    cp = ord(ch)
    # Match Java's UnicodeBlock checks
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


def estimate_tokens(text: str) -> int:
    """Estimate token count: ~1.5 CJK chars/token, ~4 ASCII chars/token."""
    if not text:
        return 0
    cjk = 0
    ascii_nonws = 0
    other = 0
    for ch in text:
        if _is_cjk(ch):
            cjk += 1
        elif ord(ch) < 128:
            if not ch.isspace():
                ascii_nonws += 1
        else:
            other += 1
    import math
    return math.ceil(cjk / 1.5) + (ascii_nonws // 4) + other


# ------------------------------------------------------------------
# Chunking (mirrors OverlapWindowChunker)
# ------------------------------------------------------------------

_SENTENCE_SPLIT_RE = re.compile(r"(?<=[。！？!?\.])\s+")
_CLAUSE_SPLIT_RE = re.compile(r"(?<=[，,；;、:：])")


def _split_sentences(text: str) -> list[str]:
    """Split long text on sentence boundaries, then clause boundaries if still too long."""
    out: list[str] = []
    parts = _SENTENCE_SPLIT_RE.split(text)
    for p in parts:
        if estimate_tokens(p) > MAX_TOKENS:
            sub = _CLAUSE_SPLIT_RE.split(p)
            acc = ""
            acc_t = 0
            for s in sub:
                st = estimate_tokens(s)
                if acc_t + st > MAX_TOKENS and acc:
                    out.append(acc.strip())
                    acc = s
                    acc_t = st
                else:
                    acc += s
                    acc_t += st
            if acc.strip():
                out.append(acc.strip())
        else:
            out.append(p)
    return out


def _split_to_units(body: str) -> list[str]:
    """Split body into units (sentences/clauses), each guaranteed <= maxTokens."""
    units: list[str] = []
    paragraphs = re.split(r"\n{2,}", body)
    for para in paragraphs:
        p = para.strip()
        if not p:
            continue
        if estimate_tokens(p) <= MAX_TOKENS:
            units.append(p)
        else:
            for s in _split_sentences(p):
                if s.strip():
                    units.append(s.strip())
    return units


def _hard_split(text: str) -> list[str]:
    """Split an oversized unit into roughly equal character-based pieces."""
    est = estimate_tokens(text)
    pieces = max(1, (est // MAX_TOKENS) + 1)
    chunk_size = max(1, len(text) // pieces)
    out: list[str] = []
    for i in range(0, len(text), chunk_size):
        end = min(i + chunk_size, len(text))
        piece = text[i:end].strip()
        if piece:
            out.append(piece)
    return out


def _build_windows(units: list[str]) -> list[str]:
    """Sliding window with overlap."""
    windows: list[str] = []
    n = len(units)
    start = 0
    while start < n:
        window: list[str] = []
        tokens = 0
        end = start
        oversized = False
        while end < n:
            u = units[end]
            ut = estimate_tokens(u)
            if ut > MAX_TOKENS:
                pieces = _hard_split(u)
                units.pop(end)
                for p in reversed(pieces):
                    units.insert(end, p)
                n = len(units)
                oversized = True
                break
            if tokens + ut > MAX_TOKENS and window:
                break
            window.append(u)
            tokens += ut
            end += 1
            if tokens >= TARGET_TOKENS:
                break
        if oversized:
            continue
        if window:
            windows.append(" ".join(window).strip())
        # Determine next start: walk back from (end-1)
        next_start = end
        back_tokens = 0
        for j in range(end - 1, start, -1):
            jt = estimate_tokens(units[j])
            if back_tokens + jt > OVERLAP_TOKENS:
                break
            back_tokens += jt
            next_start = j
        if next_start <= start:
            start = max(end, start + 1)
        else:
            start = next_start
    # Merge tiny tail window
    if len(windows) >= 2:
        last = windows[-1]
        if estimate_tokens(last) < max(50, TARGET_TOKENS // 3):
            windows[-2] = windows[-2] + " " + last
            windows.pop()
    return windows


def _deterministic_chunk_id(document_id: str, section_key: str, idx: int) -> str:
    """SHA-256 of documentId::sectionKey::idx, first 16 hex chars."""
    key = f"{document_id}::{section_key}::{idx}"
    h = hashlib.sha256(key.encode("utf-8")).digest()
    return "".join(f"{b:02x}" for b in h[:8])


def _chunk_sections(meta: dict, sections: list[RawSection]) -> list[dict]:
    """Chunk sections into FilingChunk dicts, mirroring OverlapWindowChunker.chunk()."""
    result: list[dict] = []
    if not sections:
        return result
    section_idx = 0
    for section in sections:
        sec_title = section.title
        sec_content = section.content
        if not sec_content and not sec_title:
            section_idx += 1
            continue
        body = sec_content or ""
        units = _split_to_units(body)
        windows = _build_windows(units)
        idx = 0
        for window in windows:
            chunk_content = (f"Section: {sec_title}\n" + window) if sec_title else window
            section_key = (sec_title or "") + f"#{section_idx}"
            chunk_id = _deterministic_chunk_id(meta["documentId"], section_key, idx)
            chunk = {
                "chunkId": chunk_id,
                "ticker": meta["ticker"],
                "documentId": meta["documentId"],
                "formType": meta.get("formType"),
                "fiscalPeriod": meta.get("fiscalPeriod"),
                "filingDate": meta.get("filingDate"),
                "sourceFileName": meta.get("_sourceFileName", ""),
                "sectionTitle": sec_title,
                "content": chunk_content,
                "fiscalYear": meta.get("fiscalYear"),
                "pageNumber": section.page_number,
                "score": None,
                "metadata": {
                    "sectionIndex": section_idx,
                    "chunkIndexInSection": idx,
                },
            }
            result.append(chunk)
            idx += 1
        section_idx += 1
    return result


# ------------------------------------------------------------------
# Process orchestration (mirrors DefaultFilingRagService.buildOneDocument)
# ------------------------------------------------------------------

_FISCAL_PERIOD_SUFFIXES = [
    ("_FY", "FY"),
    ("Q1", "Q1"), ("Q2", "Q2"), ("Q3", "Q3"), ("Q4", "Q4"),
    ("_H1", "H1"), ("_H2", "H2"),
]


def _derive_fiscal_period(document_id: str) -> str | None:
    upper = document_id.upper()
    for suffix, period in _FISCAL_PERIOD_SUFFIXES:
        if suffix in upper:
            return period
    return None


def _load_doc_meta(doc_dir: Path, ticker: str, document_id: str) -> dict:
    """Load document metadata from meta.json, falling back to documentId parsing."""
    meta: dict[str, Any] = {
        "ticker": ticker.upper(),
        "documentId": document_id,
    }
    meta_file = doc_dir / "meta.json"
    if meta_file.is_file():
        try:
            data = json.loads(meta_file.read_text(encoding="utf-8"))
            meta["formType"] = data.get("formType")
            meta["fiscalYear"] = data.get("fiscalYear")
            meta["fiscalPeriod"] = data.get("fiscalPeriod")
            meta["filingDate"] = data.get("filingDate") or data.get("reportDate")
            if not meta.get("fiscalPeriod"):
                meta["fiscalPeriod"] = _derive_fiscal_period(document_id)
        except Exception:
            pass
    if not meta.get("fiscalPeriod"):
        meta["fiscalPeriod"] = _derive_fiscal_period(document_id)
    return meta


def _list_document_files(doc_dir: Path) -> list[Path]:
    """List source files (PDF/HTML/HTM) in document directory, excluding meta.json."""
    files: list[Path] = []
    for p in sorted(doc_dir.iterdir()):
        if not p.is_file():
            continue
        name = p.name.lower()
        if name == "meta.json":
            continue
        if name.endswith(".pdf") or name.endswith(".html") or name.endswith(".htm"):
            files.append(p)
    return files


def process_document(workspace: Path, ticker: str, document_id: str) -> int:
    """Process a single filing: extract text, chunk, and write chunks.json.

    Returns the number of chunks written.
    """
    ticker = ticker.upper()
    doc_dir = workspace / "portfolio" / ticker / "filings" / document_id
    if not doc_dir.is_dir():
        raise FileNotFoundError(f"Document directory does not exist: {doc_dir}")

    meta = _load_doc_meta(doc_dir, ticker, document_id)
    files = _list_document_files(doc_dir)
    if not files:
        raise FileNotFoundError(f"No PDF/HTML files found in {doc_dir}")

    all_chunks: list[dict] = []
    for file_path in files:
        name = file_path.name.lower()
        sections: list[RawSection] = []
        if name.endswith(".pdf"):
            sections = _extract_pdf(file_path)
        elif name.endswith(".html") or name.endswith(".htm"):
            sections = _extract_html(file_path)
        else:
            continue

        file_meta = dict(meta)
        file_meta["_sourceFileName"] = file_path.name
        chunks = _chunk_sections(file_meta, sections)
        all_chunks.extend(chunks)

    if not all_chunks:
        return 0

    # Write chunks.json atomically
    processed_dir = workspace / "portfolio" / ticker / "processed" / document_id
    processed_dir.mkdir(parents=True, exist_ok=True)
    chunks_file = processed_dir / "chunks.json"
    tmp_fd, tmp_path = tempfile.mkstemp(
        dir=str(processed_dir), prefix=".chunks_", suffix=".tmp"
    )
    try:
        with os.fdopen(tmp_fd, "w", encoding="utf-8") as f:
            json.dump(all_chunks, f, ensure_ascii=False, indent=2)
        os.replace(tmp_path, str(chunks_file))
    except Exception:
        # Clean up temp file on failure
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
        raise

    return len(all_chunks)


def ensure_processed(workspace: Path, ticker: str, document_id: str) -> bool:
    """Ensure chunks.json exists for a document; process if missing.

    Returns True if processing was needed (auto-processed), False if already existed.
    """
    ticker = ticker.upper()
    chunks_file = workspace / "portfolio" / ticker / "processed" / document_id / "chunks.json"
    if chunks_file.is_file():
        return False
    process_document(workspace, ticker, document_id)
    return True
