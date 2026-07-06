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
        self._infer_currency_and_unit(table, table_el)
        return table

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
