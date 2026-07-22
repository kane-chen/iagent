# Formulas Reference 

## Essential Excel Formulas

### Statistical Functions

```excel
=AVERAGE(range)          // Simple mean
=MEDIAN(range)           // Middle value
=QUARTILE(range, 1)      // 25th percentile
=QUARTILE(range, 3)      // 75th percentile
=MAX(range)              // Maximum value
=MIN(range)              // Minimum value
=STDEV.P(range)          // Standard deviation
```
### Financial Calculations
```excel
=B7/C7                   // Simple ratio (Margin)
=SUM(B7:B9)/3            // Average of multiple companies
=IF(B7>0, C7/B7, "N/A")  // Conditional calculation
=IFERROR(C7/D7, 0)       // Handle divide by zero
```
### Cross-Sheet References
```excel
='Sheet1'!B7             // Reference another sheet
=VLOOKUP(A7, Table1, 2)  // Lookup from data table
=INDEX(MATCH())          // Advanced lookup
```

## Common Ratio Formulas
### Operating Metrics
```excel
Gross Margin = Gross Profit / Revenue
EBITDA Margin = EBITDA / Revenue
FCF Margin = Free Cash Flow / Revenue
FCF Conversion = FCF / Operating Cash Flow
ROE = Net Income / Shareholders' Equity
ROA = Net Income / Total Assets
Asset Turnover = Revenue / Total Assets
Debt/Equity = Total Debt / Shareholders' Equity
```
### Valuation Multiples
```excel
EV/Revenue = Enterprise Value / LTM Revenue
EV/EBITDA = Enterprise Value / LTM EBITDA
P/E Ratio = Market Cap / Net Income
FCF Yield = LTM FCF / Market Cap
PEG Ratio = (P/E) / Growth Rate %
```
### Returns & Efficiency
```excel
FCF Conversion = FCF / Operating Cash Flow
ROE = Net Income / Shareholders’ Equity
ROA = Net Income / Total Assets
Asset Turnover = Revenue / Total Assets
Debt/Equity = Total Debt / Shareholders’ Equity
```
## Formatting

```excel
=TEXT(B7, "0.0%")        // Format as percentage
=TEXT(C7, "#,##0")       // Thousands separator
```