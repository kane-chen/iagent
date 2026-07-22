# DCF Model Formatting Standards Reference

> **When to read this file:** When applying visual formatting to a DCF Excel model — colors, borders, number formats, and cell comments.

## Table of Contents

- [Color Scheme — Two Layers](#color-scheme--two-layers)
    - [Layer 1: Font Colors (MANDATORY)](#layer-1-font-colors-mandatory)
    - [Layer 2: Fill Colors — Professional Blue/Grey Palette](#layer-2-fill-colors--professional-bluegrey-palette)
    - [How the Layers Work Together](#how-the-layers-work-together)
- [Border Standards (REQUIRED)](#border-standards-required)
- [Number Formats](#number-formats)
- [Cell Comments (MANDATORY)](#cell-comments-mandatory)
- [Formula Recalculation (MANDATORY)](#formula-recalculation-mandatory)
- [Quality Rubric](#quality-rubric)

---

## Color Scheme — Two Layers

### Layer 1: Font Colors (MANDATORY)

- **Blue text (RGB: 0,0,255)**: ALL hardcoded inputs (stock price, shares, historical data, assumptions)
- **Black text (RGB: 0,0,0)**: ALL formulas and calculations
- **Green text (RGB: 0,128,0)**: Links to other sheets (WACC sheet references)

### Layer 2: Fill Colors — Professional Blue/Grey Palette

**Keep it minimal** — use only blues and greys for fills. Do NOT introduce greens, yellows, oranges, or multiple accent colors. A model with too many colors looks amateurish.

**Default fill palette:**
- **Section headers**: Dark blue (RGB: 31,78,121 / `#1F4E79`) background with white bold text
- **Sub-headers/column headers**: Light blue (RGB: 217,225,242 / `#D9E1F2`) background with black bold text
- **Input cells**: Light grey (RGB: 242,242,242 / `#F2F2F2`) background with blue font — or just white with blue font if you want maximum minimalism
- **Calculated cells**: White background with black font
- **Output/summary rows** (per-share value, EV, etc.): Medium blue (RGB: 189,215,238 / `#BDD7EE`) background with black bold font

**That's it — 3 blues + 1 grey + white.** Resist the urge to add more.

User-provided templates or explicit color preferences ALWAYS override these defaults.

### How the Layers Work Together

- Input cell: Blue font + light grey fill = "Hardcoded input"
- Formula cell: Black font + white background = "Calculated value"
- Sheet link: Green font + white background = "Reference from another sheet"
- Key output: Black bold font + medium blue fill = "This is the answer"

**Font color tells you WHAT it is (input/formula/link). Fill color tells you WHERE you are (header/data/output).**

---

## Border Standards (REQUIRED)

Professional appearance requires proper bordering. Models without borders are not client-ready.

**Thick borders** (1.5pt) around major sections:
- KEY INPUTS section
- PROJECTION ASSUMPTIONS section
- 5-YEAR CASH FLOW PROJECTION section
- TERMINAL VALUE section
- VALUATION SUMMARY section
- Each SENSITIVITY ANALYSIS table

**Medium borders** (1pt) between sub-sections:
- Company Details vs Historical Performance
- Growth Assumptions vs EBIT Margin vs FCF Parameters

**Thin borders** (0.5pt) around data tables:
- Scenario assumption tables (Bear | Base | Bull | Selected)
- Historical vs projected financials matrix

**No borders:** Individual cells within tables (keep clean, scannable)

**Borders are mandatory** - models without professional borders are not client-ready.

---

## Number Formats

Follows xlsx skill standards:

- **Years**: Format as text strings (e.g., "2024" not "2,024")
- **Percentages**: `0.0%` (one decimal place)
- **Currency**: `$#,##0` for millions; `$#,##0.00` for per-share - ALWAYS specify units in headers ("Revenue ($mm)")
- **Zeros**: Use number formatting to make all zeros "-" (e.g., `$#,##0;($#,##0);-`)
- **Large numbers**: `#,##0` with thousands separator
- **Negative numbers**: `(#,##0)` in parentheses (NOT minus sign)

---

## Cell Comments (MANDATORY)

Per the xlsx skill, ALL hardcoded values must have cell comments documenting the source.

**Format:** `"Source: [System/Document], [Date], [Reference], [URL if applicable]"`

**Examples:**

```csv
Item,Source Comment
Stock price,Source: Market data script 2025-10-12 Close price
Shares outstanding,Source: 10-K FY2024 Page 45 Note 12
Historical revenue,Source: 10-K FY2024 Page 32 Consolidated Statements
Beta,Source: Market data script 2025-10-12 5-year monthly beta
Consensus estimates,Source: Management guidance Q3 2024 earnings call
```
**CRITICAL**: Add comments AS CELLS ARE CREATED. Do not defer to the end.


## Formula Recalculation (MANDATORY)

After creating or modifying the Excel model, **recalculate all formulas** using the recalc.py script from the xlsx skill:

```bash
python recalc.py [path_to_excel_file] [timeout_seconds]
```

Example:
```bash
python recalc.py AAPL_DCF_Model_2025-10-12.xlsx 30
```

The script will:
- Recalculate all formulas in all sheets using LibreOffice
- Scan ALL cells for Excel errors (#REF!, #DIV/0!, #VALUE!, #NAME?, #NULL!, #NUM!, #N/A)
- Return detailed JSON with error locations and counts

**Expected output format:**
```json
{
  "status": "success",           // or "errors_found"
  "total_errors": 0,              // Total error count
  "total_formulas": 42,           // Number of formulas in file
  "error_summary": {}             // Only present if errors found
}
```

**If errors are found**, the output will include details:
```json
{
  "status": "errors_found",
  "total_errors": 2,
  "total_formulas": 42,
  "error_summary": {
    "#REF!": {
      "count": 2,
      "locations": ["DCF!B25", "DCF!C25"]
    }
  }
}
```

**Fix all errors** and re-run recalc.py until status is "success" before delivering the model.

## Quality Rubric

Every DCF model must maximize for:
1. **Realistic revenue and margin assumptions** based on historical performance
2. **Appropriate cost of capital calculation** with proper CAPM methodology
3. **Comprehensive sensitivity analysis** showing valuation ranges
4. **Clear terminal value calculation** with supporting rationale
5. **Professional model structure** enabling scenario analysis
6. **Transparent documentation** of all key assumptions