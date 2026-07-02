#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 iagent Java 提取引擎中拉取指定 ticker 的分部财务数据，落地为 JSON。

流程：
  1. 找 iagent CLI jar（target/iagent-<ver>-cli.jar），未找到则调用 mvn 触发一次 package
  2. java -jar <cli.jar> --ticker <TICKER> --workspace <WS> --flat --output <JSON>
  3. 打印/回写 JSON 路径，供 generate_segment_excel.py 消费

设计原则：
  - 编排层：Python，负责路径解析、进程调度、错误映射
  - 引擎层：Java（`FinancialExtractionService`，含 HtmlLayoutHandler/PdfLayoutHandler 等
    策略实现），保留公司配置隔离（src/main/resources/extraction/config/<TICKER>.json）
  - 渲染层：generate_segment_excel.py，只负责 JSON → Excel
  - 三层独立：换渲染只改 renderer；换公司只加 JSON 配置；换识别策略只碰 Java 侧

前置：项目根目录已经 mvn package 过一次，产出了 iagent-<ver>-cli.jar。
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import Any


# ---------------------------------------------------------------------------
# 路径推断
# ---------------------------------------------------------------------------

# 本 skill 位于 workspace/skills/segment-financial-report/scripts/，
# 顺着 3 层就是 workspace 根，再上一层是 iagent 项目根。
SCRIPT_DIR = Path(__file__).parent
SKILL_DIR = SCRIPT_DIR.parent
WORKSPACE_DIR = SKILL_DIR.parent.parent            # workspace/
PROJECT_ROOT = WORKSPACE_DIR.parent                # iagent 项目根

CLI_MAIN_CLASS = "io.invest.iagent.cli.SegmentExtractionCli"


def find_cli_jar(project_root: Path) -> Path | None:
    """在 target/ 目录里查找 iagent-*-cli.jar；找不到返回 None。"""
    candidates = sorted((project_root / "target").glob("iagent-*-cli.jar"))
    return candidates[-1] if candidates else None


def ensure_cli_jar(project_root: Path, auto_build: bool) -> Path:
    """定位 cli jar；若无且 auto_build=True 则触发一次 mvn package。"""
    jar = find_cli_jar(project_root)
    if jar is not None:
        return jar

    if not auto_build:
        raise FileNotFoundError(
            f"未找到 iagent CLI jar（{project_root/'target'}/iagent-*-cli.jar）。"
            f"请先在项目根执行 `mvn -DskipTests package`，或加 --auto-build 让本脚本自动执行。"
        )

    print(f"[extract] 未找到 CLI jar，触发 mvn -DskipTests package ...", file=sys.stderr)
    result = subprocess.run(
        ["mvn", "-q", "-DskipTests", "package"],
        cwd=project_root,
    )
    if result.returncode != 0:
        raise RuntimeError("mvn package 失败，请手动执行看具体错误")

    jar = find_cli_jar(project_root)
    if jar is None:
        raise FileNotFoundError("mvn package 后仍未找到 CLI jar，请检查 pom.xml 配置")
    return jar


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def extract_segments(ticker: str,
                     workspace: Path,
                     output_json: Path,
                     project_root: Path,
                     flat: bool,
                     fiscal_year_start: str | None,
                     fiscal_year_end: str | None,
                     auto_build: bool) -> Path:
    jar = ensure_cli_jar(project_root, auto_build=auto_build)
    output_json.parent.mkdir(parents=True, exist_ok=True)

    cmd = [
        "java", "-jar", str(jar),
        "--ticker", ticker,
        "--workspace", str(workspace),
        "--output", str(output_json),
    ]
    if flat:
        cmd.append("--flat")
    if fiscal_year_start:
        cmd += ["--fiscal-year-start", fiscal_year_start]
    if fiscal_year_end:
        cmd += ["--fiscal-year-end", fiscal_year_end]

    print(f"[extract] 执行：{' '.join(cmd)}", file=sys.stderr)
    result = subprocess.run(cmd)
    if result.returncode != 0:
        raise RuntimeError(
            f"CLI 提取失败，退出码 {result.returncode}。"
            f"退出码含义：1=参数错、2=无候选财报文件、3=提取失败。"
        )

    if not output_json.exists() or output_json.stat().st_size == 0:
        raise RuntimeError(f"CLI 声称成功但输出文件为空：{output_json}")

    return output_json


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="按 ticker 从 iagent Java 引擎提取分部财务数据到 JSON",
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
        "--project-root",
        type=Path,
        default=PROJECT_ROOT,
        help="iagent 项目根（含 target/、pom.xml）",
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
        "--auto-build",
        action="store_true",
        help="jar 缺失时自动 mvn -DskipTests package 补上",
    )
    parser.add_argument(
        "--print-preview",
        action="store_true",
        help="额外在 stderr 打印前 5 条 segment 便于快速核对",
    )
    return parser.parse_args()


def default_output(workspace: Path, ticker: str) -> Path:
    from datetime import datetime
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    return workspace / "temp" / f"{ticker}_segments_{ts}.json"


def main() -> int:
    args = parse_args()
    output = args.output or default_output(args.workspace, args.ticker)

    try:
        path = extract_segments(
            ticker=args.ticker,
            workspace=args.workspace,
            output_json=output,
            project_root=args.project_root,
            flat=args.flat,
            fiscal_year_start=args.fiscal_year_start,
            fiscal_year_end=args.fiscal_year_end,
            auto_build=args.auto_build,
        )
    except Exception as e:  # noqa: BLE001
        print(f"[extract] 失败：{e.__class__.__name__}: {e}", file=sys.stderr)
        return 1

    if args.print_preview:
        try:
            data: list[dict[str, Any]] = json.loads(path.read_text(encoding="utf-8"))
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
            print(f"[extract] total records: {len(data) if isinstance(data, list) else '?'}", file=sys.stderr)
        except Exception:  # noqa: BLE001
            pass

    # 最终把 JSON 路径写到 stdout，方便调用方（generate_segment_excel.py 或 Agent）读取
    print(str(path))
    return 0


if __name__ == "__main__":
    sys.exit(main())
