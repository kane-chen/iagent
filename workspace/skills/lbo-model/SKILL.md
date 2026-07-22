---
name: lbo-model
description: This skill should be used when completing LBO (Leveraged Buyout) model templates in Excel for private equity transactions, deal materials, or investment committee presentations. The skill extracts financial data, fills in formulas, validates calculations, and ensures professional formatting standards.
---

## CRITICAL INSTRUCTIONS - READ FIRST

### Environment & Execution

This skill generates standalone `.xlsx` files using Python (`openpyxl`).
- Execute the script: `python scripts/build_lbo_model.py --ticker BABA --workspace /path/to/workspace --entry_multiple 10.0 --exit_multiple 10.0`
- The script automatically extracts financial data from `workspace/excels/` and builds a 4-sheet LBO model.

### Core Principles
* **Every calculation must be an Excel formula** - NEVER compute values in Python and hardcode results into cells. When using openpyxl, write `cell.value = "=B5*B6"` (formula string), NOT `cell.value = 1250` (computed result). The model must be dynamic and update when inputs change.
* **Use proper cell references** - All formulas should reference the appropriate cells. Never type numbers that should come from other cells.
* **Maintain sign convention consistency** - Follow whatever sign convention the template uses (some use negative for outflows, some use positive). Be consistent throughout.
* **Handle missing data gracefully** - If D&A or Long-Term Debt is missing from the source Excel files, estimate them using standard accounting proxies (e.g., D&A ≈ 70% of CapEx, or change in accumulated depreciation).

### Formula Color Conventions
* **Blue (0000FF)**: Hardcoded inputs - typed numbers that don't reference other cells
* **Black (000000)**: Formulas with calculations - any formula using operators or functions (`=B4*B5`, `=SUM()`, `=-MAX(0,B4)`)
* **Purple (800080)**: Links to cells on the **same tab** - direct references with no calculation (`=B9`, `=B45`)
* **Green (008000)**: Links to cells on **different tabs** - cross-sheet references (`=Assumptions!B5`, `='Operating Model'!C10`)

### Fill Color Palette — Professional Blues & Greys
* **Section headers** (Sources & Uses, Operating Model, etc.): Dark blue `#1F4E79` with white bold text
* **Column headers** (Year 1, Year 2, etc.): Light blue `#D9E1F2` with black bold text
* **Input cells**: Light grey `#F2F2F2` (or just white) — the blue *font* is the signal, fill is secondary
* **Formula/calculated cells**: White, no fill
* **Key outputs** (IRR, MOIC, Exit Equity): Medium blue `#BDD7EE` with black bold text


### Number Formatting Standards
* **Currency**: `$#,##0;($#,##0);"-"` or `$#,##0.0` depending on template
* **Percentages**: `0.0%` (one decimal)
* **Multiples**: `0.0"x"` (one decimal)
* **MOIC/Detailed Ratios**: `0.00"x"` (two decimals for precision)
* **All numeric cells**: Right-aligned

### SCRIPT-DRIVEN BUILD
The `build_lbo_model.py` script handles the entire workflow:
1. **Data Retrieval**: Finds `{ticker}_{type}_*.xlsx` in the excels directory. 
2. **Extraction**: Parses Income Statement, Balance Sheet, and Cash Flow Statement. Handles missing `sharedStrings.xml` errors robustly. 
3. **Assumptions**: Generates transaction assumptions (Entry/Exit multiples, Debt %, Interest Rate, Tax Rate). 
4. **Model Construction**: Builds 4 sheets:
   - **Sources & Uses**: Balances transaction structure with Sponsor Equity as the plug.
   - **Operating Model**: 5-year projection of Revenue, EBITDA, D&A, EBIT, and Net Income.
   - **Debt Schedule**: Roll-forward of transaction debt with beginning balance, interest, and repayment.
   - **Returns**: Calculates Exit EV, Exit Equity, MOIC, and IRR using live formulas.

### Data Extraction Rules
- **Revenue**: Extracts “总收入” or “营业总收入”.
- **EBITDA**: Uses “营业利润” (EBIT) as a proxy, or calculates from Cash Flow if D&A is available.
- **Debt**: Extracts “短期借款与融资租赁负债” + “长期借款” (if available).
- **Cash**: Extracts “现金及现金等价物和短期投资”.
- **D&A**: Extracts from Cash Flow Statement if available; otherwise, estimates as 70% of CapEx.

## VERIFICATION CHECKLIST - RUN AFTER COMPLETION

### Section Balancing
- [ ] Any sections that must balance (Sources/Uses, Assets/Liabilities) balance exactly
- [ ] Plug items are calculated correctly as the balancing figure
- [ ] Amounts that should match across sections are consistent

### Formatting
- [ ] Hardcoded inputs are blue (0000FF)
- [ ] Calculated formulas are black (000000)
- [ ] Same-tab links are purple (800080)
- [ ] Cross-tab links are green (008000)
- [ ] All numbers are right-aligned
- [ ] Appropriate number formats applied throughout
- [ ] No cells show error values (#REF!, #DIV/0!, #VALUE!, #NAME?)
