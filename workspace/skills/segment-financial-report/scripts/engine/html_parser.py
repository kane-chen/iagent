# -*- coding: utf-8 -*-
"""HTML 报告解析器（基于 BeautifulSoup）。

保留 mergeSplitParentheses 处理以支持 BABA 2026Q1 EBITA 行中括号拆分后的数值合并。
"""
from __future__ import annotations

import logging
import re
import warnings
from pathlib import Path
from typing import List, Optional

from bs4 import BeautifulSoup, Tag
from bs4 import XMLParsedAsHTMLWarning

warnings.filterwarnings("ignore", category=XMLParsedAsHTMLWarning)

from .model import FinancialTable, TableCell, TableRow

logger = logging.getLogger(__name__)


TITLE_KEYWORDS = [
    "revenue", "income", "segment", "profit", "loss",
    "收入", "利润", "分部", "营业", "经营", "成本", "费用", "EBIT", "EBITDA",
]


class HtmlReportParser:
    """Parses HTML financial reports and yields a list of FinancialTable."""

    def parse(self, file: Path) -> List[FinancialTable]:
        logger.info("Parsing HTML report file: %s", Path(file).name)
        with open(file, "r", encoding="utf-8", errors="replace") as f:
            content = f.read()
        return self.parseHtml(content)

    def parseHtml(self, html_content: str) -> List[FinancialTable]:
        logger.info("Parsing HTML content, length: %d", len(html_content))
        soup = BeautifulSoup(html_content, "lxml")
        return self._extract_tables(soup)

    def supports(self, fmt: str) -> bool:
        return fmt is not None and fmt.lower() in ("html", "htm")

    # ------------------------------------------------------------------
    def _extract_tables(self, soup: BeautifulSoup) -> List[FinancialTable]:
        tables: List[FinancialTable] = []
        table_elements = soup.find_all("table")
        logger.info("Found %d tables in document", len(table_elements))
        for idx, te in enumerate(table_elements):
            try:
                t = self._parse_table(te, idx)
                if self._is_valid_financial_table(t):
                    tables.append(t)
            except Exception as e:  # noqa: BLE001
                logger.warning("Failed to parse table %d: %s", idx, e)
        logger.info("Successfully extracted %d financial tables", len(tables))
        return tables

    def _parse_table(self, table_el: Tag, idx: int) -> FinancialTable:
        table = FinancialTable()
        table.tableId = f"table_{idx}"
        table.title = self._find_table_title(table_el)
        table.headers = self._parse_headers(table_el)
        table.rows = self._parse_rows(table_el)
        table.period = self._infer_table_period(table_el)
        self._infer_currency_and_unit(table, table_el)
        return table

    # ---- table-level period hint from surrounding text ---------------
    _PERIOD_PHRASE_RE = re.compile(
        r"(?:three|six|nine|twelve)\s+months?\s+ended\s+([a-z]+)\s+(\d{1,2})\s*,?\s*(\d{4})",
        re.IGNORECASE,
    )
    _QUARTER_ENDED_RE = re.compile(
        r"quarter\s+ended\s+([a-z]+)\s+(\d{1,2})\s*,?\s*(\d{4})",
        re.IGNORECASE,
    )
    _YEAR_ENDED_RE = re.compile(
        r"(?:year|fiscal\s+year)\s+ended\s+([a-z]+)\s+(\d{1,2})\s*,?\s*(\d{4})",
        re.IGNORECASE,
    )

    _MONTH_NAMES = ("january","february","march","april","may","june",
                    "july","august","september","october","november","december")

    def _infer_table_period(self, table_el: Tag) -> Optional[str]:
        """Look in immediately preceding content for period-ending phrases like
        "Three Months Ended June 30, 2025" or "fiscal year ended March 31, 2026".

        Strategy for combined press releases (which contain both quarterly and FY
        tables): search preceding elements (walking siblings upward, including text
        inside container elements like <table> highlights blocks), and stop when we
        find a period phrase, prioritizing the one CLOSEST to the table.  Also
        detect section-boundary headings to avoid crossing into a previous
        section's data.

        Downstream code can use this as a fallback when the table itself has no
        period header (common in press-release / 6-K tables).
        """
        import re as _re

        def _find_phrase_in_text(text: str):
            """Return (year, end_pos_in_text, match_text) for the last period phrase
            in *text*, or None."""
            if not text:
                return None
            low = text.lower().replace("\xa0", " ")
            # Normalize newlines inside the phrase (e.g. "March 31,\n2026")
            low_norm = re.sub(r"[,\s]+", " ", low)
            best = None
            for pat in (self._PERIOD_PHRASE_RE, self._YEAR_ENDED_RE, self._QUARTER_ENDED_RE):
                # Use a version that allows embedded commas/newlines between tokens
                for m in pat.finditer(low_norm):
                    try:
                        y = int(m.group(3))
                        if best is None or y > best[0] or (y == best[0] and m.end() > best[1]):
                            best = (y, m.end(), m.group(0))
                    except (IndexError, ValueError):
                        continue
            return best

        def _is_section_heading(el: Tag) -> bool:
            """Return True if el looks like a section heading that separates periods."""
            tag_name = getattr(el, "name", "") or ""
            if tag_name in ("h1", "h2", "h3", "h4", "h5", "h6"):
                return True
            # Bold short text announcing results
            txt = self._element_text(el).strip()
            if 0 < len(txt) < 200:
                low = txt.lower()
                if "results" in low and re.search(r"\b(fiscal\s+year|quarter|three\s+months?|six\s+months?|nine\s+months?|twelve\s+months?|year\s+ended)\b", low):
                    return True
            return False

        def _is_divider(el: Tag) -> bool:
            """Return True if el is a page/section divider (border-top rule)."""
            tag_name = getattr(el, "name", "") or ""
            style = (el.get("style", "") or "").lower()
            cls = " ".join(el.get("class", []) or []).lower()
            if "border-top" in style or "rule-page" in cls or "field: rule-page" in str(el):
                return True
            return False

        # Walk backwards through previous siblings, collecting text and stopping at
        # first period phrase found or section boundary.
        prev = self._previous_element_sibling(table_el)
        visited = 0
        best_match = None  # (year, end_pos_in_combined, match_text)
        accumulated_len = 0
        while prev is not None and visited < 30 and accumulated_len < 4000:
            visited += 1
            tag_name = getattr(prev, "name", "") or ""
            # Page/section dividers used in EDGAR press releases:
            #   <!-- Field: Rule-Page --> or <div style="...border-top...">
            # These reliably separate the quarterly and FY sections in combined
            # BABA press releases.
            is_divider = _is_divider(prev)
            is_heading = is_divider or _is_section_heading(prev)
            txt = self._element_text(prev).strip()
            if is_heading:
                break
            if txt:
                # Check if this element contains a period phrase
                hit = _find_phrase_in_text(txt)
                if hit is not None:
                    # Found a phrase in this sibling; since we walk backwards (closest
                    # first), the first hit is the nearest to the table → keep it
                    # only if it's from a later/equal year than any prior.
                    if best_match is None or hit[0] >= best_match[0]:
                        best_match = hit
                    # Stop after finding a phrase in a paragraph-level element (not
                    # in a highlights <table> which may list multiple periods).
                    if tag_name in ("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "span"):
                        break
                    # For <table> highlights blocks: the phrase with the latest year
                    # is usually the period this section is about, but keep walking
                    # one more sibling to see if there's a closer explicit heading
                    # like "In the fiscal year ended ...:" paragraph.
                accumulated_len += len(txt)
            prev = self._previous_element_sibling(prev)

        # Check next sibling for immediately-following caption/period note
        nxt = self._next_element_sibling(table_el)
        if nxt is not None:
            ntxt = self._element_text(nxt).strip()
            if ntxt and len(ntxt) < 500:
                hit = _find_phrase_in_text(ntxt)
                if hit is not None:
                    return hit[2]

        if best_match is not None:
            return best_match[2]
        return None

    # ---- title -------------------------------------------------------
    def _find_table_title(self, table_el: Tag) -> str:
        """在 table 前驱元素中查找标题。"""
        prev = self._previous_element_sibling(table_el)
        while prev is not None:
            text = self._element_text(prev).strip()
            if text and len(text) < 200 and self._is_likely_title(text):
                return text
            prev = self._previous_element_sibling(prev)
        cap = table_el.find("caption")
        if cap is not None:
            return self._element_text(cap).strip()
        return "Untitled Table"

    def _is_likely_title(self, text: str) -> bool:
        lower = text.lower()
        for kw in TITLE_KEYWORDS:
            if kw.lower() in lower:
                return True
        return False

    # ---- headers -----------------------------------------------------
    def _parse_headers(self, table_el: Tag) -> List[str]:
        headers: List[str] = []
        thead = table_el.find("thead")
        if thead is not None:
            for th in thead.find_all("th"):
                headers.append(self._clean_text(self._element_text(th)))
        if not headers:
            first_row = table_el.find("tr")
            if first_row is not None:
                for cell in first_row.find_all(["th", "td"]):
                    headers.append(self._clean_text(self._element_text(cell)))
        return headers

    # ---- rows --------------------------------------------------------
    def _parse_rows(self, table_el: Tag) -> List[TableRow]:
        rows: List[TableRow] = []
        trs = table_el.find_all("tr")
        start = 0
        thead = table_el.find("thead")
        if thead is not None:
            start = len(thead.find_all("tr"))
        elif trs:
            start = 1
        for i in range(start, len(trs)):
            r = self._parse_row(trs[i])
            if r is not None:
                rows.append(r)
        return rows

    def _parse_row(self, tr: Tag) -> Optional[TableRow]:
        cells = tr.find_all(["td", "th"])
        if not cells:
            return None
        row = TableRow()
        first = cells[0]
        label = self._clean_text(self._element_text(first))
        row.label = label
        row.indentLevel = self._calculate_indent_level(first)
        if self._is_total_row(label):
            row.isTotalRow = True
        elif self._is_subtotal_row(label):
            row.isSubtotalRow = True
        row.isBold = self._detect_bold_style(first)

        # collect raw cell texts first, then merge split parentheses
        cell_texts: List[str] = []
        for c in cells[1:]:
            cell_texts.append(self._clean_text(self._element_text(c)))
        self._merge_split_parentheses(cell_texts)
        for txt in cell_texts:
            row.addCell(TableCell(txt))
        return row

    def _merge_split_parentheses(self, cell_texts: List[str]) -> None:
        """合并被拆分到相邻单元格的括号内容。"""
        n = len(cell_texts)
        for i in range(n):
            t = cell_texts[i]
            if not t:
                continue
            if not t.startswith("(") or t.endswith(")"):
                continue
            for j in range(i + 1, n):
                nxt = cell_texts[j]
                if not nxt:
                    continue
                if nxt.startswith(")"):
                    cell_texts[i] = t + nxt
                    cell_texts[j] = ""
                break

    # ---- indent ------------------------------------------------------
    def _calculate_indent_level(self, cell_el: Tag) -> int:
        level = 0
        text = self._element_text(cell_el)
        for ch in text:
            if ch == " " or ch == " ":
                level += 1
            else:
                break
        if cell_el.find(["ul", "ol"]):
            level += 4
        return min(level // 4, 5)

    # ---- bold detection ---------------------------------------------
    _FONT_WEIGHT_RE = re.compile(r"font-weight\s*:\s*(\d{3})")

    def _detect_bold_style(self, cell_el: Tag) -> bool:
        """检测单元格是否加粗（b/strong/font-weight/style）。"""
        if cell_el.find(["b", "strong"]):
            return True
        if self._style_has_bold(cell_el.get("style") or ""):
            return True
        for d in cell_el.find_all(True):
            if self._style_has_bold(d.get("style") or ""):
                return True
        cls = cell_el.get("class")
        if cls:
            cls_str = " ".join(cls).lower() if isinstance(cls, list) else str(cls).lower()
            if "bold" in cls_str or "header" in cls_str:
                return True
        return False

    def _style_has_bold(self, style: str) -> bool:
        if not style:
            return False
        s = style.lower()
        if "font-weight" not in s:
            return False
        if "bold" in s:
            return True
        m = self._FONT_WEIGHT_RE.search(s)
        if m:
            try:
                return int(m.group(1)) >= 600
            except ValueError:
                pass
        return False

    # ---- total / subtotal -------------------------------------------
    def _is_total_row(self, label: Optional[str]) -> bool:
        if not label:
            return False
        l = label.lower().strip()
        return any(x in l for x in ("total", "consolidated", "合计", "总计", "合并"))

    def _is_subtotal_row(self, label: Optional[str]) -> bool:
        if not label:
            return False
        l = label.lower().strip()
        return "subtotal" in l or "小计" in l

    # ---- clean text --------------------------------------------------
    _WS_RE = re.compile(r"[\r\n]+")

    def _clean_text(self, text: Optional[str]) -> str:
        if text is None:
            return ""
        return self._WS_RE.sub(" ", text).strip()

    _JSOUP_WS_RE = re.compile(r"[ \t\r\n\f]+")

    def _element_text(self, el: Tag) -> str:
        # Emulate Jsoup Element.text() — collapses consecutive whitespace (space, tab,
        # newline, form-feed) into a single space. NB: keeps non-breaking space ( )
        # so the indent-detection logic sees it.
        if el is None:
            return ""
        raw = el.get_text(separator=" ", strip=False)
        # Collapse only ASCII whitespace; nbsp stays intact for indent detection.
        return self._JSOUP_WS_RE.sub(" ", raw).strip()

    # ---- validation --------------------------------------------------
    def _is_valid_financial_table(self, t: FinancialTable) -> bool:
        if len(t.rows) < 2 or len(t.headers) < 2:
            return False
        numeric_count = 0
        for r in t.rows:
            for c in r.cells:
                if c.isNumeric():
                    numeric_count += 1
        return numeric_count >= 5

    # ---- currency/unit inference ------------------------------------
    def _infer_currency_and_unit(self, table: FinancialTable, table_el: Tag) -> None:
        title = table.title.lower() if table.title else ""
        all_headers = " ".join(table.headers).lower()
        combined = title + " " + all_headers
        surrounding = self._extract_surrounding_text(table_el)
        if surrounding:
            combined += " " + surrounding.lower()

        if "rmb" in combined or "人民币" in combined or "元" in combined:
            table.currency = "RMB"
        elif "us$" in combined or "us dollar" in combined or "美元" in combined:
            table.currency = "USD"
        elif "$" in combined:
            table.currency = "USD"

        if "million" in combined or "百万" in combined:
            table.unit = "million"
        elif "billion" in combined or "十亿" in combined:
            table.unit = "billion"
        elif "thousand" in combined or "千" in combined:
            table.unit = "thousand"

    def _extract_surrounding_text(self, table_el: Tag) -> str:
        """提取 table 前后的文本，用于上下文识别。"""
        prev = self._previous_element_sibling(table_el)
        if prev is not None:
            prev_text = self._element_text(prev).strip()
            if len(prev_text) < 300 and self._is_unit_description(prev_text):
                return prev_text
        # up to 5 previous siblings
        cur = self._previous_element_sibling(table_el)
        count = 0
        while cur is not None and count < 5:
            t = self._element_text(cur).strip()
            if len(t) < 300 and self._is_unit_description(t):
                return t
            cur = self._previous_element_sibling(cur)
            count += 1
        nxt = self._next_element_sibling(table_el)
        if nxt is not None:
            t = self._element_text(nxt).strip()
            if len(t) < 300 and self._is_unit_description(t):
                return t
        # parent scan (up to 10 elements before table)
        parent = table_el.parent
        if parent is not None:
            all_els = list(parent.find_all(True))
            try:
                idx = all_els.index(table_el)
            except ValueError:
                idx = -1
            if idx > 0:
                for i in range(idx - 1, max(-1, idx - 11), -1):
                    el = all_els[i]
                    if el.name in ("p", "div"):
                        t = self._element_text(el).strip()
                        if len(t) < 300 and self._is_unit_description(t):
                            return t
        return ""

    def _is_unit_description(self, text: Optional[str]) -> bool:
        if not text:
            return False
        l = text.lower()
        return any(x in l for x in (
            "amounts in", "in thousands", "in millions", "in billions",
            "(amounts", "单位:", "金额单位",
        ))

    # ---- sibling helpers --------------------------------------------
    @staticmethod
    def _previous_element_sibling(el: Tag) -> Optional[Tag]:
        s = el
        while True:
            s = s.previous_sibling
            if s is None:
                return None
            if isinstance(s, Tag):
                return s

    @staticmethod
    def _next_element_sibling(el: Tag) -> Optional[Tag]:
        s = el
        while True:
            s = s.next_sibling
            if s is None:
                return None
            if isinstance(s, Tag):
                return s
