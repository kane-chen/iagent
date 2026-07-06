# 分部业务财务报表 Excel 生成工具

## 功能说明

本工具是 **Segment Financial Report Skill** 的 Python 实现，用于从上市公司财报文件（HTML/PDF）中提取分部业务财务数据，并生成多层级分部报表 Excel。

## 架构说明

```
Agent（bash 调用）
    ↓
extract_segments.py           # Stage 1：提取引擎编排（一步到位模式）
    ├─ engine/                #   纯 Python 提取引擎
    │   ├── extraction_service.py  # 主服务入口
    │   ├── html_segment_parser.py # HTML 解析（SEC 美股）
    │   └── pdf_parser.py          # PDF 解析（港股/A 股）
    └─ generate_segment_excel.py  # Stage 2：JSON → Excel 渲染
```

**推荐使用 `extract_segments.py --excel` 一步完成提取+渲染**，无需单独调用 generate_segment_excel.py。

## 特性

- 多层级分部展示（Level 1-3），通过缩进和颜色编码体现层级关系
- 支持收入、EBITA 等财务指标
- YoY 同比增长率自动高亮显示
- 智能数值格式化（M/K 单位）
- 多周期数据自动识别
- 策略模式：按公司 config 自动选择 HTML/PDF 解析 handler

## 依赖安装

```bash
pip install -r workspace/skills/segment-financial-report/scripts/requirements.txt
```

## 使用方法

### ✅ 推荐方式：一步到位（Agent 使用这个）

```bash
# 提取 + 渲染 Excel 一次完成，stdout 最后一行是 xlsx 绝对路径
python workspace/skills/segment-financial-report/scripts/extract_segments.py \
    --ticker BABA --excel
# → 输出 workspace/excels/BABA_segments_<ts>.xlsx
```

### 🛠️ 分阶段（调试用）

```bash
# Stage 1：只提取分部数据到 JSON
python workspace/skills/segment-financial-report/scripts/extract_segments.py \
    --ticker BABA --output ./segments.json

# Stage 2：JSON → Excel
python workspace/skills/segment-financial-report/scripts/generate_segment_excel.py BABA \
    --json ./segments.json --workspace workspace
```

## 输入数据格式（Stage 2 JSON）

`generate_segment_excel.py --json` 接受的 JSON 结构（flat 模式，由 extract_segments.py 产出）：

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
    "yoyGrowth": 5.2,
    "period": "2024Q1"
  }
]
```

## 输出 Excel 结构

| 业务分部 | 指标 | 2024Q1 | 2024Q2 | ... | 2024Q1 YoY(%) | 2024Q2 YoY(%) | ... |
|---------|-----|--------|--------|-----|---------------|---------------|-----|
| Taobao and Tmall Group | 收入 | xxx | xxx | ... | 5.2% | 6.1% | ... |
| Taobao and Tmall Group | EBITA | xxx | xxx | ... | 8.3% | 10.2% | ... |
|   ├─ China commerce retail | 收入 | xxx | xxx | ... | 4.5% | 5.8% | ... |
|   ├─ China commerce retail | EBITA | xxx | xxx | ... | 7.1% | 9.3% | ... |

## 样式说明

- **一级分部**：浅蓝背景，深蓝色粗体字
- **二级分部**：浅绿背景，深绿色粗体字
- **三级分部**：浅黄背景，深黄色字
- **YoY 下降 > 5%**：红色粗体字
- **YoY 增长 > 30%**：绿色粗体字
