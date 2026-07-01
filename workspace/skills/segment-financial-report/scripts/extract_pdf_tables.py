#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
港股财报 PDF 分部数据提取（多引擎策略）

针对中文字体编码问题（港股PDF常见），不依赖中文文本识别，
而是基于**表格列结构**和**数值位置**来定位分部数据。

提取引擎优先级（从准确率高到低）：
  1. camelot-py lattice  —— 适合有完整边框线的表格（Ghostscript 渲染列线）
  2. camelot-py stream   —— 适合无边框、靠空白对齐的表格
  3. pdfplumber lines    —— pdfplumber 内置 lines 策略
  4. pdfplumber text     —— pdfplumber 文本对齐策略（带 snap_tolerance 调参）

不同引擎对同一页可能各自提取出表格，本脚本会按 (page, columnCount, 前若干数字)
做去重，避免下游 DataExtractor 重复处理。

用法：
    python extract_pdf_tables.py <pdf_path> --output <json_path> [--max-pages N]

输出格式：
    {
        "tables": [
            {
                "page": 45,
                "tableId": "page45_lattice_t0",
                "engine": "camelot-lattice",
                "currency": "RMB",
                "unit": "million",
                "periodHint": "Q3" | "Q3_YTD" | "FY" | "H1" | ...,
                "year": 2024,
                "headerRows": [...],
                "dataRows": [...],
                "columnCount": 5
            }
        ],
        "engines": ["camelot-lattice", "pdfplumber-text"]
    }
