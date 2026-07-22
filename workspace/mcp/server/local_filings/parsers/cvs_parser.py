# -*- coding: utf-8 -*-
"""兼容性 shim——cvs_parser 已重命名为 csv_parser。"""
import warnings
warnings.warn("cvs_parser 已重命名为 csv_parser", DeprecationWarning, stacklevel=2)
from .csv_parser import parse_financials_csv  # noqa: F401