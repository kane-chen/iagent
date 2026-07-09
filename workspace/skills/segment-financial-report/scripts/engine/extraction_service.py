# -*- coding: utf-8 -*-
"""分部数据提取主服务。

通过 parser 注册表支持 HTML（美股 SEC）和 PDF（港股/A 股）两类财报文件。
"""
from __future__ import annotations

import logging
from pathlib import Path
from typing import List, Optional

from .config_loader import CompanyConfigLoader
from .data_extractor import DataExtractor
from .file_filter import FinancialFileFilter
from .html_orchestrator import HtmlReportOrchestrator
from .html_segment_parser import HtmlFileSegmentParser
from .html_support import HtmlExtractionSupport
from .metric_mapper import MetricMapper
from .model import CompanyConfig, Segment
from .pdf_parser import PdfFileSegmentParser
from .segment_recognizer import SegmentRecognizer
from .ytd_derivation import derive_ytd_quarters

logger = logging.getLogger(__name__)


class ExtractionError(Exception):
    """提取失败，带面向用户的友好消息。"""
    def __init__(self, message: str, hint: Optional[str] = None):
        super().__init__(message)
        self.message = message
        self.hint = hint

    def user_message(self) -> str:
        if self.hint:
            return f"{self.message}\n提示: {self.hint}"
        return self.message


