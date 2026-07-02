---
name: segment-financial-report
description: 生成上市公司分部业务财务报表 Excel，两阶段执行（extract_segments.py → generate_segment_excel.py）：Java 引擎从财报 HTML/PDF 提取分部数据（支持策略模式识别 + 公司配置隔离），Python 渲染多层级 Excel（含 YoY 同比高亮）。触发词：分部财报、分部数据、业务分部、segment metrics、分部收入、分部EBITA
---

# 分部业务财务报表 Skill

**Java 提取引擎 + Python 渲染层**，两阶段解耦，共享 workspace 文件契约。

## 架构

```
┌─────────────────────────────────────────────────────────────────┐
│  Stage 1: extract_segments.py                                   │
│    → java -jar iagent-<ver>-cli.jar --ticker BABA               │
│                                    --workspace ./workspace      │
│                                    --flat --output <JSON>       │
│    → 内部走 FinancialExtractionService                          │
│      ├─ HtmlReportOrchestrator（策略分派）                      │
│      │  ├─ GenericHtmlLayoutHandler（兜底）                     │
│      │  ├─ SegmentContributionHandler（BABA/GOOG 类）           │
│      │  └─ 其它专有 handler                                     │
│      └─ PdfReportParser（港股 PDF 通道）                        │
│    → 加载 src/main/resources/extraction/config/<TICKER>.json    │
│    → 输出扁平 SegmentMetricDTO JSON                             │
├─────────────────────────────────────────────────────────────────┤
│  Stage 2: generate_segment_excel.py                             │
│    → 读上一步的 JSON                                            │
│    → 渲染多层级 Excel，含 YoY 同比高亮                          │
│    → 输出 workspace/excels/<TICKER>_segments_<ts>.xlsx          │
└─────────────────────────────────────────────────────────────────┘
```

**为什么两阶段？**
1. **策略模式在 Java 侧保留**：`HtmlLayoutHandler / PdfLayoutHandler / SubsegmentMatrixHandler / SegmentsAsRowsHandler / SegmentsAsColumnsHandler` 等已成熟策略实现无缝复用
2. **公司隔离**：`src/main/resources/extraction/config/<TICKER>.json` 承担公司差异（segments/aliases/indent hints/period 过滤），一个公司一个 JSON，脚本不改
3. **渲染独立可换**：如果要出 PDF/HTML 报告，只加新的 stage 2 脚本
4. **可单独跑**：Stage 1 得到 JSON 后可直接给 LLM/其它下游用；Stage 2 也可以拿一份已有 JSON 单独跑

## 目录结构

```
workspace/skills/segment-financial-report/
├── SKILL.md
└── scripts/
    ├── extract_segments.py         # Stage 1：Python 编排 + 调 Java jar
    ├── generate_segment_excel.py   # Stage 2：JSON → Excel 渲染
    ├── extract_pdf_tables.py       # 辅助（PDF 表格调试）
    ├── requirements.txt
    └── README.md
```

## 前置准备

```bash
# 1) 打包 CLI jar（一次即可，pom.xml 已经配置好双 execution）
cd <PROJECT_ROOT>
mvn -DskipTests package
# 会产出：target/iagent-<ver>.jar（主应用）和 target/iagent-<ver>-cli.jar（CLI）

# 2) Python 依赖
pip install -r workspace/skills/segment-financial-report/scripts/requirements.txt
```

## 用法

### 常规流程：先提取，再渲染

```bash
# Stage 1：提取分部数据到 JSON
python workspace/skills/segment-financial-report/scripts/extract_segments.py \
    --ticker BABA --print-preview
# → stdout 一行：JSON 文件绝对路径（默认写到 workspace/temp/BABA_segments_<ts>.json）

# Stage 2：JSON → Excel
python workspace/skills/segment-financial-report/scripts/generate_segment_excel.py BABA \
    --json <上一步 stdout 的路径>
# → 生成 workspace/excels/BABA_segments_<ts>.xlsx
```

### 一行式（Agent 场景）

```bash
python workspace/skills/segment-financial-report/scripts/extract_segments.py \
    --ticker BABA --auto-build \
| xargs -I{} python workspace/skills/segment-financial-report/scripts/generate_segment_excel.py BABA --json {}
```

Windows PowerShell：

```powershell
$json = python workspace/skills/segment-financial-report/scripts/extract_segments.py --ticker BABA --auto-build
python workspace/skills/segment-financial-report/scripts/generate_segment_excel.py BABA --json $json
```

## extract_segments.py 参数

| 参数 | 说明 | 默认 |
|---|---|---|
| `--ticker` | 股票代码（BABA / 00700 / PDD 等）；对应 `resources/extraction/config/<TICKER>.json` | 必填 |
| `--workspace` | workspace 根目录（含 `portfolio/<TICKER>/filings/`） | 从脚本位置推断 |
| `--output` | JSON 输出路径 | `workspace/temp/<TICKER>_segments_<ts>.json` |
| `--project-root` | iagent 项目根 | 从脚本位置推断 |
| `--flat` / `--no-flat` | flat=输出 SegmentMetricDTO 列表（渲染器要这个）；no-flat=输出树状 Segment | `--flat` |
| `--fiscal-year-start` / `--fiscal-year-end` | 财年闭区间过滤，例如 `--fiscal-year-start 2022 --fiscal-year-end 2025` | 不限 |
| `--auto-build` | jar 缺失时自动 `mvn -DskipTests package` | 关闭 |
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

## CLI jar 退出码

- `0` — 提取成功
- `1` — 参数错误
- `2` — 未找到候选财报文件（如 `workspace/portfolio/<TICKER>/filings/` 为空）
- `3` — 提取内部报错（策略识别失败、HTML/PDF 解析报错等）

## Excel 渲染层特性（Stage 2 未改动）

Excel 的多层级配色、YoY 高亮、周期识别等特性完全由 `generate_segment_excel.py` 决定：

- **Level 1 一级分部** — 浅蓝底/深蓝字
- **Level 2 二级分部** — 浅绿底/深绿字
- **Level 3 三级分部** — 浅黄底/深黄字
- **YoY 高亮** — 下降 > 5% 红色，增长 > 30% 绿色

## 添加新公司

1. 在 `src/main/resources/extraction/config/` 下新增 `<TICKER>.json`（参考 `BABA.json` / `GOOG.json`）
   - 配置 `segments` 列表：`segmentCode / segmentName / level / aliases` 等
   - 可选 `includePeriodTypes`：限定只保留哪些周期（如 `["Q1","Q2","Q3","Q4"]`）
2. 把该公司的财报下载到 `workspace/portfolio/<TICKER>/filings/<docId>/`（走 `futu-announcements` skill 即可）
3. `mvn package` 重新打包（resources 打进 jar）
4. `python extract_segments.py --ticker <TICKER>` 冒烟

**无需改动 Java 代码或 Python 脚本**——策略分派在 Java 侧按 config + 表格结构自动路由；渲染侧对新 ticker 完全通用。

## 已知限制

- 港股 PDF 依赖 `PdfReportParser` 的位置映射，如果新公司 PDF 有特殊字体，需要在 Java 侧新增 handler
- CLI jar 首次启动约 1~2 秒（JVM）；批量调用建议改造成常驻服务（未来 M6+）
