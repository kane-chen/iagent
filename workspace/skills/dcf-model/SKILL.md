---
name: dcf-model
description: Real DCF model creation for equity valuation. Retrieves financial data from local files, builds cash flow projections with WACC calculations, performs sensitivity analysis, and outputs professional Excel models.
---

# DCF Model Builder

## Overview
Creates institutional-quality DCF models. The tool now accepts command-line arguments to specify the company, data location, and key market inputs, automatically sourcing financial data from local Excel files.

## Tools
- Default to using all information provided by user and MCP servers for data sourcing.

## Critical Constraints
**Environment:** Standalone .xlsx → use Python/openpyxl.
**Formulas Over Hardcodes (NON-NEGOTIABLE):** Every projection, margin, discount factor, PV, and sensitivity cell MUST be a live Excel formula.
**Verify Step-by-Step:** After each major section (data retrieval → revenue → FCF → WACC → terminal value → sensitivity), confirm with user before proceeding.

## DCF Process Workflow
### Step 1: Data Retrieval and Validation
Fetch data from local Excel files specified by the `workspace` and `ticker` arguments.
→ The script will automatically find the latest `{ticker}_balance.xlsx`, `{ticker}_income.xlsx`, and `{ticker}_cashflow.xlsx` files in the `workspace/excels` directory.

### Step 2: Historical Analysis (3-5 years)
Analyze revenue trends, margin progression, capital intensity from the extracted data.

### Step 3-5: Revenue Projections → OpEx → FCF
Build projections using the scenario block approach.
→ The model will use the extracted historical data to generate assumptions for Bear, Base, and Bull cases.
→ **Core UFCF Formula:** `Revenue × EBIT Margin = EBIT → EBIT × (1 - Tax Rate) = NOPAT → NOPAT + D&A - CapEx - ΔNWC = UFCF`

### Step 6: WACC Calculation
Calculate WACC using the CAPM methodology. The `taxRate` argument will be used for the after-tax cost of debt.

### Step 7-8: Discounting & Terminal Value
Discount the projected UFCFs and Terminal Value to present value.
→ **Core Valuation Formula:** `PV(Explicit UFCFs) + PV(Terminal Value) = Enterprise Value (EV) → EV - Net Debt = Equity Value`

### Step 9: Enterprise to Equity Value Bridge
Calculate the final equity value and implied share price.

### Step 10: Sensitivity Analysis
Generate sensitivity tables for WACC vs. Terminal Growth, Revenue Growth vs. EBIT Margin, etc.

## Model Construction — Script-Driven Build
Instead of manually writing formulas cell-by-cell, use the parameterized builder script:

```bash
python build_dcf_model.py --ticker AAPL --workspace /path/to/project --currentPrice 185.50 --taxRate 0.21
```
## Arguments
- --ticker: The company's stock ticker (e.g., AAPL, BABA). Used to find data files and name the output.
- --workspace: The root directory of the project. The script will look for financial data in workspace/excels.
- --currentPrice: The current stock price. Used for market cap calculation and implied upside.
- --taxRate: The effective tax rate to be used in projections and WACC calculation.

## Deliverables
- File: {ticker}_DCF_Model_{date}.xlsx
- Two sheets: DCF (with sensitivity at bottom), WACC
- See model-layout.md for complete sheet structure.
- See formatting-standards.md for visual standards.

## Troubleshooting
If errors or unreasonable results → read TROUBLESHOOTING.md