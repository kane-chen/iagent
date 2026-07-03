# segment-financial-report/scripts/engine

Python port of the Java segment-extraction engine
(`io.invest.iagent.service.extraction.*`). Supports both HTML (US SEC filings)
and PDF (HK/CN filings) via the `FileSegmentParser` interface.

## Modules (top-down)

- `model.py` — Segment / SegmentMetric / FinancialTable / TableRow / TableCell /
  CompanyConfig / SegmentConfig / MetricDict / PdfColumnMapping / Layout / RowDescriptor
- `config_loader.py` — Loads `<TICKER>.json` from `../../config/extraction/` (shared with Java).
- `metric_mapper.py` — Loads `metric_dict.json` (was hardcoded in Java `MetricMapper.initDefaultMetrics()`).
- `file_filter.py` — Walks `workspace/portfolio/<TICKER>/filings/`; US → primary HTML doc, HK/CN → PDF.
- `filing_context.py` — Parses filing period (FY/H1/H2/Q1..Q4) from parent directory name;
  resolves `CURRENT_Q/PRIOR_Q/CURRENT_P/PRIOR_P` period placeholders.
- `html_parser.py` — BeautifulSoup port of `HtmlReportParser`; preserves `mergeSplitParentheses` (BABA 2026Q1 EBITA).
- `period_type_util.py` — Table-level period classifier (FY/H1/Q1..Q4).
- `period_sequence.py` — Column-wise (period, currency) sequence builder.
- `section_title_detector.py` — Colon/bold/empty-row section title heuristics.
- `segment_recognizer.py` — Config-first segment matcher with structural fallback.
- `html_support.py` — Shared `normalizeToMillion` (trunc-toward-zero) and Segment/Metric writers.
- `data_extractor.py` — Row/column extractor.
- `handlers/` — HTML layout handlers:
  - `SegmentContributionHandler` (BEKE press-release blocks)
  - `SegmentRowPeriodColumnHandler` (TCOM row-per-segment, multi-period columns)
  - `GenericHtmlLayoutHandler` (BABA/PDD/MSFT/GOOG fallback)
- `html_orchestrator.py` — Priority-sorted handler dispatch for HTML.
- `html_segment_parser.py` — `FileSegmentParser` impl for HTML (composes `HtmlReportParser` + `HtmlReportOrchestrator`).
- `pdf_parser.py` — `FileSegmentParser` impl for PDF; invokes `extract_pdf_tables.extract_tables()` in-process
  and dispatches to PdfLayoutHandler strategies via the consumed-table algorithm.
- `pdf_layout_handler.py` — ABC for PDF layout handlers + `as_rows()` helper.
- `pdf_support.py` — Shared numeric parsing, TOTAL consistency verification, and Segment/Metric writers
  (mirrors Java `PdfExtractionSupport`; delegates unit normalization to `html_support.normalizeToMillion`).
- `pdf_handlers/` — PDF layout handlers:
  - `SegmentsAsColumnsHandler` — columns = segments, rows = metrics, single period (Tencent revenue/gross-profit block)
  - `SegmentsAsRowsHandler` — rows = segments, columns = periods, single metric (Tencent multi-period block)
  - `SubsegmentMatrixHandler` — columns = L1 segments, rows = L2 revenue rows + L1 cost/op-income rows (Meituan)
- `extraction_service.py` — Entry point `FinancialExtractionService`; wires parsers and exposes
  `extractFromFile(Path)`, `extractSegments(ticker, fyStart, fyEnd)`, `extractFromHtmlContent(str)`.

## Run tests

```
pip install -q -r ../requirements.txt
pytest ../../tests/ -v
```
