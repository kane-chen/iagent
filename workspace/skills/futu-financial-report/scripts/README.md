# Futu Financial Report Scripts

## File Descriptions

### `generate_financial_excel.py` - Main Entry Script
- **Purpose**: Generate formatted Excel financial reports from Futu API data
- **Features**:
  - Income / Balance / Cash Flow statements
  - Automatic financial ratio calculations
  - Market-specific field mappings (A/HK/US)
  - YoY decline red highlighting
  - Company name mapping for readable filenames
  - Full styling with background colors

### `get_financials_statements.py` - API Reference Copy
- **Source**: Copied from `C:\Users\chan\.qclaw\skills\futuapi\scripts\quote\get_financials_statements.py`
- **Purpose**: Reference only (not executable standalone due to dependencies)
- **Active use**: The original path in QClaw skills folder is used for actual API calls

## Dependencies
- `openpyxl` for Excel generation
- `futu-api` (included in QClaw environment)
- Python from QClaw installation is used for API calls

## Usage
```bash
python generate_financial_excel.py <stock_code> [--type income|balance|cashflow] [--num N]

# Examples:
python generate_financial_excel.py HK.00700 --type income --num 4
python generate_financial_excel.py US.BABA --type income
python generate_financial_excel.py SH.600519 --type balance
```

## Generated File Format
```
{MarketCode}_{StatementType}_{Timestamp}.xlsx
Example: HK_03690_income_20260627_185316.xlsx
```
