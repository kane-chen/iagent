---
name: segment-financial-report
description: 生成上市公司分部业务财务报表 Excel：纯 Python 引擎从财报 HTML/PDF 提取分部数据（支持策略模式识别 + 公司配置隔离），渲染多层级 Excel（含 YoY 同比高亮）。一次 bash 调用即可。触发词：分部财报、分部数据、业务分部、segment metrics、分部收入、分部EBITA
---

# 分部业务财务报表 Skill

**纯 Python 提取引擎 + Python 渲染层**，单次 bash 调用即可产出 Excel。Java 侧保留相同的接口与策略实现（`FinancialExtractionService` / `FileSegmentParser`），作为工具 `export_segment_financial_excel` 的后端。

## 架构（推荐：一步到位）

```
┌─────────────────────────────────────────────────────────────────┐
│  extract_segments.py --excel                                    │
│    python extract_segments.py --ticker BABA --excel            │
│                              --workspace ./workspace            │
│    → 内部走 engine/ 纯 Python 版 FinancialExtractionService     │
│      ├─ FileSegmentParser 按扩展名分发                          │
│      │  ├─ HtmlFileSegmentParser → HtmlReportOrchestrator      │
│      │  │   ├─ GenericHtmlLayoutHandler（兜底）                │
│      │  │   ├─ SegmentContributionHandler（BEKE 类）           │
│      │  │   └─ SegmentRowPeriodColumnHandler（TCOM 类）        │
│      │  └─ PdfFileSegmentParser → extract_pdf_tables + PdfLayoutHandler │
│      │     ├─ SegmentsAsColumnsHandler（腾讯多列分部）         │
│      │     ├─ SegmentsAsRowsHandler（腾讯多行分部）            │
│      │     └─ SubsegmentMatrixHandler（美团 L1×L2 矩阵）       │
│    → 加载 config/extraction/<TICKER>.json                       │
│    → 生成扁平 SegmentMetricDTO，直接渲染 Excel                  │
│    → stdout 最后一行：workspace/excels/<TICKER>_segments_<ts>.xlsx
└─────────────────────────────────────────────────────────────────┘
```

**设计要点**
1. **纯 Python 引擎**：`HtmlLayoutHandler / PdfLayoutHandler / SubsegmentMatrixHandler / SegmentsAsRowsHandler / SegmentsAsColumnsHandler` 等策略直接在 `engine/` 下实现，与 Java 侧逻辑等价，无需 JVM
2. **公司隔离**：`config/extraction/<TICKER>.json` 承担公司差异（segments/aliases/pdfColumnMappings/period 过滤），一个公司一个 JSON，脚本不改（与 Java 侧共享同一份配置格式）
3. **渲染可独立**：如果需要自定义渲染（PDF/HTML 报告），可以分两步：先跑 `extract_segments.py`（不加 `--excel`）得到 JSON，再加任意 renderer
4. **Java 侧同步实现**：`export_segment_financial_excel` 工具仍然走 Java 引擎（`FinancialExtractionService`）→ 临时 JSON → `generate_segment_excel.py` 子进程，结果一致

## 目录结构

```
workspace/skills/segment-financial-report/
├── SKILL.md
├── config/extraction/             # 公司配置（JSON），Java/Python 共享
└── scripts/
    ├── extract_segments.py         # Stage 1：纯 Python 引擎编排
    ├── generate_segment_excel.py   # Stage 2：JSON → Excel 渲染
    ├── extract_pdf_tables.py       # PDF 多引擎表格抽取（camelot/pdfplumber）
    ├── engine/                     # 纯 Python 提取引擎
    │   ├── extraction_service.py   #   主服务（FileSegmentParser 分发）
    │   ├── model.py                #   数据模型（Segment/Metric/CompanyConfig）
    │   ├── file_filter.py          #   候选文件过滤（US→HTML, HK→PDF）
    │   ├── filing_context.py       #   Filing 周期上下文
    │   ├── html_segment_parser.py  #   HTML parser 组合器
    │   ├── html_parser.py          #   BeautifulSoup 表格解析
    │   ├── html_orchestrator.py    #   HTML handler 策略分派
    │   ├── pdf_parser.py           #   PDF parser（调 extract_pdf_tables）
    │   ├── pdf_layout_handler.py   #   PDF layout handler 接口
    │   ├── pdf_support.py          #   PDF 公共工具（数值/校验/组装）
    │   ├── pdf_handlers/           #   PDF 各 layout handler
    │   └── handlers/               #   HTML 各 layout handler
    ├── requirements.txt
    └── README.md
```

## 前置准备

```bash
# Python 依赖（camelot/pdfplumber 用于 PDF 表格抽取，openpyxl 用于 Excel 渲染）
pip install -r workspace/skills/segment-financial-report/scripts/requirements.txt
```

## 用法

### 推荐：一步到位（Agent 场景使用这个）

