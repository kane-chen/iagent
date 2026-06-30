#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
港股财报 PDF 分部数据提取（基于 pdfplumber）

针对中文字体编码问题（港股PDF常见），不依赖中文文本识别，
而是基于**表格列结构**和**数值位置**来定位分部数据。

策略：
1. 用 pdfplumber 提取每页所有表格（带行列结构）
2. 找候选分部表：5-6列、3-5行数值、且各列数值规模合理（百万级）
3. 输出每个候选表，让 Java 端基于配置（columnMapping）映射列到分部

用法：
    python extract_tencent_pdf.py <pdf_path> --output <json_path>

输出格式：
    {
        "tables": [
            {
                "page": 45,
                "tableId": "page45_t0",
                "currency": "RMB",
                "unit": "million",
                "periodHint": "Q3" | "Q3_YTD" | "FY" | "H1" | ...,
                "headers": [...],
                "rows": [
                    {"label": "REVENUE", "cells": ["78822", "29871", "50440", "1984", "161117"]},
                    ...
                ],
                "columnCount": 5
            }
        ]
    }
"""

import sys
import json
import argparse
import re
from pathlib import Path

try:
    import pdfplumber
except ImportError:
    print(json.dumps({"error": "pdfplumber not installed"}), file=sys.stderr)
    sys.exit(1)


# 中文常见乱码模式（PDF字体编码缺失时出现）：'��' 'Ű' 'ҵ' '���' 等
# 我们不依赖中文，只依赖数值结构


def is_numeric_cell(text: str) -> bool:
    """判断是否数字单元格（含千分位、负号、括号负数）"""
    if not text:
        return False
    t = text.strip().replace(",", "").replace(" ", "").replace("\n", "")
    if not t:
        return False
    if t.startswith("(") and t.endswith(")"):
        t = t[1:-1]
    t = t.lstrip("-").replace(".", "")
    return t.isdigit() and len(t) > 0


def parse_number(text: str) -> float:
    """解析数字单元格"""
    if not text:
        return None
    t = text.strip().replace(",", "").replace(" ", "").replace("\n", "")
    is_negative = False
    if t.startswith("(") and t.endswith(")"):
        is_negative = True
        t = t[1:-1]
    if t.startswith("-"):
        is_negative = True
        t = t[1:]
    try:
        v = float(t)
        return -v if is_negative else v
    except (ValueError, TypeError):
        return None


def detect_period_hint(page_text: str) -> str:
    """从页面文本推测周期类型（基于英文关键词或日期数字）

    返回：
        "Q1" / "Q2" / "Q3" / "Q4" / "H1" / "H2" / "FY" / "Q1_YTD" 等
        如果无法判断，返回空字符串
    """
    if not page_text:
        return ""
    text = page_text.lower()

    # 优先匹配英文（如果有）
    has_three_months = "three months" in text or "three-month" in text
    has_six_months = "six months" in text or "six-month" in text
    has_nine_months = "nine months" in text or "nine-month" in text
    has_year_ended = "year ended" in text or "twelve months" in text
    has_first_half = "first half" in text or "半年度" in text or "中期" in text
    has_annual = "annual" in text or "年度" in text

    # 通过月份/日期推断季度
    month = ""
    if "march 31" in text or "march31" in text or "31 march" in text or "三月" in text or "3月31" in text:
        month = "Q1_END"  # 财年Q1结束于3月
    elif "june 30" in text or "june30" in text or "30 june" in text or "六月" in text or "6月30" in text:
        month = "Q2_END"
    elif "september 30" in text or "30 september" in text or "九月" in text or "9月30" in text:
        month = "Q3_END"
    elif "december 31" in text or "31 december" in text or "十二月" in text or "12月31" in text:
        month = "Q4_END"

    # 组合判断
    if has_nine_months and month == "Q3_END":
        return "Q3_YTD"
    if has_six_months and month == "Q2_END":
        return "H1"
    if has_three_months:
        if month == "Q1_END":
            return "Q1"
        elif month == "Q2_END":
            return "Q2"
        elif month == "Q3_END":
            return "Q3"
        elif month == "Q4_END":
            return "Q4"
    if has_year_ended or has_annual:
        return "FY"
    if has_first_half or has_six_months:
        return "H1"

    return ""


def extract_year(text: str) -> int:
    """从文本中提取年份（2020-2030）"""
    m = re.search(r'\b(20[12]\d)\b', text or "")
    return int(m.group(1)) if m else 0


def looks_like_segment_table(table_data) -> bool:
    """判断是否疑似分部财务数据表

    特征：
    - 至少4列、至少3行
    - 多个单元格是百万级数字（>100）
    - 各列数据规模相近（不会一列是亿、另一列是几）
    """
    if not table_data or len(table_data) < 3:
        return False

    cols = len(table_data[0]) if table_data[0] else 0
    if cols < 4 or cols > 10:
        return False

    # 统计数值单元格
    large_numbers = 0  # 百万级以上数字
    total_numeric = 0
    for row in table_data:
        for cell in row:
            if cell and is_numeric_cell(cell):
                v = parse_number(cell)
                if v is not None:
                    total_numeric += 1
                    if abs(v) >= 100:
                        large_numbers += 1

    # 需要至少 5 个百万级数字
    return large_numbers >= 5 and total_numeric >= 5


def get_table_context(page, table_data, page_text: str) -> dict:
    """提取表格的上下文（标题、单位、币种等）"""
    period_hint = detect_period_hint(page_text)
    year = extract_year(page_text)

    # 推断币种和单位
    currency = "RMB"  # 港股财报常用RMB
    unit = "million"
    if "billion" in page_text.lower() or "十亿" in page_text:
        unit = "billion"
    if "hkd" in page_text.lower() or "hk$" in page_text.lower() or "港元" in page_text:
        currency = "HKD"
    if "us$" in page_text.lower() or "usd" in page_text.lower():
        currency = "USD"

    return {
        "periodHint": period_hint,
        "year": year,
        "currency": currency,
        "unit": unit
    }


def extract_tables(pdf_path: str, max_pages: int = 0):
    """从PDF提取所有疑似分部财务数据表"""
    result_tables = []

    with pdfplumber.open(pdf_path) as pdf:
        total_pages = len(pdf.pages)
        end_page = min(max_pages, total_pages) if max_pages > 0 else total_pages

        for page_idx in range(end_page):
            page = pdf.pages[page_idx]
            tables = page.extract_tables()
            if not tables:
                continue

            page_text = page.extract_text() or ""

            for table_idx, table_data in enumerate(tables):
                if not looks_like_segment_table(table_data):
                    continue

                # 清理空行
                clean_rows = []
                for row in table_data:
                    if any(cell and cell.strip() for cell in row):
                        # 清理每个 cell
                        clean_rows.append([
                            (c.strip() if c else "") for c in row
                        ])

                if len(clean_rows) < 3:
                    continue

                context = get_table_context(page, clean_rows, page_text)

                # 表头行（前 2-4 行通常是表头）
                # 数据行（包含数字的行）
                header_rows = []
                data_rows = []
                for row in clean_rows:
                    has_number = any(c and is_numeric_cell(c) for c in row)
                    if has_number:
                        data_rows.append(row)
                    else:
                        header_rows.append(row)

                if not data_rows:
                    continue

                result_tables.append({
                    "page": page_idx + 1,
                    "tableId": f"page{page_idx + 1}_t{table_idx}",
                    "columnCount": len(clean_rows[0]),
                    "periodHint": context["periodHint"],
                    "year": context["year"],
                    "currency": context["currency"],
                    "unit": context["unit"],
                    "headerRows": header_rows,
                    "dataRows": data_rows,
                    "pageTextSnippet": page_text[:300].replace("\n", " ")
                })

    return {"tables": result_tables}


def main():
    parser = argparse.ArgumentParser(description="Extract segment tables from HK PDF reports")
    parser.add_argument("pdf_path", help="Path to PDF file")
    parser.add_argument("--max-pages", type=int, default=0,
                        help="Max pages to process (0 = all)")
    parser.add_argument("--output", "-o", default=None,
                        help="Output JSON file path")
    args = parser.parse_args()

    if not Path(args.pdf_path).exists():
        print(json.dumps({"error": f"PDF not found: {args.pdf_path}"}), file=sys.stderr)
        return 1

    try:
        result = extract_tables(args.pdf_path, args.max_pages)
        output_json = json.dumps(result, ensure_ascii=False, indent=2)

        if args.output:
            Path(args.output).write_text(output_json, encoding="utf-8")
            print(f"Wrote {len(result['tables'])} segment tables to {args.output}",
                  file=sys.stderr)
        else:
            sys.stdout.reconfigure(encoding='utf-8')
            print(output_json)
        return 0
    except Exception as e:
        import traceback
        print(json.dumps({"error": str(e), "trace": traceback.format_exc()}),
              file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
