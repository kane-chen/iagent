# segment-financial-report/scripts/engine

纯 Python 分部报告提取引擎，支持 HTML（美股 SEC filings）和 PDF（港股/A 股）两类文件，
通过 `FileSegmentParser` 接口统一抽象。

## Modules（top-down）

- `model.py` — Segment / SegmentMetric / FinancialTable / TableRow / TableCell /
  CompanyConfig / SegmentConfig / MetricDict / PdfColumnMapping / Layout / RowDescriptor
- `config_loader.py` — 从 `../../config/extraction/` 加载 `<TICKER>.json` 公司配置
- `metric_mapper.py` — 加载 `metric_dict.json`（指标名映射表）
- `file_filter.py` — 遍历 `workspace/portfolio/<TICKER>/filings/`；US → 主 HTML 文档，HK/CN → PDF
- `filing_context.py` — 从父目录名解析报告期（FY/H1/H2/Q1..Q4）；
  解析 `CURRENT_Q/PRIOR_Q/CURRENT_P/PRIOR_P` 周期占位符
- `html_parser.py` — BeautifulSoup 实现的 HTML 表格解析器，保留 `mergeSplitParentheses` 处理
- `period_type_util.py` — 表格级周期分类器（FY/H1/Q1..Q4）
- `period_sequence.py` — 列维度（period, currency）序列构建
- `section_title_detector.py` — 冒号/加粗/空行等 section 标题启发式检测
- `segment_recognizer.py` — 配置优先的 segment 匹配，配置未命中时走结构回退
- `html_support.py` — `normalizeToMillion`（截尾法 unit 换算）以及 Segment/Metric 写入工具
- `data_extractor.py` — 行/列数据提取器
- `handlers/` — HTML layout handlers：
  - `SegmentContributionHandler`（BEKE press-release 块）
  - `SegmentRowPeriodColumnHandler`（TCOM 行为 segment、列为多周期）
  - `GenericHtmlLayoutHandler`（BABA/PDD/MSFT/GOOG 兜底）
- `html_orchestrator.py` — HTML handler 按优先级排序分派
- `html_segment_parser.py` — HTML 的 `FileSegmentParser` 实现（组合 `html_parser` + `html_orchestrator`）
- `pdf_parser.py` — PDF 的 `FileSegmentParser` 实现；进程内调用 `extract_pdf_tables.extract_tables()`，
  通过 consumed-table 算法分派给 PdfLayoutHandler 策略
- `pdf_layout_handler.py` — PDF layout handler 的 ABC 及 `as_rows()` 辅助方法
- `pdf_support.py` — PDF 侧数值解析、TOTAL 一致性校验、Segment/Metric 写入工具；
  unit 归一化复用 `html_support.normalizeToMillion`
- `pdf_handlers/` — PDF layout handlers：
  - `SegmentsAsColumnsHandler` — 列为 segments、行为指标、单周期（腾讯收入/毛利块）
  - `SegmentsAsRowsHandler` — 行为 segments、列为周期、单指标（腾讯多周期块）
  - `SubsegmentMatrixHandler` — 列为 L1 segments、行为 L2 收入行 + L1 成本/经营利润行（美团）
- `extraction_service.py` — 入口 `FinancialExtractionService`；装配各 parser，暴露
  `extractFromFile(Path)`、`extractSegments(ticker, fyStart, fyEnd)`、`extractFromHtmlContent(str)`

## Run tests

```
pip install -q -r ../requirements.txt
pytest ../../tests/ -v
```
