---
name: financial-metrics-query
description: 从 workspace/excels/ 中已提取好的财务报表 Excel 中查询公司损益与分部损益数据。两个入口：query_income.py（整体损益表，如营业收入/毛利/营业利润）与 query_segments.py（分部业务数据，含 EBITA / 分部收入及多层级）。自动挑最新版本文件，支持财年/财期/分部名过滤。触发词：查财报数字、公司营收、分部收入、EBITA、季度损益、[fact] 数据源
---

# Financial Excel Query Skill

**从 workspace/excels/*.xlsx 里读结构化财务数据**

## 应用场景

Agent 回答业绩类问题时，`[fact]` 数字必须来自可核验的结构化数据源。本 skill 就是这一层数据源：

- **公司整体**：营业总收入 / 毛利 / 营业利润 / 净利润 / YoY / 各项费用（→ `query_income.py`）
- **分部业务**：各业务线（如淘天、Cloud）的分部收入、EBITA、EBITA 利润率、多层子分部（→ `query_segments.py`）

Agent 拿到这两个脚本的 JSON 输出后，把数字塞进 `[fact]` 表格，再叠加 `retrieve_filing_kb`（[filing-stated cause]）+ web 搜索（[external]）综合分析。

## 数据源约定

`workspace/excels/` 由上游其它 skill（`futu-financial-report` / `segment-financial-report`）生成，文件命名两套：

| 类型 | 文件名规则 | 例子 |
|---|---|---|
| 公司整体 | `{MARKET}_{TICKER}_income_{yyyymmdd}_{hhmmss}.xlsx` | `US_BABA_income_20260628_141917.xlsx` |
| 分部业务 | `{TICKER}_segments_{yyyymmdd}_{hhmmss}.xlsx` | `BABA_segments_20260630_210744.xlsx` |

- `MARKET` ∈ `US / HK / SH / SZ`
- **同 ticker 同类型的多个版本**：脚本按文件名末尾时间戳降序取最新一份，其它忽略
- 打开 Excel 时自动跳过临时锁文件（`~$` 前缀）

Excel 内部结构（两类文件对齐）：

| 布局 | 说明 |
|---|---|
| 第 1 列 | income = 指标名（营业总收入）；segments = 分部名（可能带 `├─` 缩进标层级） |
| 第 2 列 | income = 单位（百万人民币）；segments = 指标（收入 / EBITA / EBITA 利润率(%)） |
| 第 3 列起 | 每列一个 period，如 `2026Q1` / `2025FY` / `2024Q4`；`2025FY` 视为完整财年 |
| 第 1 行 | period 列头 |
| 第 2 行（仅 income） | 财报日期 |
| 第 3 行起 | 数据行 |

## 目录结构

```
workspace/skills/financial-metrics-query/
├── SKILL.md
├── config/
│   └── excel-format.json           # 文件名正则 + 列布局 + period 正则
└── scripts/
    ├── _common.py                  # 共用：文件挑选、period 过滤、单元格规范化
    ├── query_income.py             # 整体损益表查询
    ├── query_segments.py           # 分部业务查询
    └── requirements.txt            # openpyxl
```

## 前置准备

```bash
pip install -r workspace/skills/financial-metrics-query/scripts/requirements.txt
```

## 用法：query_income.py

```bash
# 最简：取 BABA 最新一份 income excel，返回全部指标全部 period
python workspace/skills/financial-metrics-query/scripts/query_income.py --ticker BABA --pretty

# 最近两年全部季度
python workspace/skills/financial-metrics-query/scripts/query_income.py \
    --ticker BABA \
    --fiscal-years 2024-2025 \
    --pretty

# 只要 FY 与 Q4
python workspace/skills/financial-metrics-query/scripts/query_income.py \
    --ticker 00700 --fiscal-periods FY,Q4 --pretty

# 精确 period 列表
python workspace/skills/financial-metrics-query/scripts/query_income.py \
    --ticker BABA --periods "2024Q3,2025FY,2026Q1" --pretty
```

### query_income.py 参数

| 参数 | 说明 | 默认 |
|---|---|---|
| `--ticker` | 股票代码（自动 upper） | 必填 |
| `--fiscal-years` | 财年过滤，`2024` 单年或 `2022-2025` 闭区间 | 不限 |
| `--fiscal-periods` | 财期类型，如 `FY,Q3,Q4` | 不限 |
| `--periods` | 精确 period 列表，如 `2024Q3,2025FY` | 不限 |
| `--config` | 配置文件路径 | `config/excel-format.json` |
| `--excels-dir` | 覆盖 excels 目录 | `workspace/excels/` |
| `--pretty` | 缩进输出 | 关闭 |

## 用法：query_segments.py

```bash
# BABA 全部分部全部指标（可能很大，建议加过滤）
python workspace/skills/financial-metrics-query/scripts/query_segments.py --ticker BABA --pretty

# 只要一级分部（level=1）
python workspace/skills/financial-metrics-query/scripts/query_segments.py \
    --ticker BABA  --max-level 1 --pretty

# 结合财年过滤
python workspace/skills/financial-metrics-query/scripts/query_segments.py \
    --ticker BABA  --fiscal-years 2024-2025 --pretty
```

### query_segments.py 参数

| 参数 | 说明 | 默认 |
|---|---|---|
| `--ticker` | 股票代码 | 必填 |
| `--fiscal-years` / `--fiscal-periods` / `--periods` | 同 income | 不限 |
| `--max-level` | 只返回层级 ≤ N 的分部（Level 1 = 顶层） | 不限 |

## 输出格式

### query_income.py 输出

```json
{
  "success": true,
  "ticker": "BABA",
  "source": "US_BABA_income_20260629_220718.xlsx",
  "sheet": "利润表",
  "periods": ["2026Q1", "2025FY", "2025Q4", "2025Q3", ...],
  "report_dates": {"2026Q1": "2026-03-30", "2025FY": "2025-03-30"},
  "metrics": [
    {
      "metric": "营业总收入",
      "unit": "百万人民币",
      "values": {"2026Q1": 247652, "2025FY": 996347, "2025Q4": 236454, ...}
    },
    {
      "metric": "营业利润",
      "unit": "百万人民币",
      "values": {"2026Q1": 76235, "2025FY": 250986, ...}
    }
  ],
  "count": 2
}
```

### query_segments.py 输出

```json
{
  "success": true,
  "ticker": "BABA",
  "source": "BABA_segments_20260702_174907.xlsx",
  "sheet": "分部财务数据",
  "periods": ["2026Q1", "2025Q4", "2025Q3", "2025Q2", "2025Q1", ...],
  "rows": [
    {
      "segment": "Taobao and Tmall Group",
      "level": 1,
      "parent_segment": null,
      "metric": "收入",
      "values": {"2026Q1": 122220, "2025Q4": 159347, ...}
    },
    {
      "segment": "China commerce retail",
      "level": 2,
      "parent_segment": "Taobao and Tmall Group",
      "metric": "收入",
      "values": {"2026Q1": 116280, ...}
    },
    {
      "segment": "Customer management",
      "level": 3,
      "parent_segment": "China commerce retail",
      "metric": "收入",
      "values": {"2026Q1": 73024, ...}
    }
  ],
  "count": 3
}
```

失败时：`{"success":false,"company":"BABA","error":"..."}`，退出码 2。

## Excel 布局如何配置

`config/excel-format.json` 里的所有硬编码集中管理，涉及新格式时只改 JSON：

| 节点 | 用途 |
|---|---|
| `excelsDir` | Excel 存放目录（相对 workspace） |
| `income.filenamePattern` | 整体损益表文件名正则（含 named group `market/ticker/date/time`） |
| `income.metricColumn / unitColumn / firstPeriodColumn` | 各类列在 sheet 中的位置 |
| `income.reportDateRow / reportDateLabel` | 财报日期所在行 |
| `segments.filenamePattern` | 分部文件名正则 |
| `segments.hierarchyPrefixes` | 分部名的层级前缀（`├─` / `└─` 等） |
| `periodFilter.periodTypeRegex` | period 列名匹配（默认 `20YYFY / 20YYQx / 20YYHx`） |

## Agent Prompt 集成建议

`[fact]` 数据源里应加入本 skill：

```
可用工具与分工：
- financial-metrics-query skill (query_income.py)  → 结构化"事实"来源：公司整体损益（营收/毛利/营业利润/费用/YoY）
- financial-metrics-query skill (query_segments.py)→ 结构化"事实"来源：分部业务数据（分部收入/EBITA/多层级）
- retrieve_filing_kb                             → [filing-stated cause] 原文片段
- ...
```

优先级：本 skill 是"结构化事实"的唯一入口，覆盖全部市场且延迟毫秒级。`workspace/excels/` 由 `futu-financial-report` / `segment-financial-report` 两个 skill 负责生成。

