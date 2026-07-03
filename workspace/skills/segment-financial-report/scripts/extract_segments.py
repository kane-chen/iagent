#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 iagent 纯 Python 引擎中拉取指定 ticker 的分部财务数据，落地为 JSON。
可选 `--excel` 一步产出 Excel（内部调用 generate_segment_excel 逻辑，不再二次起进程）。

流程：
  1. 加载 engine/ 下的纯 Python 提取引擎（支持 HTML + PDF）
  2. 通过 FinancialFileFilter 过滤候选财报文件
  3. 按文件扩展名分发给 HtmlFileSegmentParser / PdfFileSegmentParser
  4. 按策略模式（HtmlLayoutHandler / PdfLayoutHandler）+ 公司配置（config/extraction/<TICKER>.json）
     提取分部数据
  5. 打印/回写 JSON 路径，供下游消费；--excel 时直接生成 Excel 并打印 xlsx 路径

设计原则：
  - 编排层：本脚本，负责路径解析、CLI 参数、错误映射
  - 引擎层：engine/ 包（纯 Python），含 Html/Pdf 解析器、策略 handler、公司配置
  - 渲染层：generate_segment_excel.py 逻辑（同目录），本脚本以 import 方式复用
  - 三层独立：换渲染只改 renderer；换公司只加 JSON 配置；换识别策略只碰引擎侧
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from datetime import datetime
from pathlib import Path
from typing import Any


# ---------------------------------------------------------------------------
# 路径推断
# ---------------------------------------------------------------------------

# 本脚本位于 workspace/skills/segment-financial-report/scripts/
SCRIPT_DIR = Path(__file__).resolve().parent
SKILL_DIR = SCRIPT_DIR.parent            # segment-financial-report/
WORKSPACE_DIR = SKILL_DIR.parent.parent  # workspace/


# ---------------------------------------------------------------------------
# 噪声抑制：pdfminer 在处理港股嵌入字体（FlateDecode 损坏流）时会打大量
# "Error -5 while decompressing data: incomplete or truncated stream" 到 stderr。
# 这些流是中文字体子集，表格抽取不依赖字体渲染，因此对结果无影响。
# 把 pdfminer / pdfplumber / PIL 等模块的日志级别抬到 WARNING 以上，避免污染 stderr。
# ---------------------------------------------------------------------------
def _silence_pdf_noise() -> None:
    # 港股 PDF 的嵌入中文字体子集经常有损坏的 FlateDecode 流，
    # pdfminer/playa(camelot 后端) 会打 "Error -5 while decompressing data" 到 stderr。
    # 这些字体流不影响表格文本/数字提取，抬到 CRITICAL 以上屏蔽。
    for noisy in (
        "pdfminer", "pdfminer.psparser", "pdfminer.pdfparser",
        "pdfminer.pdfinterp", "pdfminer.converter", "pdfminer.cmapdb",
        "pdfplumber", "PIL", "camelot",
        "playa", "playa.document", "playa.page", "playa.ccitt",
        "playa.filter", "playa.pdftypes",
    ):
        logging.getLogger(noisy).setLevel(logging.CRITICAL + 1)


_silence_pdf_noise()


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def run_python_engine(ticker: str,
                      workspace: Path,
                      output_json: Path,
                      flat: bool,
                      fiscal_year_start: str | None,
                      fiscal_year_end: str | None) -> Path:
    """纯 Python 引擎：HTML + PDF。

    使用 FinancialExtractionService.extractSegments() 批量提取，
    内部按文件扩展名自动路由到 HtmlFileSegmentParser / PdfFileSegmentParser。
    """
    # Make sibling engine package importable.
    if str(SCRIPT_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPT_DIR))
    from engine.extraction_service import FinancialExtractionService  # type: ignore

    svc = FinancialExtractionService(companyCode=ticker, workspace=workspace)
    all_segments = svc.extractSegments(ticker, fiscal_year_start, fiscal_year_end)

    if not all_segments:
        print(f"[extract] 未找到 {ticker} 的分部财务数据（workspace={workspace}）", file=sys.stderr)
        sys.exit(2)

    def _seg_to_dict(seg, parent_code=None):
        """Convert a Segment to a JSON-serializable dict (tree form)."""
        return {
            "segmentCode": seg.segmentCode,
            "segmentName": seg.segmentName,
            "level": seg.level,
            "sortOrder": getattr(seg, "sortOrder", 0),
            "metrics": [
                {
                    "metricCode": m.metricCode,
                    "metricName": m.metricName,
                    "value": m.value,
                    "period": m.period,
                    "currency": m.currency,
                    "unit": m.unit,
                    "sourceType": m.sourceType,
                    "sourceLocation": m.sourceLocation,
                    "confidenceScore": m.confidenceScore,
                }
                for m in seg.metrics
            ],
            "children": [_seg_to_dict(c, seg.segmentCode) for c in seg.children],
        }

    if flat:
        payload = []

        def _walk(seg, parent_code):
            for m in seg.metrics:
                payload.append({
                    "segmentCode": seg.segmentCode,
                    "segmentName": seg.segmentName,
                    "level": seg.level,
                    "parentSegmentCode": parent_code,
                    "metricCode": m.metricCode,
                    "metricName": m.metricName,
                    "value": m.value,
                    "period": m.period,
                    "currency": m.currency,
                    "unit": m.unit,
                    "sourceType": m.sourceType,
                    "sourceLocation": m.sourceLocation,
                    "confidenceScore": m.confidenceScore,
                })
            for c in seg.children:
                _walk(c, seg.segmentCode)
        for s in all_segments:
            _walk(s, None)
    else:
        payload = [_seg_to_dict(s) for s in all_segments]

    output_json.parent.mkdir(parents=True, exist_ok=True)
    output_json.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, default=str),
        encoding="utf-8",
    )
    return output_json


