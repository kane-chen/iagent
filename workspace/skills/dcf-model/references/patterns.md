# DCF Model Correct Patterns & Common Mistakes Reference

> **When to read this file:** Before writing any formulas — to ensure you follow the correct structural patterns. Also read the Common Mistakes section to avoid the top 5 errors.

## Table of Contents

- [Correct Patterns](#correct-patterns)
    - [Scenario Block Selection Pattern](#scenario-block-selection-pattern)
    - [Correct Revenue Projection Pattern](#correct-revenue-projection-pattern)
    - [Correct FCF Formula Pattern](#correct-fcf-formula-pattern)
    - [Correct Cell Comment Format](#correct-cell-comment-format)
    - [Correct Assumption Table Structure](#correct-assumption-table-structure)
    - [Correct Row Planning Process](#correct-row-planning-process)
    - [Correct Sensitivity Table Implementation](#correct-sensitivity-table-implementation)
    - [Office JS Merged Cell Handling](#office-js-merged-cell-handling)
- [Common Mistakes](#common-mistakes)
    - [WRONG: Simplified Sensitivity Table Approximations](#wrong-simplified-sensitivity-table-approximations)
    - [WRONG: Missing Cell Comments](#wrong-missing-cell-comments)
    - [WRONG: Formula Row References Off](#wrong-formula-row-references-off)
    - [WRONG: Single Row for Each Assumption Across Scenarios](#wrong-single-row-for-each-assumption-across-scenarios)
    - [WRONG: No Borders](#wrong-no-borders)
    - [WRONG: Wrong Font Colors](#wrong-wrong-font-colors)
    - [WRONG: Operating Expenses Based on Gross Profit](#wrong-operating-expenses-based-on-gross-profit)
    - [Top 5 Errors Summary](#top-5-errors-summary)
- [Case Selector Implementation](#case-selector-implementation)
- [Input Requirements](#input-requirements)

---

## Correct Patterns

### Scenario Block Selection Pattern - Follow This Approach

**Assumptions are organized in separate blocks for each scenario:**

**CRITICAL STRUCTURE - Three rows per section header:**

```csv
BEAR CASE ASSUMPTIONS (section header, merge cells across)
Assumption,FY1,FY2,FY3,FY4,FY5
Revenue Growth (%),12%,10%,9%,8%,7%
EBIT Margin (%),45%,44%,43%,42%,41%

BASE CASE ASSUMPTIONS (section header, merge cells across)
Assumption,FY1,FY2,FY3,FY4,FY5
Revenue Growth (%),16%,14%,12%,10%,9%
EBIT Margin (%),48%,49%,50%,51%,52%

BULL CASE ASSUMPTIONS (section header, merge cells across)
Assumption,FY1,FY2,FY3,FY4,FY5
Revenue Growth (%),20%,18%,15%,13%,11%
EBIT Margin (%),50%,51%,52%,53%,54%
```

**Each scenario block MUST have a column header row** showing the projection years (FY2025E, FY2026E, etc.) immediately below the section title. Without this, users cannot tell which assumption value corresponds to which year.

**How to reference assumptions - Create a consolidation column:**
1. Case selector cell (e.g., B6) contains 1=Bear, 2=Base, or 3=Bull
2. Create a consolidation column with INDEX or OFFSET formulas to pull from the correct scenario block
3. Projection formulas reference the consolidation column (clean cell references)
4. Each scenario block contains full set of DCF assumptions across projection years

**Recommended consolidation column pattern (using INDEX):**
`=INDEX(B10:D10, 1, $B$6)`

**NOT this - scattered IF statements throughout:**
`=IF($B$6=1,[Bear block cell],IF($B$6=2,[Base block cell],[Bull block cell]))`

The consolidation column approach centralizes logic and makes the model easier to audit.

### Correct Revenue Projection Pattern

**Create a consolidation column with INDEX formulas, then reference it in projections:**

**Step 1 - Consolidation column for FY1 growth:**
`=INDEX([Bear FY1 growth]:[Bull FY1 growth], 1, $B$6)`

**Step 2 - Revenue projection references the consolidation column:**
`Revenue Year 1: =D29*(1+$E$10)`

Where:
- D29 = Prior year revenue
- $E$10 = Consolidation column cell for FY1 growth (contains INDEX formula)
- $B$6 = Case selector (1=Bear, 2=Base, 3=Bull)

**This approach is cleaner than embedding IF statements in every projection formula** and makes it much easier to audit which scenario assumptions are being used.

### Correct FCF Formula Pattern

**Use consolidation columns with INDEX formulas, then reference them in FCF calculations:**

**Consolidation column approach:**
```csv
Item,Formula,Reference
D&A,=E29*$E$21,$E$21 = consolidation column for D&A %
CapEx,=E29*$E$22,$E$22 = consolidation column for CapEx %
Δ NWC,=(E29-D29)*$E$23,$E$23 = consolidation column for NWC %
Unlevered FCF,=E57+E58-E60-E62,E57=NOPAT E58=D&A E60=CapEx E62=Δ NWC
```

**Each consolidation column cell contains an INDEX formula** that pulls from the appropriate scenario block based on case selector. This keeps projection formulas clean and auditable.

Before writing formulas, confirm scenario block row locations and set up consolidation columns.

### Correct Cell Comment Format

**Every hardcoded value needs this format:**

"Source: [System/Document], [Date], [Reference], [URL if applicable]"

**Examples:**
```csv
Item,Source Comment
Stock price,Source: Market data script 2025-10-12 Close price
Shares outstanding,Source: 10-K FY2024 Page 45 Note 12
Historical revenue,Source: 10-K FY2024 Page 32 Consolidated Statements
Beta,Source: Market data script 2025-10-12 5-year monthly beta
Consensus estimates,Source: Management guidance Q3 2024 earnings call
```

### Correct Assumption Table Structure

**CRITICAL: Each scenario block requires THREE structural elements:**

1. **Section header row** (merged cells): e.g., "BEAR CASE ASSUMPTIONS"
2. **Column header row** showing years - THIS IS REQUIRED, DO NOT SKIP
3. **Data rows** with assumption values

**Structure:**
```csv
BEAR CASE ASSUMPTIONS (section header - merge across columns A:G)
Assumption,FY1,FY2,FY3,FY4,FY5
Revenue Growth (%),X%,X%,X%,X%,X%
EBIT Margin (%),X%,X%,X%,X%,X%
Terminal Growth,X%,,,,
WACC,X%,,,,

BASE CASE ASSUMPTIONS (section header - merge across columns A:G)
Assumption,FY1,FY2,FY3,FY4,FY5
Revenue Growth (%),X%,X%,X%,X%,X%
EBIT Margin (%),X%,X%,X%,X%,X%
Terminal Growth,X%,,,,
WACC,X%,,,,

BULL CASE ASSUMPTIONS (section header - merge across columns A:G)
Assumption,FY1,FY2,FY3,FY4,FY5
Revenue Growth (%),X%,X%,X%,X%,X%
EBIT Margin (%),X%,X%,X%,X%,X%
Terminal Growth,X%,,,,
WACC,X%,,,,
```

**WITHOUT the column header row showing projection years (FY2025E, FY2026E, etc.), users cannot tell which assumption value corresponds to which year. This row is MANDATORY.**

**Then create a consolidation column** (typically the next column to the right) that uses INDEX formulas to pull from the selected scenario block based on the case selector. This consolidation column is what your projection formulas reference.

### Correct Row Planning Process

**1. Write ALL headers and labels FIRST:**
```csv
Row,Content
1,[Company Name] DCF Model
2,Ticker | Date | Year End
4,Case Selector
7,KEY ASSUMPTIONS
26,Assumption headers
27-31,Growth assumptions
...,...
```

**2. Write ALL section dividers and blank rows**

**3. THEN write formulas using the locked row positions**

**4. Test formulas immediately after creation**

**Think of it like construction:**
- Good: Pour foundation, then build walls (stable structure)
- Bad: Build walls, then pour foundation (walls collapse)

**Excel version:**
- Good: Add headers, then write formulas (formulas stable)
- Bad: Write formulas, then add headers (formulas break)

### Correct Sensitivity Table Implementation

**IMPORTANT**: These are NOT Excel's "Data Table" feature. These are simple grids where you write regular formulas using openpyxl. Yes, this means ~75 formulas total (3 tables × 25 cells each), but this is straightforward and required.

**Programmatic Population with Formulas:**

Each sensitivity table must be fully populated with formulas that recalculate the implied share price for each combination of assumptions. **Do not use Excel's Data Table feature** (it requires manual intervention and cannot be automated via openpyxl).

**Implementation approach - CONCRETE EXAMPLE:**

**Table Structure — 5×5 grid (ODD dimensions, base case centered):**

If the model's base WACC = 9.0% and base terminal growth = 3.0%, build the axes symmetrically around those values:

```csv
WACC vs Terminal Growth,  2.0%,  2.5%,  3.0%,  3.5%,  4.0%
              8.0%,       [fml], [fml], [fml], [fml], [fml]
              8.5%,       [fml], [fml], [fml], [fml], [fml]
              9.0%,       [fml], [fml], [★  ], [fml], [fml]   ← middle row = base WACC
              9.5%,       [fml], [fml], [fml], [fml], [fml]
             10.0%,       [fml], [fml], [fml], [fml], [fml]
                                   ↑
                          middle col = base terminal g
```

**★ = the center cell.** Its formula output MUST equal the model's actual implied share price (from the valuation summary). Apply the medium-blue fill (`#BDD7EE`) and bold font to this cell so the base case is visually anchored.

**Rule for axis values:** `axis_values = [base - 2*step, base - step, base, base + step, base + 2*step]` — symmetric around the base, odd count guarantees a center.

**Formula Pattern - Cell B88 (WACC=8.0%, Terminal Growth=2.0%):**

The formula in B88 should recalculate the implied price using:
- WACC from row header: `$A88` (8.0%)
- Terminal Growth from column header: `B$87` (2.0%)

**Recommended approach:** Reference the main DCF calculation but substitute these values.

**Example formula structure:**
`=([SUM of PV FCFs using $A88 as discount rate] + [Terminal Value using B$87 as growth rate and $A88 as WACC] - [Net Debt]) / [Shares]`

**CRITICAL - Write a formula for EVERY cell in the 5x5 grid (25 cells per table, 75 cells total).** Use openpyxl to write these formulas programmatically in a loop. Do NOT skip this step or leave placeholder text.

**Python implementation pattern:**
```python
# Pseudocode for populating sensitivity table
for row_idx, wacc_value in enumerate(wacc_range):
    for col_idx, term_growth_value in enumerate(term_growth_range):
        # Build formula that uses wacc_value and term_growth_value
        formula = f"=<DCF recalc using {wacc_value} and {term_growth_value}>"
        ws.cell(row=start_row+row_idx, column=start_col+col_idx).value = formula
```

**The sensitivity tables must work immediately when the model is opened, with no manual steps required from the user.**

### Office JS Merged Cell Handling
**⚠️ Office JS merged cell pitfall:** When building section headers with merged cells, do NOT call `.merge()` then set `.values` on the merged range — Office JS still reports the range's original dimensions and will throw `InvalidArgument: The number of rows or columns in the input array doesn't match the size or dimensions of the range`. Instead, write the value to the top-left cell alone, then merge and format the full range:

```js
// WRONG — throws InvalidArgument:
const hdr = ws.getRange("A7:H7");
hdr.merge();
hdr.values = [["MARKET DATA & KEY INPUTS"]];  // 1×1 array vs 1×8 range → fails

// CORRECT — value first on single cell, then merge + format the range:
ws.getRange("A7").values = [["MARKET DATA & KEY INPUTS"]];
const hdr = ws.getRange("A7:H7");
hdr.merge();
hdr.format.fill.color = "#1F4E79";
hdr.format.font.bold = true;
hdr.format.font.color = "#FFFFFF";
```

This applies to every merged section header in the DCF (market data, scenario blocks, cash flow projection, terminal value, valuation summary, sensitivity tables).

## Common Mistakes

### WRONG: Simplified Sensitivity Table Approximations

**Don't use linear approximations:**

```
// WRONG - Linear approximation
B97: =B88*(1+(0.096-0.116))    // Assumes linear relationship

// WRONG - Division shortcut
B105: =B88/(1+(E48-0.07))      // Doesn't recalculate full DCF
```

**Don't leave placeholder text:**
```
// WRONG - Placeholder note
"Note: Use Excel Data Table feature (Data → What-If Analysis → Data Table) to populate sensitivity tables."

// WRONG - Empty cells
[leaving cells blank because "this is complex"]
```

**Don't confuse terminology:**
- ❌ "Sensitivity tables need Excel's Data Table feature" (NO - that's a specific Excel tool we can't use)
- ✅ "Sensitivity tables are simple grids with formulas in each cell" (YES - this is what we build)

**Why these shortcuts are wrong:**
- Linear approximation formulas don't actually recalculate the DCF - they just apply simple math adjustments
- The relationships are not linear, so the results will be inaccurate
- Placeholder text requires manual user intervention
- Model is not immediately usable when delivered
- Not professional or client-ready
- Empty cells = incomplete deliverable

**Common rationalization to REJECT:**
"Writing 75+ formulas feels complex, so I'll leave a note for the user to complete it manually."

**Reality:** Writing 75 formulas is straightforward when you use a loop in Python with openpyxl. Each formula follows the same pattern - just substitute the row/column values. This is a required part of the deliverable.

**Instead:** Populate every sensitivity cell with formulas that recalculate the full DCF for that specific combination of assumptions

### WRONG: Missing Cell Comments

**Don't do this:**
- Create all hardcoded inputs without comments
- Think "I'll add them later"
- Write "TODO: add source"
- Leave blue inputs without documentation

**Why it's wrong:**
- Can't verify where data came from
- Fails xlsx skill requirements
- Not audit-ready
- Wastes time fixing later

**Instead:** Add cell comment AS EACH hardcoded value is created

### WRONG: Formula Row References Off

**Symptom:**
The FCF section references wrong assumption rows:
`D&A:  =E29*$E$34    // Should be $E$21, but referencing wrong row`
`CapEx: =E29*$E$41   // Should be $E$22, but row shifted`

**Why this happens:**
1. Formulas written first
2. Then headers inserted
3. All row references shifted
4. Now formulas point to wrong cells → #REF! errors

**Instead:** Lock row layout FIRST, then write formulas

### WRONG: Single Row for Each Assumption Across Scenarios

**Don't structure assumptions like this:**
```csv
Assumption,Bear,Base,Bull
Revenue Growth FY1,10%,13%,16%
Revenue Growth FY2,9%,12%,15%
```
This vertical layout makes it hard to see the progression across years within each scenario.

**Why it's wrong:**
- Makes it difficult to see assumptions evolving across years within each scenario
- Harder to compare scenario assumptions across full projection period
- Less intuitive for reviewing scenario logic

**Instead:**
- Create separate blocks for each scenario (Bear, Base, Bull)
- Within each block, show assumptions horizontally across projection years
- This makes each scenario's assumptions easier to review as a cohesive set

### WRONG: No Borders

**Don't deliver a model without borders:**
- No section delineation
- All cells blend together
- Hard to read and unprofessional

**Why it's wrong:**
- Not client-ready
- Difficult to navigate
- Looks amateur

**Instead:** Add borders around all major sections

### WRONG: Wrong Font Colors

**Don't do this:**
- All text is black
- Only use fill colors (no font color changes)
- Mix up which cells are blue vs black

**Why it's wrong:**
- Can't distinguish inputs from formulas
- Auditing becomes impossible
- Violates xlsx skill requirements

**Instead:** Blue text for ALL hardcoded inputs, black text for ALL formulas, green for sheet links

### WRONG: Operating Expenses Based on Gross Profit

**Don't do this:**
`S&M: =E33*0.15    // E33 = Gross Profit (WRONG)`

**Why it's wrong:**
- Operating expenses scale with revenue, not gross profit
- Produces unrealistic margin progression
- Not how businesses actually operate

**Instead:**
`S&M: =E29*0.15    // E29 = Revenue (CORRECT)`

### TOP 5 ERRORS SUMMARY

1. **Formula row references off** → Define ALL row positions BEFORE writing formulas
2. **Missing cell comments** → Add comments AS cells are created, not at end
3. **Simplified sensitivity tables** → Populate all cells with full DCF recalc formulas, not approximations
4. **Scenario block references wrong** → Ensure IF formulas pull from correct Bear/Base/Bull blocks
5. **No borders** → Add professional section borders for client-ready appearance


## Case Selector Implementation

**Three-Case Framework:**

### Bear Case
- Conservative revenue growth (low end of historical range)
- Margin compression or no expansion
- Higher WACC (risk premium increase)
- Lower terminal growth rate
- Higher CapEx assumptions

### Base Case
- Consensus or management guidance revenue growth
- Moderate margin expansion based on operating leverage
- Current market-implied WACC
- GDP-aligned terminal growth (2.5-3.0%)
- Standard CapEx assumptions

### Bull Case
- Optimistic revenue growth (high end of projections)
- Significant margin expansion
- Lower WACC (reduced risk premium)
- Higher terminal growth (3.5-5.0%)
- Reduced CapEx intensity

**Formula Implementation:**

**DO NOT use nested IF formulas scattered throughout.** Instead, create a consolidation column that uses INDEX or OFFSET formulas to pull from the appropriate scenario block.

**Recommended pattern (using INDEX):**
`=INDEX(B10:D10, 1, $B$6)` where `B10:D10` = Bear/Base/Bull values, `1` = row offset, `$B$6` = case selector cell (1, 2, or 3)

**Then reference the consolidation column** in all projections:
`Revenue Year 1: =D29*(1+$E$10)` where $E$10 is the consolidation column value for Year 1 growth.

This approach centralizes scenario logic, making the model easier to audit and maintain.


## Input Requirements

### Minimum Required Inputs
1. **Company identifier**: Ticker symbol or company name
2. **Growth assumptions**: Revenue growth rates for projection period (or "use consensus")
3. **Optional parameters**:
    - Projection period (default: 5 years)
    - Scenario cases (Bear/Base/Bull growth and margin assumptions)
    - Terminal growth rate (default: 2.5-3.0%)
    - Specific WACC inputs if not using CAPM