"""

import sys
import json
import argparse
import re
from pathlib import Path

# ---- 可选依赖 ----------------------------------------------------------------
# 任一引擎可用即可工作，全部缺失才会报错退出。
_HAS_CAMELOT = False
_HAS_PDFPLUMBER = False
_IMPORT_ERRORS = []

try:
    import camelot  # type: ignore
    _HAS_CAMELOT = True
except Exception as e:  # ImportError or runtime backend missing (ghostscript)
    _IMPORT_ERRORS.append(f"camelot unavailable: {e}")

try:
    import pdfplumber  # type: ignore
    _HAS_PDFPLUMBER = True
except Exception as e:
    _IMPORT_ERRORS.append(f"pdfplumber unavailable: {e}")


# ---- 通用工具 ----------------------------------------------------------------

# 把所有"代表零或缺失"的占位符统一成空串，避免误判为非数字
_DASH_LIKE = {"-", "–", "—", "−", "–", "—", "*"}


def _clean_cell(text):
    """统一清洗单元格文本：换行→空格、去边空白"""
    if text is None:
        return ""
    s = str(text)
    s = s.replace("\r", " ").replace("\n", " ")
    s = re.sub(r"\s+", " ", s).strip()
    if s in _DASH_LIKE:
        return ""
    return s


def is_numeric_cell(text):
    """判断是否数字单元格（含千分位、负号、括号负数、占位符）"""
    if text is None:
        return False
    t = _clean_cell(text)
    if not t:
        return False
    # 括号负数
    if t.startswith("(") and t.endswith(")"):
        t = t[1:-1]
    # 去千分位、空格、负号、百分号
    t = t.replace(",", "").replace(" ", "").lstrip("-").rstrip("%")
    # 仅小数点的去掉
    t = t.replace(".", "", 1)
    return t.isdigit() and len(t) > 0


def parse_number(text):
    """解析数字单元格"""
    if text is None:
        return None
    t = _clean_cell(text)
    if not t:
        return None
    is_negative = False
    if t.startswith("(") and t.endswith(")"):
        is_negative = True
        t = t[1:-1]
    t = t.replace(",", "").replace(" ", "")
    if t.startswith("-"):
        is_negative = True
        t = t[1:]
    t = t.rstrip("%")
    try:
        v = float(t)
        return -v if is_negative else v
    except (ValueError, TypeError):
        return None


def detect_period_hint(page_text):
    """从页面文本推测周期类型（基于英文关键词或日期数字）"""
    if not page_text:
        return ""
    text = page_text.lower()

    has_three_months = "three months" in text or "three-month" in text
    has_six_months = "six months" in text or "six-month" in text
    has_nine_months = "nine months" in text or "nine-month" in text
    has_year_ended = "year ended" in text or "twelve months" in text
    has_first_half = "first half" in text or "半年度" in text or "中期" in text
    has_annual = "annual" in text or "年度" in text

    month = ""
    if "march 31" in text or "march31" in text or "31 march" in text or "三月" in text or "3月31" in text:
        month = "Q1_END"
    elif "june 30" in text or "june30" in text or "30 june" in text or "六月" in text or "6月30" in text:
        month = "Q2_END"
    elif "september 30" in text or "30 september" in text or "九月" in text or "9月30" in text:
        month = "Q3_END"
    elif "december 31" in text or "31 december" in text or "十二月" in text or "12月31" in text:
        month = "Q4_END"

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


def extract_year(text):
    """从文本中提取年份（2020-2030）"""
    m = re.search(r"\b(20[12]\d)\b", text or "")
    return int(m.group(1)) if m else 0


def looks_like_segment_table(table_data):
    """判断是否疑似分部财务数据表

    特征：
      - 至少3行、4-10列
      - 至少 5 个百万级数字（>100，避免误抓"年份/页码"小表）
    """
    if not table_data or len(table_data) < 3:
        return False
    cols = len(table_data[0]) if table_data[0] else 0
    if cols < 4 or cols > 10:
        return False
    large_numbers = 0
    total_numeric = 0
    for row in table_data:
        for cell in row:
            if cell and is_numeric_cell(cell):
                v = parse_number(cell)
                if v is not None:
                    total_numeric += 1
                    if abs(v) >= 100:
                        large_numbers += 1
    return large_numbers >= 5 and total_numeric >= 5


def get_table_context(page_text):
    """提取表格的上下文（周期、年份、币种、单位）"""
    period_hint = detect_period_hint(page_text)
    year = extract_year(page_text)

    currency = "RMB"
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
        "unit": unit,
    }


def _split_header_data(clean_rows):
    """将清理后的行拆为表头/数据：含数字的视为数据行，否则视为表头"""
    header_rows = []
    data_rows = []
    for row in clean_rows:
        has_number = any(c and is_numeric_cell(c) for c in row)
        if has_number:
            data_rows.append(row)
        else:
            header_rows.append(row)
    return header_rows, data_rows


def _cleanup_rows(table_data):
    """统一清洗：去掉全空行、清洗每个 cell"""
    cleaned = []
    for row in table_data:
        if not row:
            continue
        cleaned_row = [_clean_cell(c) for c in row]
        if any(c for c in cleaned_row):
            cleaned.append(cleaned_row)
    return cleaned


def _table_fingerprint(page_num, clean_rows):
    """生成一个表格指纹用于去重：(page, columnCount, 前 5 个数值)"""
    nums = []
    for row in clean_rows:
        for c in row:
            if c and is_numeric_cell(c):
                v = parse_number(c)
                if v is not None and abs(v) >= 100:
                    nums.append(round(v, 2))
                    if len(nums) >= 5:
                        break
        if len(nums) >= 5:
            break
    cols = len(clean_rows[0]) if clean_rows and clean_rows[0] else 0
    return (page_num, cols, tuple(nums))


# ---- 引擎实现 ----------------------------------------------------------------

def _extract_with_camelot(pdf_path, max_pages):
    """用 camelot 抽取，先 lattice 后 stream。返回 [(engine, page_num, raw_rows)]"""
    results = []
    # camelot 的 pages 参数不接受"超过总页数"的显式页号 —— 传 "1,2,...,100"
    # 但 PDF 只有 50 页会抛 "list index out of range"。所以：
    #   max_pages<=0 → "all"（camelot 自动扫全部）
    #   max_pages>0  → 让 camelot 自己 clamp：用范围语法 "1-N"，比逗号列表更宽容
    if max_pages <= 0:
        page_arg = "all"
    else:
        page_arg = f"1-{max_pages}"

    for flavor in ("lattice", "stream"):
        try:
            tables = camelot.read_pdf(
                pdf_path,
                pages=page_arg,
                flavor=flavor,
                # lattice: line_scale 影响线条识别；stream: edge_tol 影响列分界容差
                **({"line_scale": 40} if flavor == "lattice" else {"edge_tol": 50}),
                suppress_stdout=True,
            )
        except Exception as e:
            # 有些 PDF 页数少于 max_pages，仍可能被范围语法误伤 —— 再退到 "all"
            if "list index out of range" in str(e) and page_arg != "all":
                try:
                    tables = camelot.read_pdf(
                        pdf_path,
                        pages="all",
                        flavor=flavor,
                        **({"line_scale": 40} if flavor == "lattice" else {"edge_tol": 50}),
                        suppress_stdout=True,
                    )
                except Exception as e2:
                    print(f"[camelot-{flavor}] failed after all-fallback: {e2}", file=sys.stderr)
                    continue
            else:
                print(f"[camelot-{flavor}] failed: {e}", file=sys.stderr)
                continue

        for t in tables:
            try:
                df = t.df
                raw_rows = df.values.tolist()
                page_num = int(t.page)
                results.append((f"camelot-{flavor}", page_num, raw_rows))
            except Exception as e:
                print(f"[camelot-{flavor}] table parse error: {e}", file=sys.stderr)
                continue

    return results


def _extract_with_pdfplumber(pdf_path, max_pages):
    """用 pdfplumber 抽取，尝试 lines 和 text 两种策略。返回 [(engine, page_num, raw_rows, page_text)]"""
    results = []
    page_texts = {}

    # 两套不同的 table_settings：
    #   lines：识别 PDF 自带的 ruling lines（最准确，前提是 PDF 有线）
    #   text：靠文字对齐推断列边界（适合无边框表格，需调 tolerance）
    settings_lines = {
        "vertical_strategy": "lines",
        "horizontal_strategy": "lines",
        "snap_tolerance": 3,
        "join_tolerance": 3,
    }
    settings_text = {
        "vertical_strategy": "text",
        "horizontal_strategy": "text",
        "snap_tolerance": 4,
        "join_tolerance": 4,
        "text_x_tolerance": 3,
        "text_y_tolerance": 3,
        "intersection_x_tolerance": 6,
        "intersection_y_tolerance": 6,
    }

    with pdfplumber.open(pdf_path) as pdf:
        total_pages = len(pdf.pages)
        end_page = min(max_pages, total_pages) if max_pages > 0 else total_pages

        for page_idx in range(end_page):
            page = pdf.pages[page_idx]
            page_num = page_idx + 1
            page_text = page.extract_text() or ""
            page_texts[page_num] = page_text

            for engine, settings in (("pdfplumber-lines", settings_lines),
                                     ("pdfplumber-text", settings_text)):
                try:
                    tables = page.extract_tables(table_settings=settings)
                except Exception as e:
                    print(f"[{engine}] page {page_num} failed: {e}", file=sys.stderr)
                    continue
                if not tables:
                    continue
                for raw_rows in tables:
                    results.append((engine, page_num, raw_rows))

            # 引擎 5：plaintext 兜底 —— 直接从页面文字里 regex 找"标签 + 数字*"的行，
            # 用于 PDF 里表格是"内联文字"（没有 ruling line、字符间距紧凑）的情况，
            # 例如腾讯年报正文里"分部收入构成"是段落里嵌入的一段固定格式文字：
            #     增值服务 69,079 45% 70,417 49%
            #     网络广告 29,794 19% 24,660 17%
            # camelot/pdfplumber 的 line/text 策略都识别不到列边界，只能靠这条兜底。
            for raw_rows in _extract_plaintext_rows(page_text):
                results.append(("pdfplumber-plaintext", page_num, raw_rows))

    return results, page_texts


# 数字 token：正负号 + 千分位 + 小数 + 可选百分号 + 括号负数
_NUM_TOKEN = re.compile(r"^[\(\-]?[\d]{1,3}(?:,\d{3})*(?:\.\d+)?[\%\)]?[\*\)]?$")


def _tokenize_line_for_table(line):
    """把一行文字切成 [label, num, num, ...] 的候选 token 列表。
    切分规则：从右往左识别"数字/百分号"末尾 token，剩下前缀作为 label。
    返回 None 表示这行不像"标签 + 数字*"结构。"""
    tokens = re.split(r"\s+", line.strip())
    if len(tokens) < 3:
        return None
    # 尾部连续数字/百分号
    numeric_tail = []
    while tokens and _NUM_TOKEN.match(tokens[-1]):
        numeric_tail.insert(0, tokens.pop())
    if len(numeric_tail) < 2 or len(numeric_tail) > 8:
        return None
    if not tokens:  # 全是数字，没标签
        return None
    label = " ".join(tokens).strip()
    if not label:
        return None
    # 剔除明显是"数字-开头"的伪标签（如页码/年份）
    if re.fullmatch(r"[\d\s\-\.,]+", label):
        return None
    # label 至少含一个非数字字符，通常是中文/英文
    return [label] + numeric_tail


def _extract_plaintext_rows(page_text):
    """从页面文本按行扫描，聚合连续 3+ 行"标签+数字*"结构的行段成候选表格。
    列数按段内多数派对齐；不匹配的行按空 token 填充。"""
    if not page_text:
        return []
    lines = page_text.split("\n")
    groups = []
    current = []
    for raw in lines:
        row = _tokenize_line_for_table(raw)
        if row is not None:
            current.append(row)
        else:
            if len(current) >= 3:
                groups.append(current)
            current = []
    if len(current) >= 3:
        groups.append(current)

    output = []
    for group in groups:
        # 目标列数：取组内数字列数的众数
        col_counts = [len(r) for r in group]
        target = max(set(col_counts), key=col_counts.count)
        norm = []
        for r in group:
            if len(r) == target:
                norm.append(r)
            elif len(r) < target:
                # 补空以对齐 —— 但只当差距 <= 1 时才收，避免把无关行拉进来
                if target - len(r) <= 1:
                    norm.append(r + [""] * (target - len(r)))
            # 长于 target：丢弃（可能是标题混入）
        if len(norm) >= 3:
            output.append(norm)
    return output


# ---- 主流程 ------------------------------------------------------------------

def extract_tables(pdf_path, max_pages=0):
    """从PDF提取所有疑似分部财务数据表（多引擎合并去重）"""
    if not _HAS_CAMELOT and not _HAS_PDFPLUMBER:
        raise RuntimeError("No PDF extraction backend available. "
                           "Install at least one of: pdfplumber, camelot-py[base]. "
                           f"Import errors: {_IMPORT_ERRORS}")

    used_engines = []
    page_texts = {}
    candidates = []  # [(engine, page_num, raw_rows)]

    # 引擎 1+2：camelot（lattice / stream）
    if _HAS_CAMELOT:
        try:
            cam_results = _extract_with_camelot(pdf_path, max_pages)
            for r in cam_results:
                candidates.append(r)
            if cam_results:
                seen_engines = set()
                for engine, _, _ in cam_results:
                    seen_engines.add(engine)
                used_engines.extend(sorted(seen_engines))
        except Exception as e:
            print(f"[camelot] backend error, skipping: {e}", file=sys.stderr)

    # 引擎 3+4：pdfplumber（lines / text）
    if _HAS_PDFPLUMBER:
        try:
            pp_results, pp_page_texts = _extract_with_pdfplumber(pdf_path, max_pages)
            page_texts.update(pp_page_texts)
            for r in pp_results:
                candidates.append(r)
            seen_engines = set()
            for engine, _, _ in pp_results:
                seen_engines.add(engine)
            used_engines.extend(sorted(seen_engines))
        except Exception as e:
            print(f"[pdfplumber] backend error, skipping: {e}", file=sys.stderr)

    # 如果 camelot 跑过但没拿到 page text，再用 pdfplumber 单独跑一遍获取页面文字（用于推断周期）
    if _HAS_PDFPLUMBER and not page_texts:
        try:
            with pdfplumber.open(pdf_path) as pdf:
                total_pages = len(pdf.pages)
                end_page = min(max_pages, total_pages) if max_pages > 0 else total_pages
                for page_idx in range(end_page):
                    page_texts[page_idx + 1] = pdf.pages[page_idx].extract_text() or ""
        except Exception as e:
            print(f"[pdfplumber] page text fetch failed: {e}", file=sys.stderr)

    # 候选去重 + 过滤
    seen_fingerprints = set()
    seen_engine_table_ids = {}  # page -> per-engine table counter
    result_tables = []

    for engine, page_num, raw_rows in candidates:
        clean_rows = _cleanup_rows(raw_rows)
        if not looks_like_segment_table(clean_rows):
            continue

        fp = _table_fingerprint(page_num, clean_rows)
        if fp in seen_fingerprints:
            continue
        seen_fingerprints.add(fp)

        page_text = page_texts.get(page_num, "")
        context = get_table_context(page_text)
        header_rows, data_rows = _split_header_data(clean_rows)
        if not data_rows:
            continue

        # tableId 形如 page45_camelot-lattice_t0
        key = (page_num, engine)
        idx = seen_engine_table_ids.get(key, 0)
        seen_engine_table_ids[key] = idx + 1

        result_tables.append({
            "page": page_num,
            "tableId": f"page{page_num}_{engine}_t{idx}",
            "engine": engine,
            "columnCount": len(clean_rows[0]),
            "periodHint": context["periodHint"],
            "year": context["year"],
            "currency": context["currency"],
            "unit": context["unit"],
            "headerRows": header_rows,
            "dataRows": data_rows,
            "pageTextSnippet": page_text[:300].replace("\n", " "),
        })

    # 按页码排序方便阅读
    result_tables.sort(key=lambda t: (t["page"], t["tableId"]))

    return {
        "tables": result_tables,
        "engines": sorted(set(used_engines)),
    }


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

    # 启动时打印一下激活的引擎，方便排查
    active = []
    if _HAS_CAMELOT:
        active.append("camelot")
    if _HAS_PDFPLUMBER:
        active.append("pdfplumber")
    print(f"[extract_pdf_tables] active backends: {active}", file=sys.stderr)
    if _IMPORT_ERRORS:
        for msg in _IMPORT_ERRORS:
            print(f"[extract_pdf_tables] note: {msg}", file=sys.stderr)

    try:
        result = extract_tables(args.pdf_path, args.max_pages)
        output_json = json.dumps(result, ensure_ascii=False, indent=2)

        if args.output:
            Path(args.output).write_text(output_json, encoding="utf-8")
            print(f"Wrote {len(result['tables'])} segment tables to {args.output} "
                  f"(engines: {result['engines']})", file=sys.stderr)
        else:
            try:
                sys.stdout.reconfigure(encoding="utf-8")
            except Exception:
                pass
            print(output_json)
        return 0
    except Exception as e:
        import traceback
        print(json.dumps({"error": str(e), "trace": traceback.format_exc()}),
              file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
