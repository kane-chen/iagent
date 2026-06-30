---
name: segment-financial-report
description: 生成分部业务财务报表Excel，支持多层级分部展示（如BABA的三层业务结构），包含收入、EBITA等指标及YoY同比高亮。触发词：分部财报、分部数据、业务分部、分部门数据
---

# 分部业务财务报表生成Skill

**多层级分部展示 + 收入/EBITA指标 + YoY同比高亮**

## 核心特性

### 📊 支持多层级分部
- **Level 1** - 一级业务分部（如淘天集团、国际数字商业、云智能）
- **Level 2** - 二级业务分部
- **Level 3** - 三级业务分部
- 通过缩进、颜色编码直观体现层级关系

### 📈 支持的财务指标
| 指标 | 说明 |
|-----|------|
| **收入 (Revenue)** | 各分部营业收入 |
| **EBITA** | 调整后EBITA或营业利润 |

### 🔴 YoY同比智能高亮
- 同比**下降超过5%**的数据自动红色高亮
- 同比**增长超过30%**的数据自动绿色高亮

### 🎨 智能样式着色
| 层级/类别 | 背景色 | 字体色 |
|----------|-------|-------|
| 表头 | 深蓝 | 白色 |
| 一级分部 | 浅蓝 | 深蓝色 |
| 二级分部 | 浅绿 | 深绿色 |
| 三级分部 | 浅黄 | 深黄色 |
| 收入指标列 | 浅蓝 | |
| EBITA指标列 | 浅绿 | |

### ⚡ 技术优化
- **多周期自动识别**：自动提取所有财报周期
- **层级展平展示**：保持树状结构的同时扁平化展示
- **数值格式化**：自动处理大数单位（M/K）

## 调用方式

### 🔧 通过 Java Tool 调用（推荐）

本Skill通过 `FinancialSegmentMetricsTool` 类中的 Tool 方法调用：

```java
// 查询分部数据（返回Java对象）
List<Segment> segments = tool.queryFinancialMetrics("BABA");

// 导出Excel（调用Python脚本生成）
String excelPath = tool.exportSegmentExcel("BABA");
```

Tool 名称：`export_segment_financial_excel`

### 📋 通过命令行调用（需先准备JSON数据）

```bash
# 从JSON文件生成（JSON由Java Tool生成并传入）
python ${workspace}/skills/segment-financial-report/scripts/generate_segment_excel.py BABA --json ./segments.json

# 指定输出路径
python ${workspace}/skills/segment-financial-report/scripts/generate_segment_excel.py BABA --json ./segments.json --output ./baba_segments.xlsx
```

## 参数说明

| 参数 | 说明 | 默认值 |
|-----|------|-------|
| `ticker` | 股票代码 | 必填 |
| `--json` / `-j` | 包含Segment数据的JSON文件路径（**必填**，由Java Tool生成） | - |
| `--output` / `-o` | 输出Excel路径 | 自动生成 |
| `--workspace` / `-w` | 项目工作空间路径 | 自动推断 |

## 输出路径

- Excel文件：`workspace/excels/{代码}_segments_{时间}.xlsx`
- 日志文件：`workspace/excels/logs/segment_{代码}_{时间}.log`

## stdout 输出规范

```
>>> Processing {ticker} 分部财务数据
============================================================
{ JSON格式的结果摘要 }
============================================================
```

JSON 摘要示例：

```json
{
  "status": "ok",
  "ticker": "BABA",
  "segments_count": 8,
  "periods": ["2024Q1", "2024Q2", "2024Q3", "2024Q4"],
  "excel_path": "绝对路径到Excel",
  "log_path": "绝对路径到日志"
}
```

## 文件结构

```
segment-financial-report/
├── SKILL.md                    # 本文档
└── scripts/
    └── generate_segment_excel.py  # 主脚本
```

## Excel 列结构

| 列名 | 说明 |
|-----|------|
| 业务分部 | 带层级缩进的分部名称 |
| 指标 | 收入 / EBITA |
| 2024Q1, 2024Q2, ... | 各周期数值 |
| 2024Q1 YoY(%), ... | 各周期同比增长率 |
