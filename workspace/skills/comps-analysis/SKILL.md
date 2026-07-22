---
name: comps-analysis
description: |
  Build institutional-grade comparable company analyses with operating metrics, valuation multiples, and statistical benchmarking. This skill uses a hybrid data acquisition strategy: prioritizing local Excel files for operating metrics, and falling back to web search (Eastmoney, Futu, etc.) for missing data or market valuation metrics.
---

## ⚠️ CRITICAL: Data Acquisition Strategy (READ FIRST)

**ALWAYS follow this data source hierarchy:**

1. **FIRST: Local Excel Extraction (Priority 1)**
    - Search the `workspace/excels/` directory for files matching `{ticker}_{type}_*.xlsx` (e.g., `US_MSFT_income_*.xlsx`, `US_MSFT_balance_*.xlsx`).
    - Extract operating metrics (Revenue, EBIT, Net Income, etc.) directly from these local files.
    - This is the primary source for historical financial data.

2. **SECOND: Web Search for Missing Data (Priority 2)**
    - If specific data points are missing from local files (e.g., Market Cap, Enterprise Value, Beta, or recent quarterly data), use `web_search`.
    - Target reliable open financial APIs or websites: Eastmoney (东方财富), Futu (富途), Xueqiu (雪球), or Yahoo Finance.
    - Extract the required figures and prepare to input them into the model.

3. **MANDATORY: Data Source Annotation**
    - **Every hardcoded input cell MUST have a cell comment (批注) citing its exact source.**
    - For local Excel data: `"Source: Local file US_MSFT_income_*.xlsx, Row '总收入'"`
    - For web search data: `"Source: Futu (富途) - MSFT Market Cap, accessed 2024-10-02"`
    - No hardcoded value is allowed without a traceable source comment.

## ⚠️ CRITICAL: Formulas Over Hardcodes

### Environment & Execution
- **If generating a standalone `.xlsx` file:** Use Python/openpyxl. Write `cell.value = "=E7/C7"` (formula string).
- The `build_comps_model.py` script handles the layout, local Excel parsing, and formula generation.
- After running the script, Claude must manually inspect the output Excel, use `web_search` to fill any missing market data cells (marked with "TODO: Web Search" comments), and add appropriate source citations.

### Core Principles
* **Statistical Rigor:** Always include a statistics block (Maximum, 75th Percentile, Median, 25th Percentile, Minimum) for comparable metrics.
* **Cross-Reference:** Valuation multiples MUST reference the operating metrics section. Never input the same raw data twice.

## WORKFLOW & QUALITY CHECKS

1. **Identify Peer Group:** Determine the list of comparable company tickers.
2. **Run Script:** Execute `python scripts/build_comps_model.py --tickers MSFT,GOOGL,AMZN --workspace /path/to/workspace`.
    - The script extracts local data, builds formulas, and leaves comments for missing data.
3. **Web Search Fill:** Open the generated Excel. For cells with "TODO: Web Search" comments, perform web searches on Eastmoney/Futu to find the values.
4. **Input & Cite:** Enter the web-sourced values in blue font and update the cell comment with the exact web source URL and date.
5. **Sanity Checks:** Verify margins, multiples, and growth-multiple correlations.

## REFERENCES

- **[references/formulas.md](references/formulas.md):** Detailed formula references for statistics and financial calculations.
- **[references/formatting.md](references/formatting.md):** Visual convention standards and color palettes.
- **[references/data_sources.md](references/data_sources.md):** Guidelines on extracting data from local files and web sources.
- **[references/industry_metrics.md](references/industry_metrics.md):** Industry-specific metric selection guides (SaaS, Manufacturing, Financial Services, etc.).
