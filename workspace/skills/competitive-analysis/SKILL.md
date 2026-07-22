---
name: competitive-analysis
description: Framework for building competitive landscape decks — market positioning, competitor deep-dives, comparative analysis, strategic synthesis. Uses a script-driven approach to generate base PPTX files, integrating data from user inputs, local Excels, and web search.
---

## ⚠️ CRITICAL: Data Acquisition Strategy (READ FIRST)

**ALWAYS follow this data source hierarchy:**

1. **Priority 1: User Parameter Input**
    - Explicit arguments provided via CLI or prompt (e.g., `--revenue 5000`) override all other sources.
    - If the user provides a specific number or ratio, use it exactly.

2. **Priority 2: Local Excel Extraction**
    - Search `workspace/excels/` for files matching `{ticker}_{type}_*.xlsx` (e.g., `US_MSFT_income_*.xlsx`).
    - Extract historical financial metrics (Revenue, Growth, Margins, etc.) directly.

3. **Priority 3: Web Search (Missing Data)**
    - If data is missing from parameters and local files, use `web_search` targeting specific open data sources (Eastmoney/东方财富, Futu/富途, Xueqiu/雪球).
    - Every web-sourced data point MUST be marked with its source and access date.

## Environment & Execution

This skill generates standalone `.pptx` files using Python (`python-pptx`).
- Execute the script: `python scripts/build_competitive_deck.py --target MSFT --competitors GOOGL,AMZN --workspace /path/to/workspace --output /path/to/deck.pptx`
- The script parses local data, applies design standards, and generates base slides (Target Profile, Comparative Analysis).
- Claude must then manually inspect the PPT, use `web_search` to fill missing market data, and finalize the narrative.

## Standards — apply throughout

### Design & Typography
- **Slide titles:** 28-32pt bold. Insight-driven, not just labels.
- **Body text:** 14-16pt (never below 14pt).
- **Colors:** 2-3 colors max. Muted — navy (`#1F4E79`), gray (`#F2F2F2`), one accent.
- **Tables:** Light gray header row, bold. Right-align numbers, left-align text.

### Analysis Workflow
1. **Scope:** Define target, competitors, and metrics (See `references/frameworks.md`).
2. **Gather Data:** Run script for local extraction; identify gaps for Web Search.
3. **Market Context:** Size, growth, drivers.
4. **Target Profile & Competitor Deep-Dives:** Financials and qualitative assessments.
5. **Comparative Analysis:** Side-by-side tables with standardized metrics (See `references/schemas.md`).
6. **Synthesis:** Moat assessment, strategic context, scenarios.

## REFERENCES

- **[references/frameworks.md](references/frameworks.md):** 2x2 Matrix axis pairs by industry.
- **[references/schemas.md](references/schemas.md):** Table formats for M&A, Scenarios, and slide structures.
- **[references/data_sources.md](references/data_sources.md):** Detailed data extraction rules and web search targets.
