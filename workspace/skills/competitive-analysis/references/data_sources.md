# Data Acquisition Guidelines

## Priority 1: User Parameter Input
- If the user provides explicit arguments (e.g., `--revenue 5000` or specific numbers in the prompt), these values are absolute and override all other sources.
- Do not recalculate or round user-provided inputs.

## Priority 2: Local Excel Extraction
- **Location**: `workspace/excels/`
- **File Pattern**: `{region}_{ticker}_{type}_{date}_{stamp}.xlsx`
- **Extraction Logic**:
    - Parse the file into a dictionary.
    - Identify the latest `FY` column.
    - Extract line items by exact names (e.g., "Revenue", "营业利润").
- **Annotation Rule**: `"Source: Local file {filename}, Row '{indicator}'"`

## Priority 3: Web Search (Missing Data)
- **Trigger**: Data not found in user inputs or local files.
- **Target Sources**:
    1. **Eastmoney (东方财富)**: `https://emweb.securities.eastmoney.com/...`
    2. **Futu (富途)**: `https://www.futunn.com/stock/{ticker}`
    3. **Xueqiu (雪球)**: `https://xueqiu.com/S/{ticker}`
- **Extraction Logic**:
    - Execute `web_search` targeting these domains.
    - Parse HTML/results for numerical values.
- **Annotation Rule**: `"Source: {Website Name} - {Metric Name}, accessed {YYYY-MM-DD}"`

## Handling Missing Data
- If data cannot be found, leave cell as "TODO: Manual Input".
- Highlight missing data cells in red font for easy identification.