def generate_excel(ticker: str, workspace: Path, json_path: Path,
                   output_xlsx: Path | None = None) -> Path:
    """一步到位渲染 Excel：同进程 import generate_segment_excel 逻辑，不再起子进程。

    让 extract_segments.py --excel 成为单次 bash 调用即可拿到 .xlsx 路径，
    省去 Agent 两次 bash + xargs/PowerShell 管道的开销。
    """
    if str(SCRIPT_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPT_DIR))
    import generate_segment_excel as gx  # type: ignore

    # 读入 flat JSON（--excel 强制 flat 模式）
    segments = json.loads(json_path.read_text(encoding="utf-8"))
    if not segments:
        print(f"[extract] 分部数据为空，跳过 Excel 生成", file=sys.stderr)
        sys.exit(2)

    periods = gx.get_all_periods(segments)
    if not periods:
        periods = ["Latest"]

    excels_dir = workspace / "excels"
    logs_dir = excels_dir / "logs"
    for d in (excels_dir, logs_dir):
        d.mkdir(parents=True, exist_ok=True)

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    if output_xlsx is None:
        output_xlsx = excels_dir / f"{ticker}_segments_{timestamp}.xlsx"
    log_file = logs_dir / f"segment_{ticker}_{timestamp}.log"
    logger = gx.setup_logging(str(log_file))
    gx.generate_excel_with_styling(segments, periods, str(output_xlsx), ticker, logger)
    print(f"[extract] Excel written to {output_xlsx}", file=sys.stderr)
    return output_xlsx


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="按 ticker 从 iagent 纯 Python 引擎提取分部财务数据到 JSON（可选直接生成 Excel）",
    )
    parser.add_argument("--ticker", required=True, help="股票代码，例如 BABA / 00700 / PDD")
    parser.add_argument(
        "--workspace",
        type=Path,
        default=WORKSPACE_DIR,
        help="workspace 根目录（含 portfolio/<TICKER>/filings/）",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
        help="输出 JSON 路径。默认写到 workspace/temp/<TICKER>_segments_<ts>.json",
    )
    parser.add_argument(
        "--flat",
        action="store_true",
        default=True,
        help="输出扁平 SegmentMetricDTO 列表（renderer 需要该格式）；默认开启",
    )
    parser.add_argument(
        "--no-flat",
        dest="flat",
        action="store_false",
        help="输出树状 Segment 结构（含子分部嵌套）",
    )
    parser.add_argument("--fiscal-year-start", default=None, help="起始财年，例如 2022")
    parser.add_argument("--fiscal-year-end", default=None, help="结束财年，例如 2025")
    parser.add_argument(
        "--excel",
        action="store_true",
        help="提取后直接生成 Excel（一步到位，无需再调用 generate_segment_excel.py）。"
             "输出 xlsx 路径到 stdout。",
    )
    parser.add_argument(
        "--excel-output",
        type=Path,
        default=None,
        help="--excel 时自定义 xlsx 输出路径（默认 workspace/excels/<TICKER>_segments_<ts>.xlsx）",
    )
    parser.add_argument(
        "--print-preview",
        action="store_true",
        help="额外在 stderr 打印前 5 条 segment 便于快速核对",
    )
    return parser.parse_args()


def default_output(workspace: Path, ticker: str) -> Path:
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    return workspace / "temp" / f"{ticker}_segments_{ts}.json"


def main() -> int:
    args = parse_args()
    workspace: Path = args.workspace.resolve()
    output = args.output or default_output(workspace, args.ticker)

    # --excel 强制 flat（generate_excel 需要扁平 DTO）
    flat = args.flat
    if args.excel:
        flat = True

    try:
        json_path = run_python_engine(
            ticker=args.ticker,
            workspace=workspace,
            output_json=output,
            flat=flat,
            fiscal_year_start=args.fiscal_year_start,
            fiscal_year_end=args.fiscal_year_end,
        )
    except SystemExit:
        raise  # let explicit sys.exit pass through (e.g. exit code 2 for no files)
    except Exception as e:  # noqa: BLE001
        print(f"[extract] 失败：{e.__class__.__name__}: {e}", file=sys.stderr)
        return 1

    if args.print_preview or args.excel:
        try:
            data: list[dict[str, Any]] = json.loads(json_path.read_text(encoding="utf-8"))
            if args.print_preview:
                preview = data[:5] if isinstance(data, list) else []
                print("[extract] preview:", file=sys.stderr)
                for row in preview:
                    print(
                        f"  {row.get('segmentName','?')}"
                        f" | {row.get('metricName','?')}"
                        f" | {row.get('period','?')}"
                        f" | {row.get('value','?')} {row.get('unit','')}",
                        file=sys.stderr,
                    )
                print(f"[extract] total records: {len(data) if isinstance(data, list) else '?'}",
                      file=sys.stderr)
        except Exception:  # noqa: BLE001
            pass

    if args.excel:
        try:
            xlsx_path = generate_excel(
                ticker=args.ticker,
                workspace=workspace,
                json_path=json_path,
                output_xlsx=args.excel_output,
            )
        except Exception as e:  # noqa: BLE001
            print(f"[extract] Excel 生成失败：{e.__class__.__name__}: {e}", file=sys.stderr)
            return 1
        # --excel 模式：stdout 输出 xlsx 路径（与原先 JSON 路径协议保持一致，只是后缀变了）
        print(str(xlsx_path))
    else:
        # 默认模式：stdout 输出 JSON 路径
        print(str(json_path))
    return 0


if __name__ == "__main__":
    sys.exit(main())
