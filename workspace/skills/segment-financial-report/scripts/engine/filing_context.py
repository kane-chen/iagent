# -*- coding: utf-8 -*-
"""FilingContext：报告期上下文。

独立模块以避免 pdf_layout_handler（apply() 签名需要 FilingContext）和
pdf_parser（定义 FilingContext）之间的循环引用。
"""
from __future__ import annotations

import re
from pathlib import Path
from typing import Optional


_FILING_DIR_PATTERN = re.compile(r"fil_hk_[^_]+_(\d{4})_([A-Z0-9]+)", re.IGNORECASE)

# 新布局（FinancialReportService 下载产物）文件名：
#   港股/A 股 PDF：<ticker>_<yyyy-MM-dd>_<ANNUAL|INTERIM|QUARTERLY>.pdf
#   美股 HTM：    <ticker>_<YYYYMMDD>_<10-K|10-Q|6-K|20-F>.htm
_REPORT_FILE_PATTERN = re.compile(
    r"^[^_]+_(?P<date>\d{4}-\d{2}-\d{2}|\d{8})_"
    r"(?P<type>ANNUAL|INTERIM|QUARTERLY|10-K|10-Q|6-K|20-F)\.(pdf|html?)$",
    re.IGNORECASE,
)


class FilingContext:
    """Filing period context derived from the parent directory name."""

    def __init__(self, year: int = 0, period: str = "", report_type: str = ""):
        self.year = year
        self.period = (period or "").upper()
        # 报告类型（ANNUAL/INTERIM/QUARTERLY/10-K/10-Q/6-K/20-F），用于单位兜底等推断；
        # 旧目录布局拿不到类型时为空串
        self.reportType = (report_type or "").upper()

    @staticmethod
    def empty() -> "FilingContext":
        return FilingContext(0, "")

    @staticmethod
    def parse(file_path: Optional[Path]) -> "FilingContext":
        if file_path is None:
            return FilingContext.empty()
        parent = file_path.parent
        if parent is not None:
            # 旧布局：portfolio/<TICKER>/filings/fil_hk_<code>_<year>_<period>/ 目录名带期间
            m = _FILING_DIR_PATTERN.search(parent.name)
            if m:
                return FilingContext(int(m.group(1)), m.group(2).upper())
        # 新布局：FinancialReportService 下载产物，期间编码在文件名里
        return FilingContext._from_report_file(file_path.name)

    @staticmethod
    def _from_report_file(name: str) -> "FilingContext":
        """按 FinancialReportService 下载文件名 <ticker>_<日期>_<类型>.<ext> 解析期间。

        期间归类与 futu-filing classify() 保持一致：
        年报(ANNUAL/10-K/20-F) → FY（上半年发布的年报对应上一财年）；
        中期(INTERIM) → Q2（中期报告等价 H1/Q2，公司配置 filingPeriods 用 Q2 匹配）；
        季报(QUARTERLY/10-Q/6-K) → 按发布月归到最近结束的季度。
        """
        m = _REPORT_FILE_PATTERN.match(name)
        if not m:
            return FilingContext.empty()
        date_str = m.group("date")
        try:
            year = int(date_str[0:4])
            month = int(date_str[5:7]) if "-" in date_str else int(date_str[4:6])
        except ValueError:
            return FilingContext.empty()
        rtype = m.group("type").upper()
        if rtype in ("ANNUAL", "10-K", "20-F"):
            # 年报多在次年 3-4 月发布，上半年发布归上一财年
            return FilingContext(year - 1 if month <= 6 else year, "FY", rtype)
        if rtype == "INTERIM":
            return FilingContext(year, "Q2", rtype)
        # 季报：按发布月反推最近结束的季度（与 futu-filing _infer_us_quarter 兜底一致）
        if month in (1, 2, 3):
            return FilingContext(year - 1, "Q4", rtype)
        if month in (4, 5, 6):
            return FilingContext(year, "Q1", rtype)
        if month in (7, 8, 9):
            return FilingContext(year, "Q2", rtype)
        return FilingContext(year, "Q3", rtype)

    def currentQuarter(self) -> str:
        if self.year <= 0:
            return ""
        p = self.period
        if p == "H1":
            return f"{self.year}Q2"
        if p == "H2":
            return f"{self.year}Q4"
        if p == "FY":
            return f"{self.year}Q4"
        if p in ("Q1", "Q2", "Q3", "Q4"):
            return f"{self.year}{p}"
        return f"{self.year}{p}"

    def priorQuarter(self) -> str:
        if self.year <= 0:
            return ""
        cur = self.currentQuarter()
        if len(cur) >= 6:
            tail = cur[-2:]
            return f"{self.year - 1}{tail}"
        return ""

    def currentPeriod(self) -> str:
        if self.year <= 0 or not self.period:
            return ""
        return f"{self.year}{self.period}"

    def priorPeriod(self) -> str:
        if self.year <= 0 or not self.period:
            return ""
        return f"{self.year - 1}{self.period}"

    def resolvePeriod(self, code: Optional[str]) -> str:
        if code is None or code == "":
            return ""
        if code == "CURRENT_Q":
            return self.currentQuarter()
        if code == "PRIOR_Q":
            return self.priorQuarter()
        if code == "CURRENT_P":
            return self.currentPeriod()
        if code == "PRIOR_P":
            return self.priorPeriod()
        return code  # literal value

    def __repr__(self) -> str:
        return (f"FilingContext(year={self.year}, period='{self.period}', "
                f"currentQ={self.currentQuarter()}, priorQ={self.priorQuarter()})")
