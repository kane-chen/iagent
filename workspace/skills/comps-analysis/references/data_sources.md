# Data Acquisition Guidelines

## Priority 1: Local Excel Extraction
- **Location**: `workspace/excels/`
- **File Pattern**: `{region}_{ticker}_{type}_{date}_{stamp}.xlsx` (e.g., `US_MSFT_income_20260720_210158.xlsx`)
- **Extraction Logic**:
    - Use `_read_excel_map` in the script to parse the file into a dictionary.
    - Identify the latest `FY` column (e.g., `2026FY`).
    - Extract line items by their exact Chinese/English names (e.g., "总收入" for Revenue, "营业利润" for EBIT, "净利润" for Net Income).
- **Annotation Rule**: If data is found here, the cell comment must be: `"Source: Local file {filename}, Row '{indicator}'"`.

## Priority 2: Web Search (Missing Data)
- **Trigger**: If `extract_company_data` returns `None` for a required field, or if the field is a market metric (Market Cap, Stock Price) not found in historical financial statements.
- **Target Sources**:
    1. **Eastmoney (东方财富)**: `https://emweb.securities.eastmoney.com/PC_HSF10/NewFinanceAnalysis/Index?type=web&code={ticker}`
    2. **Futu (富途)**: `https://www.futunn.com/stock/{ticker}-{market}`
    3. **Xueqiu (雪球)**: `https://xueqiu.com/S/{ticker}`
    4. **Yahoo Finance**: `https://finance.yahoo.com/quote/{ticker}`
- **Extraction Logic**:
    - Claude executes `web_search` with queries like `"{ticker} Market Cap site:futunn.com"` or `"{ticker} 最新市值"`.
    - Parse the search results to find the numerical value.
- **Annotation Rule**: If data is found via web search, the cell comment must be: `"Source: {Website Name} - {Metric Name}, accessed {YYYY-MM-DD}"`.

## Handling Missing Data
- If a data point cannot be found via local files or web search, leave the cell as `0` (or `N/A` if appropriate).
- The cell MUST contain a comment stating: `"ERROR: Data not found. Manual input required."`
- Downstream formulas use `IFERROR` to prevent `#DIV/0!` errors when inputs are zero.