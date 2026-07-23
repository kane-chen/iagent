---
name: earnings-analysis
description:  通过自动化处理Excel财务数据生成专业的8-12页股权研究财报更新报告。
---

# 自动化财报更新技能

## 概述
本技能通过Python驱动的流程自动化创建机构级财报更新报告。它用程序化数据提取和分析替代了手动数据录入，确保数值准确性、严格遵守格式模板，并集成实时网络数据。

## 前置条件
- **Python环境**: 需要 `pandas`、`openpyxl`、`python-docx`、`matplotlib`、`numpy`。
- **网络搜索工具**: 必须有权访问 `web_search` 函数/工具。
- **文件结构**: 工作目录必须包含：
    - `scripts/` 目录（包含分析逻辑）
    - `references/` 目录（包含模板和数据字典）

## 必需输入
1. **基本参数**:
    - `ticker`: 股票代码（例如："GOOG"）
    - `work_path`: 工作目录路径（Excel文件所在目录）
2. **元数据**:
    - `company_name`: 公司名称（例如："Alphabet Inc."）
    - `target_quarter`: 目标季度（例如："2026Q2"）
    - `report_date`: 当前日期（YYYY-MM-DD格式）

## 依赖（脚本与参考文档）
智能体按顺序执行以下模块：

### 脚本（`scripts/`）
1.  **`data_extraction.py`**
    - *功能*: 从工作目录中自动查找并读取Excel文件，验证数据类型，检查财报日期（必须在`report_date`的3个月内）。
    - *文件查找规则*: 使用正则表达式 `^.*_{ticker}_{suffix}_.*\.(xlsx|xls)$` 匹配文件
        - `ticker`: 股票代码（如"GOOG"）
        - `suffix`: `income`（利润表）、`balance`（资产负债表）、`cashflow`（现金流量表）
    - *关键方法*: `read_and_validate_excel(ticker, work_path, sheet_name, reference_date)`, `extract_quarterly_data(df, quarter_year)`。

2.  **`financial_analysis.py`**
    - *功能*: 计算同比/环比增长率、利润率、ROE/ROA和自由现金流转换率。
    - *关键方法*: `calculate_metrics(balance_sheet, income_statement, cash_flow, quarter)`, `analyze_quarterly_trend(df, metric_name, periods)`。

3.  **`report_generation.py`**
    - *功能*: 构建DOCX报告，嵌入matplotlib图表，并格式化表格。
    - *关键方法*: `create_earnings_update_report(company_name, ticker, quarter, analysis_data, web_data, output_path)`, `add_charts(doc, analysis_data)`。

### 参考文档（`references/`）
1.  **`data_dictionary.md`**
    - 定义Excel列的字段映射和计算逻辑。
2.  **`analysis_framework.md`**
    - 提供财务评估的逻辑结构（盈利能力、现金流、资产负债表）。
3.  **`report_template.md`**
    - 指定最终DOCX的精确布局、字体和章节顺序。
4.  **`quality_checklist.md`**
    - 用于验证最终输出的准确性和格式合规性。

## 工作流程步骤

### 阶段1：数据获取与验证
1.  **定位文件**: 根据股票代码在工作目录中自动查找匹配的Excel文件。
    - 利润表文件：`^.*_{ticker}_income_.*\.(xlsx|xls)$`
    - 资产负债表文件：`^.*_{ticker}_balance_.*\.(xlsx|xls)$`
    - 现金流量表文件：`^.*_{ticker}_cashflow_.*\.(xlsx|xls)$`
2.  **执行验证**:
    - 调用 `scripts/data_extraction.py`。
    - **检查**: 列中是否存在`target_quarter`？
    - **操作**: 如果验证失败，停止并请求新数据或警告用户。

### 阶段2：程序化分析
1.  **提取数据**: 提取`target_quarter`、`prior_quarter`和`year_ago_quarter`的行数据。
2.  **计算指标**:
    - 运行 `scripts/financial_analysis.py`。
    - 计算：收入增长、毛利率、营业利润率、净利润率、EPS、FCF、ROE。
    - 与`prior_quarter`（环比）和`year_ago_quarter`（同比）进行比较。

### 阶段3：报告构建
1.  **初始化**: 调用 `scripts/report_generation.py`。
2.  **第1页**: 使用计算指标填充标题、摘要框、投资影响要点。
3.  **第2-3页**: 根据`analysis_framework.md`中的逻辑填充详细分析文本。
4.  **可视化**:
    - 使用`matplotlib`生成趋势图（收入、利润率）。
    - 将图表嵌入DOCX。
5.  **第8-10页**: 使用计算倍数和DCF输入更新估值表格。

### 阶段4：质量保证
1.  **运行检查清单**: 应用 `references/quality_checklist.md`。
2.  **验证**:
    - 所有数字与Excel源数据匹配。
    - 所有超链接可点击。
    - 报告长度为8-12页。
    - 引用完整。

## 输出规范
- **主要输出**: `[公司名]_Q[季度]_[年份]_Earnings_Update.docx`
- **元数据**:
    - 文件大小: ~2-5 MB（由于嵌入图表）
    - 页数: 8-12页
    - 格式: DOCX（Times New Roman/Arial字体）

## 错误处理
- **缺失列**: 如果Excel中缺少必需指标（如"自由现金流"），记录警告并使用替代计算或标记为"N/A"。
- **网络搜索失败**: 如果找不到一致预期，仅使用历史比较并在报告中注明"一致预期不可用"。
