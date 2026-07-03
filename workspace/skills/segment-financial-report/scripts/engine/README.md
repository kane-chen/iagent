# segment-financial-report/scripts/engine

Python port of the Java HTML segment-extraction engine
(`io.invest.iagent.service.extraction.*`). Stage 1: HTML only.

Modules (top-down)
- `model.py` — Segment / SegmentMetric / FinancialTable / TableRow / TableCell / CompanyConfig / SegmentConfig / MetricDict
- `config_loader.py` — Loads `<TICKER>.json` from `../../config/extraction/`.
- `metric_mapper.py` — Loads `metric_dict.json` (was hardcoded in Java `MetricMapper.initDefaultMetrics()`).
- `html_parser.py` — BeautifulSoup port of `HtmlReportParser`; preserves `mergeSplitParentheses` (BABA 2026Q1 EBITA).
- `period_type_util.py` — Table-level period classifier (FY/H1/Q1..Q4).
- `period_sequence.py` — Column-wise (period, currency) sequence builder.
- `section_title_detector.py` — Colon/bold/empty-row section title heuristics.
- `segment_recognizer.py` — Config-first segment matcher with structural fallback.
- `html_support.py` — Shared `normalizeToMillion` (trunc-toward-zero) and Segment/Metric writers.
- `data_extractor.py` — Row/column extractor (uses `Math.floor` semantics like Java).
- `handlers/` — layout handlers: SegmentContribution (BEKE), SegmentRowPeriodColumn (TCOM), GenericHtml (BABA/PDD/MSFT/GOOG).
- `html_orchestrator.py` — Prio-sorted handler dispatch.
- `extraction_service.py` — Entry point; PDF path raises `NotImplementedError` (Stage 2).

Run tests:
```
pip install -q -r ../requirements.txt
pytest ../../tests/ -v
```
