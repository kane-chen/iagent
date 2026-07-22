# -*- coding: utf-8 -*-
"""
PDF/HTML 财报表格提取器——从本地 PFD 或 HTML 文件中提取结构化表格。
"""

import io
from pathlib import Path
from typing import Optional

import pandas as pd
import pdfplumber


def extract_pdf_tables(pdf_path: str, table_type: str = "all") -> list[dict]:
    """
    从 PDF 财务报告中提取结构化表格。
    返回 [{ headers: [...], rows: [[...], ...], page: N, detected_type: str }, ...]
    """
    tables = []
    try:
        with pdfplumber.open(pdf_path) as pdf:
            for i, page in enumerate(pdf.pages):
                extracted = page.extract_tables()
                for table in extracted:
                    entry = _build_table_entry(table, i + 1, table_type)
                    if entry is not None:
                        tables.append(entry)
    except Exception:
        return []
    return tables


def extract_filing_tables(file_path: Path, table_type: str = "all") -> list[dict]:
    """
    从本地财报文件（PDF 或 HTML）中提取结构化表格。
    返回 [{ headers, rows, page, detected_type }, ...]
    """
    suffix = file_path.suffix.lower()
    if suffix == ".pdf":
        return extract_pdf_tables(str(file_path), table_type)
    elif suffix in (".html", ".htm"):
        return _extract_html_tables(file_path, table_type)
    return []


def _extract_html_tables(html_path: Path, table_type: str = "all") -> list[dict]:
    """使用 pandas.read_html 从 HTML 提取表格，返回与 PDF 一致的形状。"""
    try:
        dfs = pd.read_html(str(html_path), encoding="utf-8")
    except Exception:
        return []

    tables = []
    for df in dfs:
        if df.empty or df.shape[1] < 2:
            continue

        # 扁平化 MultiIndex
        if isinstance(df.columns, pd.MultiIndex):
            df.columns = [" ".join(str(c) for c in col if c) for col in df.columns]
        else:
            df.columns = [str(c) if c is not None else "" for c in df.columns]

        headers = df.columns.tolist()
        rows = df.values.tolist()
        # 清理行中的 NaN
        rows = [[_clean(val) for val in row] for row in rows]

        entry = _build_table_entry_from_data(headers, rows, 1, table_type)
        if entry is not None:
            tables.append(entry)

    return tables


def _build_table_entry(
    raw_table: list, page_num: int, table_type: str
) -> Optional[dict]:
    """从 pdfplumber 原始表格构建标准条目。"""
    if not raw_table or len(raw_table) < 2:
        return None
    headers = [str(h) if h is not None else "" for h in raw_table[0]]
    rows = raw_table[1:]
    return _build_table_entry_from_data(headers, rows, page_num, table_type)


def _build_table_entry_from_data(
    headers: list[str], rows: list, page_num: int, table_type: str
) -> Optional[dict]:
    """从表头与行数据构建标准条目。"""
    if not headers or not rows:
        return None

    # 过滤空行
    rows = [r for r in rows if any(_clean(c) is not None for c in r)]

    # 行清理
    cleaned_rows = []
    for row in rows:
        cleaned = []
        for cell in row:
            if isinstance(cell, (int, float)):
                cleaned.append(cell)
            else:
                cleaned.append(str(cell) if cell is not None else "")
        cleaned_rows.append(cleaned)

    if not cleaned_rows:
        return None

    detected = _detect_table_type(headers, cleaned_rows)
    if table_type != "all" and not _matches_table_type(detected, table_type):
        return None

    return {
        "headers": headers,
        "rows": cleaned_rows,
        "page": page_num,
        "detected_type": detected,
    }


def _detect_table_type(headers: list[str], rows: list[list]) -> str:
    """启发式识别表格类型——同时检查表头和前几行数据。"""
    sample_text = " ".join(str(h) for h in headers if h).lower()
    # 补充前几行文本
    for row in rows[:5]:
        for cell in row:
            if isinstance(cell, str):
                sample_text += " " + cell.lower()

    if any(kw in sample_text for kw in ["revenue", "营收", "income", "利润", "net income", "营业总收入", "operating income", "毛利", "营业利润", "净利润"]):
        return "income_statement"
    if any(kw in sample_text for kw in ["assets", "资产", "liabilities", "负债", "equity", "权益", "balance sheet", "资产负债表", "流动资产"]):
        return "balance_sheet"
    if any(kw in sample_text for kw in ["cash flow", "现金流", "operating", "投资", "筹资", "经营活动", "现金流量"]):
        return "cash_flow"
    return "other"


def _matches_table_type(detected: str, requested: str) -> bool:
    mapping = {
        "income": "income_statement",
        "balance": "balance_sheet",
        "cashflow": "cash_flow",
        "segments": "other",
    }
    target = mapping.get(requested, requested)
    return detected == target


def _clean(v) -> Optional[str]:
    """清理单元格值：NaN→None，空字符串→None，其他→str。"""
    if v is None or (isinstance(v, float) and (v != v)):  # NaN check
        return None
    s = str(v).strip()
    return s if s else None