# -*- coding: utf-8 -*-
"""与现有 capiq/daloopa 格式对齐的输出结构。"""


def build_historicals_response(ticker, income, balance, cashflow, indicators) -> dict:
    return {
        "ticker": ticker,
        "historicals": {
            "income_statement": income,
            "balance_sheet": balance,
            "cash_flow": cashflow,
            "financial_indicators": indicators,
        },
        "consensus": None,  # 本地财务报表文件通常不含一致预期
        "source": "local-filings",
        "extraction_note": "Data sourced from local CSV/Excel files",
    }


def build_filings_response(ticker, extracted_reports) -> dict:
    return {
        "ticker": ticker,
        "filings": extracted_reports,
        "source": "local-filings",
        "extraction_note": (
            "Extracted from local PDF/HTML filing documents. "
            "Verify against official filings."
        ),
    }