```bash
# 提取 + 渲染 Excel 一次完成，stdout 最后一行是 xlsx 绝对路径
python workspace/skills/segment-financial-report/scripts/extract_segments.py \
    --ticker BABA --excel
# → 输出 workspace/excels/BABA_segments_<ts>.xlsx
```

港股 PDF 公司（00700/83690 等）同样用法，引擎自动按扩展名分发给 PDF parser。

### 分阶段（调试场景）

```bash
# Stage 1：只提取分部数据到 JSON，stdout 最后一行是 JSON 绝对路径
python workspace/skills/segment-financial-report/scripts/extract_segments.py \
    --ticker BABA --print-preview
# → 默认写到 workspace/temp/BABA_segments_<ts>.json

# Stage 2：JSON → Excel（单独渲染用）
python workspace/skills/segment-financial-report/scripts/generate_segment_excel.py BABA \
    --json <上一步 stdout 的路径> --workspace workspace
# → 生成 workspace/excels/BABA_segments_<ts>.xlsx
```

## extract_segments.py 参数

| 参数 | 说明 | 默认 |
|---|---|---|
| `--ticker` | 股票代码（BABA / 00700 / PDD 等）；对应 `config/extraction/<TICKER>.json` | 必填 |
| `--workspace` | workspace 根目录（含 `portfolio/<TICKER>/filings/`） | 从脚本位置自动推断 |
| `--output` | JSON 输出路径 | `workspace/temp/<TICKER>_segments_<ts>.json` |
| `--flat` / `--no-flat` | flat=输出 SegmentMetricDTO 列表（渲染器要这个）；no-flat=输出树状 Segment | `--flat`（`--excel` 时强制 flat） |
| `--fiscal-year-start` / `--fiscal-year-end` | 财年闭区间过滤，例如 `--fiscal-year-start 2022 --fiscal-year-end 2025` | 不限 |
| `--excel` | 提取后直接生成 Excel（一步到位，不再需要调用 generate_segment_excel.py）；stdout 输出 xlsx 路径 | 关闭 |
| `--excel-output` | 自定义 xlsx 输出路径（仅 `--excel` 时生效） | `workspace/excels/<TICKER>_segments_<ts>.xlsx` |
| `--print-preview` | stderr 打印前 5 条 segment 便于快速核对 | 关闭 |

## 输出格式

Stage 1 输出的 JSON 结构（flat 模式）：

```json
[
  {
    "segmentCode": "TAOBAO_TMALL",
    "segmentName": "Taobao and Tmall Group",
    "level": 1,
    "parentSegmentCode": null,
    "metricCode": "ADJUSTED_EBITA",
    "metricName": "调整后EBITA",
    "value": 45635.0,
    "yoyGrowth": null,
    "confidenceScore": 80,
    "sourceType": "TABLE_EXTRACT",
    "sourceLocation": "table_28",
    "currency": null,
    "unit": "million",
    "period": "2022Q3"
  },
  ...
]
```

## 退出码

- `0` — 成功
- `1` — 参数错误或提取/渲染内部异常
- `2` — 未找到候选财报文件（如 `workspace/portfolio/<TICKER>/filings/` 为空）

## Excel 渲染层特性（Stage 2 未改动）

Excel 的多层级配色、YoY 高亮、周期识别等特性完全由 `generate_segment_excel.py` 决定：

- **Level 1 一级分部** — 浅蓝底/深蓝字
- **Level 2 二级分部** — 浅绿底/深绿字
- **Level 3 三级分部** — 浅黄底/深黄字
- **YoY 高亮** — 下降 > 5% 红色，增长 > 30% 绿色

## 添加新公司

1. 在 `workspace/skills/segment-financial-report/config/extraction/` 下新增 `<TICKER>.json`（参考 `BABA.json` / `00700.json` / `83690.json`）
   - 配置 `segments` 列表：`segmentCode / segmentName / level / aliases` 等
   - 可选 `includePeriodTypes`：限定只保留哪些周期（如 `["Q1","Q2","Q3","Q4"]`）
   - 港股 PDF 公司还需配置 `pdfColumnMappings`（位置映射 + layout 策略）
2. 把该公司的财报下载到 `workspace/portfolio/<TICKER>/filings/<docId>/`（走 `futu-filing` skill 即可）
3. `python extract_segments.py --ticker <TICKER> --excel` 冒烟

**无需改动引擎代码**——策略分派在 engine/ 侧按 config + 表格结构自动路由；渲染侧对新 ticker 完全通用。

## 已知限制

- 港股 PDF 依赖 `PdfFileSegmentParser` + `PdfLayoutHandler` 的位置映射，如果新公司 PDF 有特殊表格结构，需要在 Python/Java 两侧新增 handler（两侧逻辑保持一致）
- 如需走 Java 引擎，CLI 入口为 `SegmentExtractionCli`（`java -cp iagent-*.jar io.invest.iagent.cli.SegmentExtractionCli --ticker BABA --workspace ./workspace --flat`）