class FinancialExtractionService:
    def __init__(self, companyCode: Optional[str] = None,
                 workspace: Optional[Path] = None,
                 config_dir: Optional[Path] = None,
                 companyConfig: Optional[CompanyConfig] = None):
        self.workspace = workspace
        self.metricMapper = MetricMapper()
        self.configLoader = CompanyConfigLoader(config_dir)
        self.fileFilter = FinancialFileFilter(workspace) if workspace else None
        self.companyConfig: Optional[CompanyConfig] = None
        self.segmentRecognizer: Optional[SegmentRecognizer] = None
        self.dataExtractor: Optional[DataExtractor] = None
        self.htmlOrchestrator: Optional[HtmlReportOrchestrator] = None
        self.last_errors: list[tuple[Path, str]] = []  # (file, error_msg)

        # Build parser list
        self.htmlParser = HtmlFileSegmentParser()
        self.pdfParser = PdfFileSegmentParser(workspace)
        self.parsers = [self.htmlParser, self.pdfParser]

        if companyConfig is not None:
            self.configure(companyConfig)
        elif companyCode is not None:
            cfg = self.configLoader.loadConfig(companyCode)
            if cfg is None:
                configs_dir = self.configLoader.config_dir
                raise ExtractionError(
                    f"缺少公司配置: {companyCode}",
                    hint=(
                        f"请在 {configs_dir}/ 下新增 {companyCode}.json（参考 BABA.json / 00700.json）；"
                        f"或检查 ticker 拼写/市场前缀是否正确（如 BABA 而非 BABA-US、00700 而非 700）"
                    ),
                )
            self.configure(cfg)

    def configure(self, cfg: CompanyConfig) -> None:
        """根据公司 config 构建协作组件，单一初始化入口。"""
        self.companyConfig = cfg
        self.segmentRecognizer = SegmentRecognizer(cfg)
        self.dataExtractor = DataExtractor(self.segmentRecognizer, self.metricMapper)
        self.htmlOrchestrator = HtmlReportOrchestrator.standard(
            HtmlExtractionSupport(self.metricMapper),
            self.segmentRecognizer,
            self.dataExtractor,
        )
        self.htmlParser.setOrchestrator(self.htmlOrchestrator)
        self.pdfParser.setCompanyConfig(cfg)

    def setCompanyConfig(self, cfg: CompanyConfig) -> None:
        self.configure(cfg)

    # --- public API: single file -----------------------------------------------

    def extractFromFile(self, file: Path) -> List[Segment]:
        p = Path(file)
        logger.info("Extracting financial data from file: %s", p.name)
        for parser in self.parsers:
            if parser.supports(p):
                return parser.parse(p, self.companyConfig)
        logger.warning("No parser supports file: %s", p.name)
        return []

    def extractFromHtmlContent(self, html_content: str) -> List[Segment]:
        return self.htmlParser.parseHtml(html_content, self.companyConfig)

    # --- public API: batch (ticker + fiscal year range) -----------------------

    def extractSegments(self, ticker: str,
                        fiscal_year_start: Optional[str] = None,
                        fiscal_year_end: Optional[str] = None) -> List[Segment]:
        self.last_errors = []
        if self.fileFilter is None:
            raise ExtractionError("未配置 workspace，无法定位财报文件")
        files = self.fileFilter.filter(ticker, fiscal_year_start, fiscal_year_end)
        if not files:
            diag = self.fileFilter.last_diagnostics
            msg = f"未找到 {ticker} 的可提取财报文件"
            hint = diag.summarize() if diag else None
            raise ExtractionError(msg, hint=hint or "请先用 futu-filing skill 下载财报，或检查 ticker 拼写")
        all_segments: List[Segment] = []
        for f in files:
            try:
                segs = self.extractFromFile(f)
                if segs:
                    all_segments.extend(segs)
                else:
                    self.last_errors.append((f, "该文件未解析出任何 segment（可能格式不匹配或为不支持的报表类型）"))
                    logger.warning("no segments extracted from %s", f)
            except ExtractionError:
                raise
            except FileNotFoundError as e:
                err = f"文件不存在: {e.filename if hasattr(e, 'filename') else f}"
                self.last_errors.append((f, err))
                logger.error("extract failed: %s: %s", f, err)
            except Exception as e:  # noqa: BLE001
                err = f"{type(e).__name__}: {e}"
                self.last_errors.append((f, err))
                logger.error("extract failed: %s", f, exc_info=e)

        if not all_segments:
            # 所有文件都失败或没有解析出 segment
            if self.last_errors:
                sample = "; ".join(f"{f.name}: {e}" for f, e in self.last_errors[:3])
                raise ExtractionError(
                    f"解析 {ticker} 的 {len(files)} 个财报文件全部失败",
                    hint=f"错误样例: {sample}" + (f"（共 {len(self.last_errors)} 个错误）"
                          if len(self.last_errors) > 3 else ""),
                )
            raise ExtractionError(
                f"{ticker} 的财报文件已找到，但未解析出分部数据",
                hint=(
                    "可能原因：\n"
                    "  1. 该公司的分部表格布局尚未支持（需要新的 handler）\n"
                    "  2. 公司配置（config/extraction/<TICKER>.json）的 segment 别名不匹配\n"
                    "  3. PDF 是扫描件（图片型）无法直接抽取文本\n"
                    "详细原因见日志文件"
                ),
            )

        result = _merge_segments(all_segments)
        # Derive missing single quarters from YTD / FY totals (Q4 = FY - QTD9, Q2 = QTD6 - Q1)
        added = derive_ytd_quarters(result)
        if added:
            logger.info("Derived %d single-quarter metrics from YTD/FY totals", added)
        # Final period-type filter: drop YTD/H periods that aren't in includePeriodTypes
        if self.dataExtractor is not None:
            result = self.dataExtractor.filterSegmentsByPeriodType(result)
        if self.last_errors:
            logger.warning("%d/%d files had errors, %d segments extracted from %d files",
                           len(self.last_errors), len(files),
                           sum(len(s.metrics) for s in result),
                           len(files) - len(self.last_errors))
        return result

    # --- backward-compat aliases ----------------------------------------------

    def extractFromHtmlFile(self, file: Path) -> List[Segment]:
        return self.extractFromFile(file)


def _merge_segments(segments: List[Segment]) -> List[Segment]:
    """按 segmentCode 分组，递归合并 metrics/children 去重。"""
    by_code: dict[str, Segment] = {}
    for seg in segments:
        code = seg.segmentCode
        if code not in by_code:
            by_code[code] = seg
        else:
            existing = by_code[code]
            # merge metrics (dedup by (metricCode, period))
            existing_keys = {
                (m.metricCode, m.period) for m in existing.metrics
            }
            for m in seg.metrics:
                if (m.metricCode, m.period) not in existing_keys:
                    existing.addMetric(m)
            # merge children recursively
            _merge_children(existing, seg)
    return list(by_code.values())


def _merge_children(parent: Segment, incoming: Segment) -> None:
    existing_by_code = {c.segmentCode: c for c in parent.children}
    for child in incoming.children:
        code = child.segmentCode
        if code in existing_by_code:
            existing = existing_by_code[code]
            existing_keys = {(m.metricCode, m.period) for m in existing.metrics}
            for m in child.metrics:
                if (m.metricCode, m.period) not in existing_keys:
                    existing.addMetric(m)
            _merge_children(existing, child)
        else:
            parent.addChild(child)
