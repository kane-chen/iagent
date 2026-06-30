#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
流式获取并生成财务报表Excel（支持三种报表类型 + 全市场适配 + 财务比率计算）

支持报表：
- 利润表 (statement_type=1) - 含毛利率/费用率/利润率等衍生指标
- 资产负债表 (statement_type=2)
- 现金流量表 (statement_type=3)

自动适配市场：
- A股 (SH./SZ.) -> field_id: 3xxx
- 美股/港股 (US./HK.) -> field_id: 8xxx

核心特性：
- 全程内存流式处理，无中间文件
- stdout 极简输出，最小化 token 消耗
- 动态字段映射，自动适配不同市场指标名称
- 财务比率自动计算（毛利率/费用占比/营业利润率/净利润率）
- YoY同比下降超过5%红色高亮显示

用法：
    python generate_financial_excel.py <stock_code> [--type T] [--num N] [--output <path>]

示例：
    python generate_financial_excel.py US.BABA --type income
    python generate_financial_excel.py HK.00700 --type balance
    python generate_financial_excel.py SH.600519 --type cashflow --num 20
"""

import json
import subprocess
import sys
import os
import re
import argparse
import logging
from datetime import datetime

try:
    from openpyxl import Workbook
    from openpyxl.styles import Font, PatternFill, Alignment, Border, Side
    from openpyxl.utils import get_column_letter
    HAS_OPENPYXL = True
except ImportError:
    HAS_OPENPYXL = False

# ─────────────────────────────────────────────────────────────
# 路径配置（使用系统 Python 和 workspace 中的 futuapi）
# ─────────────────────────────────────────────────────────────

# 使用系统 Python
PYTHON_EXE = sys.executable

# futu API 脚本路径（workspace 中的 futuapi 副本）
skill_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FUTUAPI_SCRIPT = os.path.join(skill_dir, "..", "futuapi", "scripts", "quote", "get_financials_statements.py")
FUTUAPI_SCRIPT = os.path.normpath(FUTUAPI_SCRIPT)


# ─────────────────────────────────────────────────────────────
# 报表类型配置
# ─────────────────────────────────────────────────────────────

STATEMENT_TYPES = {
    'income':      (1, '利润表'),
    'balance':     (2, '资产负债表'),
    'cashflow':    (3, '现金流量表'),
}

# 颜色配置
COLORS = {
    'header':   'D9E1F2',      # 表头-浅蓝
    'revenue':  'DEEAF6',      # 收入-浅蓝
    'expense':  'FCE4D6',      # 费用-浅红
    'profit':   'E2EFDA',      # 利润-浅绿
    'asset':    'DBEEF3',      # 资产-浅蓝
    'liability': 'FCE4D6',     # 负债-浅红
    'equity':   'E2EFDA',      # 权益-浅绿
    'cash_in':  'C6EFCE',      # 现金流入-浅绿
    'cash_out': 'FCE4D6',      # 现金流出-浅红
    'core_ratio': '9BBB59',    # 核心利润率(毛利率/营业利润率/净利润率)-橄榄绿
    'expense_ratio': 'D8E4BC', # 费用率(营业/销售/管理/研发费用率)-豆绿色
    'ratio':    'FFF2CC',      # 其他一般比率-高亮黄
    'yoy':      None,          # YoY-无特殊背景
    'tax':      'FCE4D6',      # 税-浅红
    'default':  None,
}

# YoY阈值配置
YOY_DECLINE_THRESHOLD = -5.0   # 下降超过此阈值红色高亮
YOY_GROWTH_THRESHOLD = 30.0    # 增长超过此阈值绿色高亮

# ─────────────────────────────────────────────────────────────
# 财务比率配置 (分子field_id, 分母field_id, 基础名称, 是否紧跟父项后)
# ─────────────────────────────────────────────────────────────

# A股利润表比率配置 - (名称后缀, 分子field_id, 分母field_id, 父项field_id)
A_SHARE_RATIO_CONFIG = {
    'income': [
        ('毛利率', 3032-27, 3001, 3009),  # A股毛利=营收-成本，插入成本之后
        ('营业利润率', 3032, 3001, 3032),
        ('净利润率', 3043, 3001, 3043),
    ],
    'core_ratios': ['毛利率', '营业利润率', '净利润率'],  # 核心利润率，特殊着色
    'expense_ratios': [
        # (比率名称, 费用field_id, 收入field_id)
        ('营业费用率', 3005, 3001),  # 营业费用占比
        ('销售费用率', 3013, 3001),
        ('管理费用率', 3014, 3001),
        ('研发费用率', 3015, 3001),
    ],
}

# 美股利润表比率配置
US_RATIO_CONFIG = {
    'income': [
        ('毛利率', 8004, 8001, 8004),        # 毛利/总收入
        ('营业利润率', 8017, 8001, 8017),     # 营业利润/总收入
        ('净利润率', 8043, 8001, 8043),       # 归属于母公司净利润/总收入
    ],
    'core_ratios': ['毛利率', '营业利润率', '净利润率'],  # 核心利润率，特殊着色
    'expense_ratios': [
        # (比率名称, 费用field_id, 收入field_id)
        ('营业费用率', 8005, 8001),           # 营业费用占比
        ('销售和管理费用率', 8007, 8001),
        ('销售费用率', 8008, 8001),
        ('管理费用率', 8009, 8001),
        ('研发费用率', 8010, 8001),
    ],
}

# 港股利润表比率配置
# HK_RATIO_CONFIG update
HK_RATIO_CONFIG = {
    'income': [
        ('毛利率', 5010, 5001, 5010),        # 毛利/总收入
        ('营业利润率', 5034, 5001, 5034),     # 营业利润/总收入
        ('净利润率', 5051, 5001, 5051),       # 归属于母公司净利润/总收入
    ],
    'core_ratios': ['毛利率', '营业利润率', '净利润率'],  # 核心利润率，特殊着色
    'expense_ratios': [
        # (比率名称, 费用field_id, 收入field_id)
        ('销售成本率', 5008, 5001),           # 销售成本占比
        ('营业费用率', 5013, 5001),           # 营业费用占比
        ('销售及分销费用率', 5015, 5001),     # 销售及分销费用占比
        ('管理费用率', 5016, 5001),           # 管理费用占比
        ('研发费用率', 5017, 5001),           # 研发费用占比
    ],
}

# ─────────────────────────────────────────────────────────────
# 不同市场的核心字段映射 (市场前缀 -> {报表类型 -> 字段配置})
# ─────────────────────────────────────────────────────────────

# A股 (3xxx field_id)
A_SHARE_FIELDS = {
    'income': [
        (3001, '营业总收入',       'revenue'),
        (3009, '营业总成本',        'expense'),
        # 插入：毛利率 (由 (营收-成本)/营收 计算)
        (3013, '销售费用',        'expense'),
        (3014, '管理费用',        'expense'),
        (3015, '研发费用',        'expense'),
        (3032, '营业利润',        'profit'),
        # 插入：营业利润率
        (3038, '利润总额',        'profit'),
        (3039, '所得税费用',       'tax'),
        (3043, '净利润',         'profit'),
        # 插入：净利润率
        (3047, '归属母公司净利润',  'profit'),
    ],
    'balance': [
        # 资产
        (3001, '资产合计',       'asset'),
        (3002, '流动资产合计',     'asset'),
        (3003, '货币资金',        'asset'),
        (3004, '结算备付金',      'asset'),
        (3005, '拆出资金',        'asset'),
        (3006, '交易性金融资产',   'asset'),
        (3007, '衍生金融资产',     'asset'),
        (3008, '应收票据',        'asset'),
        (3009, '应收账款',        'asset'),
        (3010, '应收款项融资',     'asset'),
        (3011, '预付款项',        'asset'),
        (3012, '应收保费',        'asset'),
        (3013, '应收分保账款',    'asset'),
        (3014, '应收分保合同准备金', 'asset'),
        (3015, '其他应收款',      'asset'),
        (3016, '存货',          'asset'),
        (3017, '合同资产',        'asset'),
        (3018, '持有待售资产',    'asset'),
        (3019, '一年内到期的非流动资产', 'asset'),
        (3020, '其他流动资产',    'asset'),
        (3025, '非流动资产合计',   'asset'),
        (3026, '固定资产合计',     'asset'),
        (3027, '在建工程',        'asset'),
        (3028, '工程物资',        'asset'),
        (3029, '固定资产清理',    'asset'),
        (3030, '无形资产',        'asset'),
        (3031, '开发支出',        'asset'),
        (3032, '商誉',          'asset'),
        (3033, '长期待摊费用',    'asset'),
        (3034, '递延所得税资产',   'asset'),
        (3035, '其他非流动资产',  'asset'),
        # 负债
        (3055, '负债合计',       'liability'),
        (3056, '流动负债合计',    'liability'),
        (3057, '短期借款',        'liability'),
        (3058, '交易性金融负债',   'liability'),
        (3059, '衍生金融负债',     'liability'),
        (3060, '应付票据',        'liability'),
        (3061, '应付账款',        'liability'),
        (3062, '预收款项',        'liability'),
        (3063, '合同负债',        'liability'),
        (3064, '应付职工薪酬',    'liability'),
        (3065, '应交税费',        'liability'),
        (3066, '其他应付款',      'liability'),
        (3067, '一年内到期的非流动负债', 'liability'),
        (3079, '非流动负债合计',   'liability'),
        (3080, '长期借款',        'liability'),
        (3081, '应付债券',        'liability'),
        (3082, '长期应付款',      'liability'),
        (3083, '预计负债',        'liability'),
        (3084, '递延所得税负债',   'liability'),
        (3085, '递延收益',        'liability'),
        (3086, '其他非流动负债',  'liability'),
        # 权益
        (3097, '股东权益合计',     'equity'),
        (3098, '归属母公司所有者权益合计', 'equity'),
        (3099, '实收资本',        'equity'),
        (3100, '资本公积',        'equity'),
        (3101, '盈余公积',        'equity'),
        (3102, '专项储备',        'equity'),
        (3103, '未分配利润',      'equity'),
    ],
    'cashflow': [
        (3001, '经营活动现金流量净额',  'cash_in'),
        (3033, '投资活动现金流量净额',  'cash_out'),
        (3051, '融资活动现金流量净额',  'cash_out'),
        (3068, '期末现金及现金等价物余额', 'cash_in'),
    ],
}

# 美股 (8xxx field_id)
US_FIELDS = {
    'income': [
        (8001, '总收入',         'revenue'),    # 部分公司（如 AAPL）有此字段
        (8002, '营业总收入',      'revenue'),    # 部分公司（如 FUTU）用此字段作为收入
        (8003, '营业总成本',      'expense'),
        (8004, '毛利',           'profit'),
        (8005, '营业费用',        'expense'),
        (8017, '营业利润',        'profit'),
        (8034, '税前利润',        'profit'),
        (8035, '所得税',          'tax'),
        (8037, '净利润',         'profit'),
        (8043, '归属于母公司股东净利润', 'profit'),
    ],
    'balance': [
        (8001, '资产合计',       'asset'),
        (8002, '流动资产合计',     'asset'),
        (8003, '现金及现金等价物',  'asset'),
        (8004, '短期投资',        'asset'),
        (8005, '净应收账款',      'asset'),
        (8006, '应收票据',        'asset'),
        (8007, '其他应收款',      'asset'),
        (8008, '存货',          'asset'),
        (8009, '预付费用',        'asset'),
        (8010, '其他流动资产',    'asset'),
        (8022, '非流动资产合计',   'asset'),
        (8023, '长期投资',        'asset'),
        (8024, '固定资产净额',    'asset'),
        (8025, '土地和改进',      'asset'),
        (8026, '建筑物和改进',    'asset'),
        (8027, '设备',          'asset'),
        (8028, '在建工程',        'asset'),
        (8029, '租赁资产',        'asset'),
        (8030, '无形资产',        'asset'),
        (8031, '商誉',          'asset'),
        (8032, '长期待摊费用',    'asset'),
        (8033, '递延所得税资产',   'asset'),
        (8034, '其他非流动资产',  'asset'),
        (8055, '负债合计',       'liability'),
        (8056, '流动负债合计',    'liability'),
        (8057, '短期借款',        'liability'),
        (8058, '应付账款',        'liability'),
        (8059, '应付票据',        'liability'),
        (8060, '应计费用',        'liability'),
        (8061, '应付所得税',      'liability'),
        (8062, '其他流动负债',    'liability'),
        (8083, '非流动负债合计',   'liability'),
        (8084, '长期借款',        'liability'),
        (8085, '应付债券',        'liability'),
        (8086, '递延所得税负债',   'liability'),
        (8087, '其他非流动负债',  'liability'),
        (8100, '股东权益合计',     'equity'),
        (8101, '归属母公司所有者权益合计', 'equity'),
        (8102, '股本',          'equity'),
        (8103, '资本公积',        'equity'),
        (8104, '留存收益',        'equity'),
        (8105, '其他综合收益',    'equity'),
        (8106, '库藏股',        'equity'),
    ],
    'cashflow': [
        (8015, '经营活动现金流量净额',    'cash_in'),
        (8042, '投资活动现金流量净额',    'cash_out'),
        (8056, '融资活动现金流量净额',    'cash_out'),
        (8067, '现金及现金等价物期末余额', 'cash_in'),
        (8068, '现金及现金等价物净增加额', 'cash_in'),
        (8072, '自由现金流',             'cash_in'),
    ],
}

# 港股 (5xxx field_id) - 基于美团/腾讯实际字段
HK_FIELDS = {
    'income': [
        (5001, '营业总收入',       'revenue'),      # Operating revenue
        (5005, '营业总成本',        'expense'),      # Total operating costs
        (5008, '销售成本',        'expense'),      # Cost of sales
        (5010, '毛利',           'profit'),       # Gross profit
        (5013, '营业费用',        'expense'),      # Operating expenses
        (5015, '销售及分销成本',    'expense'),      # Selling and distribution
        (5016, '行政开支',        'expense'),      # Administrative expenses - 管理费用
        (5017, '研发费用',        'expense'),      # R&D costs
        (5034, '营业利润',        'profit'),       # Operating profit
        (5035, '财务收入',        'profit'),       # Finance income
        (5036, '财务成本',        'expense'),      # Finance costs
        (5037, '应占联营公司盈利', 'profit'),       # Share of profit of associates
        (5040, '税前利润',        'profit'),       # Profit before taxation
        (5043, '所得税',          'tax'),          # Income tax
        (5045, '本年利润',        'profit'),       # Profit/Loss for the year
        (5051, '归属于母公司股东净利润', 'profit'), # Profit for equity shareholders
    ],
    'balance': [
        (5001, '资产合计',       'asset'),
        (5002, '流动资产合计',     'asset'),
        (5003, '现金及现金等价物',  'asset'),
        (5004, '受限制现金',      'asset'),
        (5005, '短期投资',        'asset'),
        (5006, '应收账款',        'asset'),
        (5007, '其他应收款',      'asset'),
        (5008, '预付款项及按金',  'asset'),
        (5009, '存货',          'asset'),
        (5010, '金融资产',        'asset'),
        (5011, '其他流动资产',    'asset'),
        (5029, '非流动资产合计',   'asset'),
        (5030, '物业、厂房及设备', 'asset'),
        (5031, '在建工程',        'asset'),
        (5032, '土地使用权',      'asset'),
        (5033, '投资物业',        'asset'),
        (5034, '无形资产',        'asset'),
        (5035, '商誉',          'asset'),
        (5036, '长期投资',        'asset'),
        (5037, '递延所得税资产',   'asset'),
        (5038, '其他非流动资产',  'asset'),
        (5060, '负债合计',       'liability'),
        (5061, '流动负债合计',    'liability'),
        (5062, '短期借款',        'liability'),
        (5063, '应付账款',        'liability'),
        (5064, '应付票据',        'liability'),
        (5065, '其他应付款项',    'liability'),
        (5066, '合同负债',        'liability'),
        (5067, '应计费用',        'liability'),
        (5068, '应交税费',        'liability'),
        (5069, '一年内到期的长期负债', 'liability'),
        (5070, '其他流动负债',    'liability'),
        (5088, '非流动负债合计',   'liability'),
        (5089, '长期借款',        'liability'),
        (5090, '应付债券',        'liability'),
        (5091, '租赁负债',        'liability'),
        (5092, '递延所得税负债',   'liability'),
        (5093, '其他非流动负债',  'liability'),
        (5109, '股东权益合计',     'equity'),
        (5110, '归属于母公司股东权益合计', 'equity'),
        (5111, '股本',          'equity'),
        (5112, '储备',          'equity'),
        (5113, '留存收益',        'equity'),
        (5114, '其他权益',        'equity'),
    ],
    'cashflow': [
        (5058, '经营活动现金流量净额',    'cash_in'),
        (5076, '投资活动现金流量净额',    'cash_out'),
        (5086, '融资活动现金流量净额',    'cash_out'),
        (5100, '现金及现金等价物期末余额', 'cash_in'),
        (5101, '现金及现金等价物净增加额', 'cash_in'),
    ],
}

# 费用细分类目 (仅利润表) - (field_id: (名称, 是否取绝对值))
EXPENSE_ITEMS_MAP = {
    'us': {
        8007: ('销售和管理费用', False),
        8008: ('销售费用',       True),
        8009: ('管理费用',       True),
        8010: ('研发费用',       False),
    },
    'hk': {
        5015: ('销售及分销成本', False),
        5016: ('行政开支', False),
        5017: ('研发费用', False),
    },
    'a_share': {
        3013: ('销售费用',       False),
        3014: ('管理费用',       False),
        3015: ('研发费用',       False),
    },
}

# ─────────────────────────────────────────────────────────────
# 辅助函数
# ─────────────────────────────────────────────────────────────

def setup_logging(log_file):
    """设置日志系统"""
    if not os.path.exists(os.path.dirname(log_file)):
        os.makedirs(os.path.dirname(log_file))

    logging.basicConfig(
        level=logging.DEBUG,
        format='%(asctime)s - %(levelname)s - %(message)s',
        filename=log_file,
        filemode='w',
        encoding='utf-8'
    )
    return logging.getLogger(__name__)


def log_stdout(msg):
    """输出到 stdout（极简）"""
    print(msg, flush=True)


def get_market_type(stock_code):
    """判断市场类型：a_share / hk / us / hk_us

    - A股 (SH./SZ.): 3xxx field_id
    - 港股 (HK.): 5xxx field_id
    - 美股 (US.): 8xxx field_id
    """
    if stock_code.startswith('SH.') or stock_code.startswith('SZ.'):
        return 'a_share'
    elif stock_code.startswith('HK.'):
        return 'hk'
    elif stock_code.startswith('US.'):
        return 'us'
    return 'hk_us'


def normalize_stock_code(stock_code):
    """标准化股票代码格式：
    - 港股（HK.）：代码补零到5位，如 HK.700 → HK.00700
    - A股（SH./SZ.）：代码补零到6位，如 SH.600 → SH.000600
    - 美股（US.）：保持原样
    """
    parts = stock_code.split('.', 1)
    if len(parts) != 2:
        return stock_code

    market, code = parts
    market_upper = market.upper()

    if market_upper == 'HK':
        # 港股5位数字
        code = code.zfill(5)
    elif market_upper in ['SH', 'SZ']:
        # A股6位数字
        code = code.zfill(6)

    return f"{market_upper}.{code}"


def get_unit_config(reports):
    """从财报数据中获取币种单位名称和百万转换除数

    Args:
        reports: API 返回的财报列表
    Returns:
        unit_name: 单位名称（如"百万人民币"、"百万港元"、"百万美元"等）
        divisor: 转换除数
    """
    # 币种代码到显示名称的映射
    currency_mapping = {
        'CNY': '百万人民币',
        'RMB': '百万人民币',
        'HKD': '百万港元',
        'HK$': '百万港元',
        'USD': '百万美元',
        'US$': '百万美元',
        'EUR': '百万欧元',
        'GBP': '百万英镑',
        'JPY': '百万日元',
        'SGD': '百万新加坡元',
        'AUD': '百万澳元',
        'CAD': '百万加元',
    }

    currency_code = None
    currency_info = None

    # 从第一条有币种信息的报表中获取
    if reports:
        for report in reports:
            currency_code = report.get('currency_code')
            currency_info = report.get('currency_info')
            if currency_code or currency_info:
                break

    # 优先使用 currency_code 映射，其次使用 currency_info
    if currency_code and currency_code in currency_mapping:
        unit_name = currency_mapping[currency_code]
    elif currency_info:
        # 使用返回的 currency_info，添加"百万"前缀
        unit_name = f"百万{currency_info}"
    else:
        # 默认兜底
        unit_name = "百万"

    # 所有币种原始单位都是元，转换到百万单位
    divisor = 1000000

    return unit_name, divisor, currency_code, currency_info


def get_financial_data_with_structure(stock_code, statement_type, num=16, logger=None):
    """从富途API获取财务数据及字段结构

    根据报表类型选择合适的 financial_type：
    - 利润表：9 = 单季报组合（Q1/Q2/Q3/Q4）
    - 资产负债表/现金流量表：7 = 年报
    """
    st_code = STATEMENT_TYPES[statement_type][0]

    # 资产负债表和现金流量表通常使用年报数据
    if statement_type in ['balance', 'cashflow']:
        financial_type = 7  # 年报
    else:
        # 利润表使用 10 = 全部报告（年报+半年报+季报），港股部分公司只提供年报和半年报
        financial_type = 10  # 全部报告

    if logger:
        msg = {7: '年报', 9: '单季报组合', 10: '全部报告（年报+半年报+季报）'}
        logger.info(f"使用 financial_type={financial_type} ({msg.get(financial_type, '未知')})")

    try:
        # 设置 PYTHONPATH 以找到 common 模块（在 futuapi/scripts 目录下）
        skill_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        futuapi_scripts_dir = os.path.normpath(os.path.join(skill_dir, "..", "futuapi", "scripts"))
        env = os.environ.copy()
        env['PYTHONPATH'] = futuapi_scripts_dir

        result = subprocess.run(
            [PYTHON_EXE, FUTUAPI_SCRIPT, stock_code,
             "--statement-type", str(st_code),
             "--financial-type", str(financial_type),
             "--num", str(num),
             "--json"],
            capture_output=True,
            timeout=120,
            env=env
        )

        # 用bytes模式读取，避免Windows编码问题
        content = result.stdout.decode('utf-8', errors='ignore')

        json_start = content.find('{"code"')
        if json_start == -1:
            return None, None

        json_end = content.rfind('}')
        if json_end == -1:
            return None, None

        json_str = content[json_start:json_end + 1]
        try:
            data = json.loads(json_str)
        except json.JSONDecodeError:
            return None, None

        structure_list = data['data']['structure_list']
        report_list = data['data']['report_list']

        # 构建 field_id -> 显示名 的映射
        name_map = {e['field_id']: e['display_name'] or f"字段{e['field_id']}" for e in structure_list}

        return report_list, name_map

    except Exception as e:
        return None, None


def convert_quarter_label(raw_label):
    """转换季度标签格式"""
    match = re.match(r'(\d{4}).*Q(\d)', raw_label)
    if match:
        return f"{match.group(1)}Q{match.group(2)}"
    return raw_label.replace('/', '').replace(' ', '')


def get_period_range(report):
    """计算财报周期的日期范围（资产负债表是时点数据，只需日期）"""
    end_date_str = report.get('date_time_str', '')
    return end_date_str


def format_value(val, divisor, is_pct=False):
    """格式化值"""
    if val is None or val == '' or val == 'N/A':
        return None
    if not isinstance(val, (int, float)):
        return val
    if is_pct:
        return round(val, 2)
    else:
        return round(val / divisor, 0)


def calculate_ratio(numerator_values, denominator_values, num_quarters, take_abs_numerator=False, logger=None):
    """计算比率（分子/分母 * 100），处理空值

    Args:
        numerator_values: 分子值数组
        denominator_values: 分母值数组
        num_quarters: 季度数
        take_abs_numerator: 是否对分子取绝对值（用于费用率计算，费用可能为负数）
    """
    ratio_values = []
    for i in range(num_quarters):
        n = numerator_values[i] if i < len(numerator_values) else None
        d = denominator_values[i] if i < len(denominator_values) else None
        if n is not None and d is not None and d != 0:
            # 对费用类分子取绝对值，避免费用率为负
            if take_abs_numerator:
                n = abs(n)
            ratio = round((n / d) * 100, 2)
            ratio_values.append(ratio)
        else:
            ratio_values.append(None)
    return ratio_values


def calculate_profit_margin_a_share(revenue_values, cost_values, num_quarters):
    """计算A股毛利率 (营收-成本)/营收"""
    margin_values = []
    for i in range(num_quarters):
        r = revenue_values[i] if i < len(revenue_values) else None
        c = cost_values[i] if i < len(cost_values) else None
        if r is not None and c is not None and r != 0:
            margin = round(((r - c) / r) * 100, 2)
            margin_values.append(margin)
        else:
            margin_values.append(None)
    return margin_values


def build_rows_with_ratios(reports, name_map, field_config, expense_items, divisor, unit_name,
                            statement_type, market_type, ratio_config, logger=None):
    """构建数据行，含财务比率计算（XX率紧跟XX后）"""
    rows = []
    yoy_tracking = []  # 记录哪些行是YoY行，用于高亮：(row_idx, [value1, value2, ...])

    # 表头
    quarters = [convert_quarter_label(r.get('period_text', '')) for r in reports]
    num_quarters = len(quarters)
    header = ["财务指标", "单位"] + quarters
    rows.append(header)

    # 财报周期行
    period_row = ["财报日期", ""] + [get_period_range(r) for r in reports]
    rows.append(period_row)

    # 预提取所有字段值（原始数据，不除以divisor）
    field_values_raw = {}
    field_values_yoy_raw = {}
    for r in reports:
        items = {i['field_id']: i for i in r.get('item_list', [])}
        for fid in items:
            if fid not in field_values_raw:
                field_values_raw[fid] = []
                field_values_yoy_raw[fid] = []
            field_values_raw[fid].append(items[fid].get('data'))
            field_values_yoy_raw[fid].append(items[fid].get('yoy'))

    # 构建 field_id -> 行索引 的映射（用于插入比率）
    # 先构建基础字段位置映射
    base_field_positions = {}
    for idx, (fid, _, _) in enumerate(field_config):
        base_field_positions[fid] = idx

    # 确定插入位置 - 预先计算所有需要插入的比率位置
    insertions = {}  # {父项field_id: [(比率名称, 分子fid, 分母fid), ...]}

    # 确定收入字段 - 处理部分公司没有8001但有8002的情况
    if statement_type == 'income':
        if market_type == 'a_share':
            revenue_fid = 3001
        elif market_type == 'hk':
            revenue_fid = 5001
        else:  # us
            # 美股优先用8001（总收入），如果不存在则用8002（营业总收入）
            if 8001 in name_map:
                revenue_fid = 8001
            else:
                revenue_fid = 8002

        # 主指标比率
        for ratio_name, num_fid, den_fid, parent_fid in ratio_config.get('income', []):
            # 对于美股，如果原来的分母是8001但实际用的是8002，更新分母
            if market_type == 'us' and den_fid == 8001 and revenue_fid == 8002:
                den_fid = 8002
            if parent_fid not in insertions:
                insertions[parent_fid] = []
            insertions[parent_fid].append((ratio_name, num_fid, den_fid))

        # 费用比率 - 营业费用（注意：营业费用是主字段，不在expense_items子项中）
        if market_type == 'a_share':
            operating_expense_fid = 3005
        elif market_type == 'hk':
            operating_expense_fid = 5015
        else:  # us
            operating_expense_fid = 8005

        if operating_expense_fid in name_map:
            if operating_expense_fid not in insertions:
                insertions[operating_expense_fid] = []
            insertions[operating_expense_fid].append(('营业费用率', operating_expense_fid, revenue_fid))

        # 子项费用比率
        if expense_items:
            for expense_name, expense_fid, den_fid in ratio_config.get('expense_ratios', []):
                # 营业费用率已单独处理，跳过
                if '营业费用率' in expense_name:
                    continue
                # 对于美股，如果原来的分母是8001但实际用的是8002，更新分母
                if market_type == 'us' and den_fid == 8001 and revenue_fid == 8002:
                    den_fid = 8002
                if expense_fid in name_map and expense_fid in expense_items:
                    if expense_fid not in insertions:
                        insertions[expense_fid] = []
                    insertions[expense_fid].append((expense_name, expense_fid, den_fid))

    # 主循环 - 逐行构建
    for field_id, default_name, category in field_config:
        # 跳过不存在的字段
        if field_id not in name_map:
            continue

        actual_name = name_map.get(field_id, default_name)
        raw_values = field_values_raw.get(field_id, [None]*num_quarters)

        # 对费用、成本、支出类字段取绝对值（部分公司用负数表示费用）
        if category in ['expense', 'tax']:
            raw_values = [abs(v) if v is not None else None for v in raw_values]

        values = [format_value(v, divisor) for v in raw_values]
        rows.append([actual_name, unit_name] + values)

        # 添加 YoY 行（资产负债表除外）
        if statement_type != 'balance' and field_id in field_values_yoy_raw:
            yoy_values_raw = field_values_yoy_raw.get(field_id, [None]*num_quarters)
            # 注意：YoY 已经是百分比变化，不需要取绝对值
            yoy_values = [round(v, 2) if v is not None else None for v in yoy_values_raw]
            yoy_row_idx = len(rows)
            rows.append([f'{actual_name} YoY', '%'] + yoy_values)
            yoy_tracking.append((yoy_row_idx, yoy_values))  # 记录用于高亮

        # 检查是否需要在此字段后插入比率行
        if field_id in insertions:
            for ratio_name, num_fid, den_fid in insertions[field_id]:
                # 获取原始值计算比率
                num_raw = field_values_raw.get(num_fid, [None]*num_quarters)
                den_raw = field_values_raw.get(den_fid, [None]*num_quarters)

                # 特殊处理：A股毛利率 = (营收 - 成本)/营收
                if market_type == 'a_share' and ratio_name == '毛利率':
                    revenue_raw = field_values_raw.get(3001, [None]*num_quarters)
                    cost_raw = field_values_raw.get(3009, [None]*num_quarters)
                    ratio_values = calculate_profit_margin_a_share(revenue_raw, cost_raw, num_quarters)
                else:
                    # 费用率计算时，对分子（费用）取绝对值
                    take_abs = '费用率' in ratio_name or '成本率' in ratio_name
                    ratio_values = calculate_ratio(num_raw, den_raw, num_quarters, take_abs)

                rows.append([ratio_name, '%'] + ratio_values)

        # 特殊处理：A股营业费用（字段名可能为"销售费用"等）直接插入营业费用率
        # 检测字段名判断是否为营业费用相关
        if statement_type == 'income' and field_id in name_map:
            field_name = name_map[field_id]
            is_operating_expense = (field_id in [8005, 3005] or '营业费用' in field_name or '销售费用' in field_name and field_id not in [3013, 8008])
            if is_operating_expense and '营业费用率' not in str(rows):
                revenue_fid = 3001 if market_type == 'a_share' else 8001
                num_raw = field_values_raw.get(field_id, [None]*num_quarters)
                den_raw = field_values_raw.get(revenue_fid, [None]*num_quarters)
                # 如果 8001 不存在，尝试用 8002（营业总收入）
                if revenue_fid == 8001 and (not den_raw or all(v is None for v in den_raw)):
                    den_raw = field_values_raw.get(8002, [None]*num_quarters)
                ratio_values = calculate_ratio(num_raw, den_raw, num_quarters, take_abs_numerator=True)
                rows.append(['营业费用率', '%'] + ratio_values)

        # 处理费用细分类目及其比率
        if expense_items and statement_type == 'income' and field_id in [8005, 3005]:  # 营业费用字段
            for exp_fid, (exp_default_name, take_abs) in expense_items.items():
                if exp_fid in name_map and exp_fid in field_values_raw:
                    exp_actual_name = name_map.get(exp_fid, exp_default_name)
                    exp_raw_values = field_values_raw.get(exp_fid, [None]*num_quarters)
                    if take_abs:
                        exp_raw_values = [abs(v) if v is not None else None for v in exp_raw_values]
                    exp_values = [format_value(v, divisor) for v in exp_raw_values]
                    rows.append([exp_actual_name, unit_name] + exp_values)

                    # 费用 YoY 行
                    if exp_fid in field_values_yoy_raw:
                        exp_yoy_raw = field_values_yoy_raw.get(exp_fid, [None]*num_quarters)
                        exp_yoy_values = [round(v, 2) if v is not None else None for v in exp_yoy_raw]
                        exp_yoy_row_idx = len(rows)
                        rows.append([f'{exp_actual_name} YoY', '%'] + exp_yoy_values)
                        yoy_tracking.append((exp_yoy_row_idx, exp_yoy_values))

                    # 费用比率（如果配置了）
                    if exp_fid in insertions:
                        for ratio_name, num_f, den_f in insertions[exp_fid]:
                            rev_raw = field_values_raw.get(den_f, [None]*num_quarters)
                            # 如果 8001 不存在，尝试用 8002（营业总收入）
                            if den_f == 8001 and (not rev_raw or all(v is None for v in rev_raw)):
                                rev_raw = field_values_raw.get(8002, [None]*num_quarters)
                            # exp_raw_values 已经取过绝对值了，不需要再取
                            ratio_values = calculate_ratio(exp_raw_values, rev_raw, num_quarters, take_abs_numerator=False)
                            rows.append([ratio_name, '%'] + ratio_values)

    if logger:
        logger.info(f"构建完成，共 {len(rows)} 行数据，{len(yoy_tracking)} 个YoY行需要高亮检查")

    return rows, quarters, yoy_tracking


def get_row_category(row_name):
    """根据行名称获取类别用于着色"""
    # 1. 核心利润率优先匹配 - 橄榄绿背景
    core_ratios = ['毛利率', '营业利润率', '净利润率']
    for ratio in core_ratios:
        if ratio in row_name:
            return 'core_ratio'

    # 2. 费用率 - 豆绿色背景
    expense_ratios = ['营业费用率', '销售费用率', '管理费用率', '研发费用率', '销售和管理费用率']
    for ratio in expense_ratios:
        if ratio in row_name:
            return 'expense_ratio'

    # 3. 其他行按类别匹配
    category_markers = [
        ('收入', 'revenue'), ('营收', 'revenue'),
        ('成本', 'expense'), ('费用', 'expense'), ('支出', 'expense'),
        ('毛利', 'profit'), ('利润', 'profit'), ('收益', 'profit'), ('净利', 'profit'),
        ('资产', 'asset'), ('流动资产', 'asset'), ('非流动资产', 'asset'),
        ('负债', 'liability'), ('流动负债', 'liability'), ('非流动负债', 'liability'),
        ('权益', 'equity'), ('股东权益', 'equity'),
        ('经营', 'cash_in'), ('投资', 'cash_out'), ('融资', 'cash_out'),
        ('现金流', 'cash_in'), ('现金', 'cash_in'),
        ('税', 'tax'),
        ('率', 'ratio'),  # 其他比率 - 黄色背景
        ('YoY', 'yoy'),
    ]
    for keyword, category in category_markers:
        if keyword in row_name:
            return category
    return 'default'


def generate_excel_with_styling(rows, quarters, yoy_tracking, output_file, sheet_name, logger=None):
    """生成带样式的Excel（含YoY下降红色高亮和YoY增长绿色高亮）"""
    if not HAS_OPENPYXL:
        raise ImportError("需要安装 openpyxl: pip install openpyxl")

    wb = Workbook(write_only=False)
    ws = wb.active
    ws.title = sheet_name

    # 基础样式
    header_font = Font(bold=True, name="微软雅黑")
    normal_font = Font(name="微软雅黑")
    red_font = Font(name="微软雅黑", color="FF0000", bold=True)    # 红色高亮字体（下降）
    green_font = Font(name="微软雅黑", color="00B050", bold=True)  # 绿色高亮字体（增长）
    center_align = Alignment(horizontal='center', vertical='center', wrap_text=True)
    left_align = Alignment(horizontal='left', vertical='center', wrap_text=True)
    thin_border = Border(
        left=Side(style='thin'),
        right=Side(style='thin'),
        top=Side(style='thin'),
        bottom=Side(style='thin')
    )

    # 构建 YoY 行索引集合用于快速查找
    yoy_row_indices = {row_idx: values for row_idx, values in yoy_tracking}

    # 逐行写入
    for row_idx, row in enumerate(rows, 1):  # openpyxl 从1开始
        # 检查是否是 YoY 行（注意rows索引从0开始，row_idx从1开始，偏移量为1）
        is_yoy_row = (row_idx - 1) in yoy_row_indices
        yoy_values = yoy_row_indices.get(row_idx - 1, []) if is_yoy_row else []

        for col_idx, val in enumerate(row, 1):
            cell = ws.cell(row=row_idx, column=col_idx)
            cell.value = val
            cell.border = thin_border

            # 对齐方式
            if col_idx == 1:
                cell.alignment = left_align
            else:
                cell.alignment = center_align

            # 表头样式
            if row_idx == 1:
                cell.font = header_font
                cell.fill = PatternFill(start_color=COLORS['header'],
                                        end_color=COLORS['header'],
                                        fill_type="solid")
            else:
                # 背景色（按类别）
                row_name = row[0] if row else ""
                category = get_row_category(row_name)
                bg_color = COLORS.get(category)
                if bg_color:
                    cell.fill = PatternFill(start_color=bg_color,
                                            end_color=bg_color,
                                            fill_type="solid")

                # YoY 行高亮：下降超过阈值=红色，增长超过阈值=绿色
                if is_yoy_row and col_idx >= 3:  # 第1列是名称，第2列是单位，第3列开始是数据
                    value_idx = col_idx - 3  # 转换为yoy_values的索引
                    if value_idx < len(yoy_values) and yoy_values[value_idx] is not None:
                        yoy_val = yoy_values[value_idx]
                        if isinstance(yoy_val, (int, float)):
                            if yoy_val <= YOY_DECLINE_THRESHOLD:
                                cell.font = red_font  # 红色高亮（大幅下降）
                            elif yoy_val >= YOY_GROWTH_THRESHOLD:
                                cell.font = green_font  # 绿色高亮（大幅增长）
                            else:
                                cell.font = normal_font
                        else:
                            cell.font = normal_font
                    else:
                        cell.font = normal_font
                else:
                    cell.font = normal_font

    # 调整列宽
    ws.column_dimensions['A'].width = 28
    ws.column_dimensions['B'].width = 12
    for col in range(3, ws.max_column + 1):
        ws.column_dimensions[get_column_letter(col)].width = 16

    # 冻结窗格和筛选
    ws.freeze_panes = 'C3'
    ws.auto_filter.ref = f"A1:{get_column_letter(ws.max_column)}{ws.max_row}"

    # 确保目录存在
    output_dir = os.path.dirname(output_file)
    if output_dir and not os.path.exists(output_dir):
        os.makedirs(output_dir)

    wb.save(output_file)

    if logger:
        logger.info(f"Excel saved: {output_file}")
        logger.info(f"YoY highlight thresholds: decline {YOY_DECLINE_THRESHOLD}% (red), growth {YOY_GROWTH_THRESHOLD}% (green)")

    return output_file


def main():
    parser = argparse.ArgumentParser(description='流式生成财务报表Excel')
    parser.add_argument('stock_code', help='股票代码，如 US.BABA / HK.00700 / SH.600519')
    parser.add_argument('--type', '-t', default='income', choices=['income', 'balance', 'cashflow'],
                        help='报表类型：income(利润表), balance(资产负债表), cashflow(现金流量表)')
    parser.add_argument('--num', '-n', type=int, default=16, help='季度数量，默认16')
    parser.add_argument('--output', '-o', help='输出Excel文件路径')
    args = parser.parse_args()

    # 确保日志目录存在（相对于项目根目录）
    script_dir = os.path.dirname(os.path.abspath(__file__))
    # workspace/skills/futu-financial-report/scripts/ -> 上4层到项目根目录
    project_root = os.path.abspath(os.path.join(script_dir, '..', '..', '..', '..'))
    logs_dir = os.path.join(project_root, 'workspace', 'excels', 'logs')
    if not os.path.exists(logs_dir):
        os.makedirs(logs_dir)

    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
    statement_name = STATEMENT_TYPES[args.type][1]

    # 标准化股票代码格式（HK.700 → HK.00700, SH.600 → SH.000600）
    original_code = args.stock_code
    normalized_code = normalize_stock_code(original_code)

    log_file = os.path.join(logs_dir, f"financial_{normalized_code}_{args.type}_{timestamp}.log")
    logger = setup_logging(log_file)

    # ── Start processing ──
    if original_code != normalized_code:
        log_stdout(f">>> Stock code normalized: {original_code} → {normalized_code}")
    log_stdout(f">>> Processing {normalized_code} ({statement_name})")

    # 1. 确定市场和字段配置
    market_type = get_market_type(normalized_code)
    logger.info(f"市场类型: {market_type}, 报表类型: {statement_name}")

    # 根据市场类型选择字段配置
    if market_type == 'a_share':
        base_fields = A_SHARE_FIELDS.get(args.type, [])
        expense_items = EXPENSE_ITEMS_MAP['a_share'] if args.type == 'income' else None
        ratio_config = A_SHARE_RATIO_CONFIG
    elif market_type == 'hk':
        base_fields = HK_FIELDS.get(args.type, [])
        expense_items = EXPENSE_ITEMS_MAP['hk'] if args.type == 'income' else None
        ratio_config = HK_RATIO_CONFIG
    else:  # US
        base_fields = US_FIELDS.get(args.type, [])
        expense_items = EXPENSE_ITEMS_MAP['us'] if args.type == 'income' else None
        ratio_config = US_RATIO_CONFIG

    # 2. 获取数据（带字段结构）
    reports, name_map = get_financial_data_with_structure(normalized_code, args.type, args.num, logger)

    if not reports or not name_map:
        log_stdout(f"ERROR: Failed to get data reports={reports} name_map={name_map}")
        return 1

    # 从财报数据中获取真实的币种单位配置
    unit_name, divisor, currency_code, currency_info = get_unit_config(reports)
    logger.info(f"币种信息: code={currency_code}, info={currency_info}, unit={unit_name}")

    log_stdout(f"OK: Got {len(reports)} quarters data, {len(name_map)} fields")
    log_stdout(f"OK: Currency: {currency_code or currency_info or 'N/A'}, Unit: {unit_name}")

    # 3. 构建数据行（含财务比率计算和YoY跟踪）
    rows, quarters, yoy_tracking = build_rows_with_ratios(
        reports, name_map, base_fields, expense_items,
        divisor, unit_name, args.type, market_type, ratio_config, logger
    )

    log_stdout(f"OK: Built {len(rows)} data rows with {len(yoy_tracking)} YoY comparison rows")

    # 4. 确定输出路径（相对于项目根目录）
    if args.output:
        output_file = args.output
    else:
        excels_dir = os.path.join(project_root, 'workspace', 'excels')
        if not os.path.exists(excels_dir):
            os.makedirs(excels_dir)
        safe_code = normalized_code.replace('.', '_')
        # 文件名格式：{代码}_{报表类型}_{时间}.xlsx
        output_file = os.path.join(excels_dir, f"{safe_code}_{args.type}_{timestamp}.xlsx")

    output_file = os.path.abspath(output_file)

    # 5. 生成 Excel（含YoY下降红色高亮）
    generate_excel_with_styling(rows, quarters, yoy_tracking, output_file, statement_name, logger)

    # ── 最终输出 ──
    result = {
        "status": "ok",
        "stock_code": normalized_code,
        "statement_type": args.type,
        "statement_name": statement_name,
        "quarters": len(reports),
        "rows": len(rows),
        "yoy_highlight_threshold": f"decline {YOY_DECLINE_THRESHOLD}% (red), growth {YOY_GROWTH_THRESHOLD}% (green)",
        "excel_path": output_file,
        "log_path": os.path.abspath(log_file),
        "period_range": f"{quarters[0]} - {quarters[-1]}" if quarters else "",
    }

    log_stdout("=" * 60)
    log_stdout(json.dumps(result, ensure_ascii=False))
    log_stdout("=" * 60)

    return 0


if __name__ == "__main__":
    sys.exit(main())
