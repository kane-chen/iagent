# 分部业务财务报表Excel生成工具

## 功能说明

本工具是 **Segment Financial Report Skill** 的 Python 辅助脚本，用于生成上市公司分部业务财务报表Excel，支持多层级分部展示（如BABA的三层业务结构）。

## 架构说明

```
Agent (通过Tool名称调用)
    ↓
FinancialSegmentMetricsTool (Java Tool)
    ├─ queryFinancialMetrics()  ← 获取分部数据
    └─ exportSegmentExcel()     ← 调用本Python脚本生成Excel
        ↓
generate_segment_excel.py (本脚本)
```

**注意：本脚本不直接调用 Java Tool，而是被 Java Tool 调用并传入数据。**

## 特性

- 多层级分部展示（Level 1-3），通过缩进和颜色编码体现层级关系
- 支持收入、EBITA等财务指标
- YoY同比增长率自动高亮显示
- 智能数值格式化（M/K单位）
- 多周期数据自动识别

## 依赖安装

```bash
pip install -r requirements.txt
```

## 使用方法

### ✅ 推荐方式：通过 Java Tool 调用

```java
// 1. 创建Tool实例
FinancialSegmentMetricsTool tool = new FinancialSegmentMetricsTool(workspacePath);

// 2. 调用Tool生成Excel（内部会调用本Python脚本）
String excelPath = tool.exportSegmentExcel("BABA");
```

对应的 Tool 名称（供Agent调用）：`export_segment_financial_excel`

### 🛠️ 手动调用方式（需先准备JSON数据）

```bash
# 从JSON文件生成（JSON由Java Tool生成并传入）
python generate_segment_excel.py BABA --json ./segments.json

# 指定输出路径
python generate_segment_excel.py BABA --json ./segments.json --output ./output.xlsx
```

## 输入数据格式

输入的JSON数据应遵循以下结构：

```json
[
  {
    "segmentId": "TAOBAO_TMALL",
    "segmentName": "Taobao and Tmall Group",
    "level": 1,
    "children": [
      {
        "segmentId": "CHINA_COMMERCE_RETAIL",
        "segmentName": "China commerce retail",
        "level": 2,
        "children": [...],
        "metrics": [...]
      }
    ],
    "metrics": [
      {
        "metricCode": "REVENUE",
        "metricName": "Revenue",
        "value": 123456.78,
        "yoyGrowth": 5.2,
        "period": "2024Q1"
      }
    ]
  }
]
```

## 输出Excel结构

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
- **收入列**：浅蓝色背景
- **EBITA列**：浅绿色背景
- **YoY下降>5%**：红色粗体字
- **YoY增长>30%**：绿色粗体字

## 与Java集成

通过`FinancialSegmentMetricsTool.exportSegmentExcel(ticker)`方法调用：

```java
FinancialSegmentMetricsTool tool = new FinancialSegmentMetricsTool(workspace);
String excelPath = tool.exportSegmentExcel("BABA");
```
