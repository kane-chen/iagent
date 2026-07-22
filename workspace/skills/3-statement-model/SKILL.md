---
name: 3-statement-model
description: This skill generates integrated 3-statement financial models (Income Statement, Balance Sheet, Cash Flow Statement) from scratch. It extracts historical data from financial Excel files, applies projection assumptions, and builds a fully linked model with live formulas following professional investment banking standards.
---

# 3-Statement Financial Model

## CRITICAL INSTRUCTIONS - READ FIRST

### Environment & Execution

This skill generates standalone `.xlsx` files using Python (`openpyxl`).

- Execute the script: `python scripts/build_3_statement_model.py --ticker BABA --workspace /path/to/workspace --growth_rate 0.05`
- The script automatically extracts historical financial data from `workspace/excels/` and builds a 5-sheet integrated financial model.

### Core Principles

- **Formulas over hardcodes (non-negotiable)**: Every projection cell, roll-forward, and linkage MUST be an Excel formula. When using openpyxl, write formula strings (`ws["D15"] = "=D14*(1+Assumptions!$B$5)"`), NOT computed results.
- **Integrity Checks**: The model must include Balance Sheet checks (Assets = Liabilities + Equity) and Cash Tie-Outs (CF Ending Cash = BS Cash) using formulas.
- **Circular Reference Handling**: Interest expense is calculated using the *Beginning* Debt Balance to avoid circular references (Interest -> Net Income -> Cash -> Debt Paydown -> Interest).

### Formula & Linkage Conventions (per formulas.md)
- **Income Statement**: Revenue Growth drives Revenue. Cost of Revenue, OpEx, etc., driven by % of Revenue. EBITDA = EBIT + D&A.
- **Balance Sheet**: Assets = Liabilities + Equity. Retained Earnings = Prior RE + Net Income - Dividends.
- **Cash Flow**: CFO starts with Net Income (linked from IS). Add back D&A. Subtract ΔAR, ΔInventory, Add ΔAP. CFI subtracts CapEx. CFF handles Debt and Equity changes. Ending Cash links to BS.
### Formatting Standards (per formatting.md)
- **Hard-coded inputs**: Blue font (0000FF), Light grey fill (F2F2F2)
- **Formulas**: Black font (000000), White fill
- **Cross-tab links**: Green font (008000)
- **Headers**: Dark blue fill (1F4E79), White bold font
- **Subtotals/Totals**: Bold font, Bottom border
- **Check rows**: Medium blue fill (BDD7EE). Error values should be red.
### SCRIPT-DRIVEN BUILD
The `build_3_statement_model.py` script handles the entire workflow:

1. **Data Retrieval**: Finds `{ticker}_{type}_*.xlsx` in the excels directory. 
2. **Extraction**: Parses Income Statement, Balance Sheet, and Cash Flow Statement for the latest FY. Handles missing `sharedStrings.xml` robustly. 
3. **Model Construction**: Builds 4 sheets:
   - **Assumptions**: Revenue growth, margin assumptions, CapEx %, D&A %, Tax Rate.
   - **Income Statement (IS)**: 5-year projection of Revenue down to Net Income.
   - **Balance Sheet (BS)**: 5-year projection of Assets, Liabilities, and Equity, including a Balance Check row.
   - **Cash Flow (CF)**: 5-year projection of CFO, CFI, CFF, linking Net Income from IS and Ending Cash to BS.
## VERIFICATION CHECKLIST - RUN AFTER COMPLETION

### Core Linkages Validation
- [ ] Balance Sheet Balance: `Assets - Liabilities - Equity = 0` for all projected years
- [ ] Cash Tie-Out: `CF Ending Cash - BS Cash = 0` for all projected years
- [ ] Net Income Link: `IS Net Income - CF Starting Net Income = 0`
- [ ] Retained Earnings: `Prior RE + NI - Dividends = Ending RE`
