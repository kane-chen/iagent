# DCF Model — 输出Excel结构说明

## 概述

标准 DCF 模型包含 **两个 Sheet**：

| Sheet | 内容 | 核心功能 |
|---|---|---|
| DCF | 主估值模型 + 敏感性分析（底部） | 完整 DCF 计算链与情景敏感性 |
| WACC | 资本成本构建 | CAPM、债务成本、资本结构权重 |

**关键设计**：敏感性表放在 DCF sheet 底部（不是独立 sheet），所有估值产出集中在一个视图。


## 结构示例
``` 
┌──────────────────────────────────────────────────────────────────────────────┐
│ Section 1: Header                                                          │
│ Row 1: [公司名称] DCF Model                                                │
│ Row 2: Ticker: AAPL | Date: 2025-06-15 | Year End: Sep                    │
│ Row 4: Case Selector (1=Bear 2=Base 3=Bull)                    ← 输入: 2   │
│ Row 5: =IF(B6=1,"Bear",IF(B6=2,"Base","Bull"))                 ← 公式     │
├──────────────────────────────────────────────────────────────────────────────┤
│ Section 2: Market Data (不随 Case 变化)                                     │
│ Item                       │ Value                                         │
│ Current Stock Price        │ $195.50                    ← 蓝色硬编码       │
│ Shares Outstanding (M)     │ 15.5                       ← 蓝色硬编码       │
│ Market Cap ($M)            │ =B10*B11                   ← 黑色公式         │
│ Net Debt ($M)              │ 50.0                       ← 蓝色硬编码       │
├──────────────────────────────────────────────────────────────────────────────┤
│ Section 3: Scenario Assumptions (三组 Block，每组含列头)                     │
│                                                                            │
│ BEAR CASE ASSUMPTIONS (深蓝底白字，合并单元格)                               │
│ Assumption       │ FY2025E │ FY2026E │ FY2027E │ FY2028E │ FY2029E        │
│ Revenue Growth % │  8%     │  7%     │  6%     │  5%     │  4%            │
│ EBIT Margin %    │  44%    │  43%    │  42%    │  41%    │  40%           │
│ Terminal Growth  │  2.0%   │         │         │         │                │
│ WACC             │  10.0%  │         │         │         │                │
│                                                                            │
│ BASE CASE ASSUMPTIONS (同样结构)                                            │
│ Revenue Growth % │ 12%     │ 11%     │ 10%    │  9%     │  8%             │
│ EBIT Margin %    │  48%    │  49%    │  50%   │  51%    │  52%           │
│ Terminal Growth  │  2.5%   │         │         │         │                │
│ WACC             │  9.0%   │         │         │         │                │
│                                                                            │
│ BULL CASE ASSUMPTIONS (同样结构)                                            │
│ Revenue Growth % │ 16%     │ 15%     │ 13%    │ 11%     │  9%             │
│ EBIT Margin %    │  50%    │  51%    │  52%   │  53%    │  54%           │
│                                                                            │
│ → 再加一列 Consolidation Column:                                            │
│   =INDEX(Bear:FY1, Bull:FY1, 1, $B$6)  ← 用 INDEX 引用选中的 Scenario     │
├──────────────────────────────────────────────────────────────────────────────┤
│ Section 4: Historical & Projected Financials                                │
│ ($M)          │ 2022A │ 2023A │ 2024A │ 2025E │ 2026E │ 2027E             │
│ Revenue       │ 394   │ 383   │ 391   │ =E29*(1+$E$10) │ ... │ ...       │
│   % growth    │       │       │       │ =E29/D29-1      │ ... │ ...       │
│ Gross Profit  │ ...   │ ...   │ ...   │ =E29*E33        │ ... │ ...       │
│ S&M           │ ...   │ ...   │ ...   │ =E29*0.15       │ ... │ ...       │
│ R&D           │ ...   │ ...   │ ...   │ =E29*0.12       │ ... │ ...       │
│ G&A           │ ...   │ ...   │ ...   │ =E29*0.08       │ ... │ ...       │
│ EBIT          │ ...   │ ...   │ ...   │ =E33-E39        │ ... │ ...       │
│ Taxes         │ ...   │ ...   │ ...   │ =E41*$E$24      │ ... │ ...       │
│ NOPAT         │ ...   │ ...   │ ...   │ =E41-E43        │ ... │ ...       │
├──────────────────────────────────────────────────────────────────────────────┤
│ Section 5: Free Cash Flow Build                                            │
│ NOPAT         │ ...   │ =E45                  │ ...                        │
│ (+) D&A       │ ...   │ =E29*$E$21            │ ...                        │
│ (-) CapEx     │ ...   │ =E29*$E$22            │ ...                        │
│ (-) Δ NWC     │ ...   │ =(E29-D29)*$E$23      │ ...                        │
│ Unlevered FCF │ ...   │ =E57+E58-E60-E62      │ ...                        │
├──────────────────────────────────────────────────────────────────────────────┤
│ Section 6: Discounting & Valuation                                         │
│ Unlevered FCF │ XXX   │ XXX   │ XXX   │ XXX   │ XXX                       │
│ Period        │ 0.5   │ 1.5   │ 2.5   │ 3.5   │ 4.5                       │
│ Discount Factor│ 0.95 │ 0.87  │ 0.79  │ 0.72  │ 0.66                      │
│ PV of FCF     │ XXX   │ XXX   │ XXX   │ XXX   │ XXX                       │
│                                                                            │
│ Terminal FCF / Terminal Value / PV Terminal Value                          │
│                                                                            │
│ Valuation Summary:                                                         │
│ Sum of PV FCFs          = XXX                                              │
│ PV Terminal Value       = XXX                                              │
│ Enterprise Value        = XXX                                              │
│ (-) Net Debt            = (XX)                                             │
│ Equity Value            = XXX                                              │
│ Shares Outstanding (M)  = XX.X                                             │
│ IMPLIED PRICE PER SHARE = $XX.XX                                           │
│ Current Stock Price     = $XX.XX                                           │
│ Implied Upside/(Downside)= XX%                                             │
├──────────────────────────────────────────────────────────────────────────────┤
│ Section 7: Sensitivity Analysis (3张 5×5 表，在 DCF Sheet 底部)              │
│                                                                            │
│ Table 1: WACC vs Terminal Growth → 隐含股价                                │
│           │  2.0%  │  2.5%  │  3.0%  │  3.5%  │  4.0%                     │
│   8.0%    │ [fml]  │ [fml]  │ [fml]  │ [fml]  │ [fml]                     │
│   8.5%    │ [fml]  │ [fml]  │ [fml]  │ [fml]  │ [fml]                     │
│   9.0%    │ [fml]  │ [fml]  │ [★]   │ [fml]  │ [fml]  ← 中心格=Base     │
│   9.5%    │ [fml]  │ [fml]  │ [fml]  │ [fml]  │ [fml]                     │
│  10.0%    │ [fml]  │ [fml]  │ [fml]  │ [fml]  │ [fml]                     │
│                                                                            │
│ Table 2: Revenue Growth vs EBIT Margin                                     │
│ Table 3: Beta vs Risk-Free Rate                                            │
│ (共 75 个公式单元格，全部用公式而非硬编码)                                    │
└──────────────────────────────────────────────────────────────────────────────┘

```
## Sheet 1: DCF（主估值模型）

### Section 1: Header（标题区）

``` excel
Row 1: [Company Name] DCF Model
Row 2: Ticker: [XXX] | Date: [Date] | Year End: [FYE]
Row 3: [空白行]
Row 4: Case Selector Cell ← 1=Bear, 2=Base, 3=Bull（蓝色输入）
Row 5: Case Name Display ← =IF(B6=1,"Bear",IF(B6=2,"Base","Bull"))（黑色公式）
```

### Section 2: Market Data（市场数据，非情景依赖）

| Item | Value | 类型 |
|---|---|---|
| Current Stock Price | $XX.XX | 蓝色硬编码 + cell comment 来源 |
| Shares Outstanding (M) | XX.X | 蓝色硬编码 + cell comment 来源 |
| Market Cap ($M) | =Stock Price × Shares | 黑色公式 |
| Net Debt ($M) | XXX | 蓝色硬编码（Net Cash 为负数标注） |

### Section 3: DCF Scenario Assumptions（三情景假设块）

**结构要求**：每个情景一个独立 block，假设横向展开跨预测年。

```excel
┌─────────────────────────────────────────────────────────┐
│ BEAR CASE ASSUMPTIONS (section header, merge A:G) │
├──────────────────┬───────┬───────┬───────┬───────┬───────┤
│ Assumption │ FY1 │ FY2 │ FY3 │ FY4 │ FY5 │ ← 列头行（必须）
├──────────────────┼───────┼───────┼───────┼───────┼───────┤
│ Revenue Growth % │ 12% │ 10% │ 9% │ 8% │ 7% │
│ EBIT Margin % │ 45% │ 44% │ 43% │ 42% │ 41% │
│ Tax Rate % │ 25% │ 25% │ 25% │ 25% │ 25% │
│ D&A % of Revenue │ 3% │ 3% │ 3% │ 3% │ 3% │
│ CapEx % of Rev │ 5% │ 5% │ 5% │ 4% │ 4% │
│ NWC % of ΔRev │ 2% │ 2% │ 2% │ 1% │ 1% │
│ Terminal Growth │ 2.0% │ │ │ │ │
│ WACC │ 10.0% │ │ │ │ │
└──────────────────┴───────┴───────┴───────┴───────┴───────┘

┌─────────────────────────────────────────────────────────┐
│ BASE CASE ASSUMPTIONS (同结构，假设值不同) │
│ ... │
│ Revenue Growth % │ 16% │ 14% │ 12% │ 10% │ 9% │
│ EBIT Margin % │ 48% │ 49% │ 50% │ 51% │ 52% │
│ Terminal Growth │ 3.0% │ │ │ │ │
│ WACC │ 9.0% │ │ │ │ │
└──────────────────┴───────┴───────┴───────┴───────┴───────┘

┌─────────────────────────────────────────────────────────┐
│ BULL CASE ASSUMPTIONS (同结构，假设值不同) │
│ ... │
│ Revenue Growth % │ 20% │ 18% │ 15% │ 13% │ 11% │
│ EBIT Margin % │ 50% │ 51% │ 52% │ 53% │ 54% │
│ Terminal Growth │ 4.0% │ │ │ │ │
│ WACC │ 8.0% │ │ │ │ │
└──────────────────┴───────┴───────┴───────┴───────┴───────┘
```

**Consolidation Column（聚合列）**：
```excel
=INDEX([Bear block]:[Bull block], 1, $B$6)   ← 聚合列逻辑
```
所有后续预测公式引用聚合列，不用散落的 IF 嵌套。

### Section 4: Historical & Projected Financials（历史与预测财务）
```csv
Item,               2020A, 2021A, 2022A, 2023A, 2024E, 2025E, 2026E
Revenue,            蓝输入, 蓝输入, 蓝输入, 蓝输入, =E29*(1+$E$10), =F29*(1+$E$11), ...
  % growth,         黑公式, 黑公式, 黑公式, 黑公式, =E29/D29-1,     =F29/E29-1,     ...
Gross Profit,       黑公式, 黑公式, 黑公式, 黑公式, =E29*E33,       =F29*F33,       ...
  % margin,         黑公式, 黑公式, 黑公式, 黑公式, =E33/E29,       =F33/F29,       ...
Operating Expenses:
  S&M,              黑公式, 黑公式, 黑公式, 黑公式, =E29*0.15,      =F29*0.14,      ...
  R&D,              黑公式, 黑公式, 黑公式, 黑公式, =E29*0.12,      =F29*0.11,      ...
  G&A,              黑公式, 黑公式, 黑公式, 黑公式, =E29*0.08,      =F29*0.07,      ...
  Total OpEx,       黑公式, 黑公式, 黑公式, 黑公式, =E36+E37+E38,   ...
EBIT,               黑公式, 黑公式, 黑公式, 黑公式, =E33-E39,       =F33-F39,       ...
  % margin,         黑公式, 黑公式, 黑公式, 黑公式, =E41/E29,       =F41/F29,       ...
Taxes,              黑公式, 黑公式, 黑公式, 黑公式, =E41*$E$24,     ...
  Tax rate,         黑公式, 黑公式, 黑公式, 黑公式, =E43/E41,       ...
NOPAT,              黑公式, 黑公式, 黑公式, 黑公式, =E41-E43,       =F41-F43,       ...
```
#### 关键规则：
- OpEx 基于 Revenue（不是 Gross Profit）
- 所有预测值引用聚合列 $E$10，不是 IF($B$6=1,...,IF($B$6=2,...,...))

### Section 5: Free Cash Flow Build（自由现金流构建）
```csv
Item,               2020A, 2021A, 2022A, 2023A, 2024E, 2025E, 2026E
NOPAT,              =E45,  =F45,  =G45,  ...          ← 引用 Section 4
(+) D&A,            =E29*$E$21, =F29*$E$21, ...       ← Revenue × 聚合列 D&A%
  % of Rev,         =E58/E29,   =F58/F29,   ...
(-) CapEx,          =E29*$E$22, =F29*$E$22, ...       ← Revenue × 聚合列 CapEx%
  % of Rev,         =E60/E29,   =F60/F29,   ...
(-) ΔNWC,           =(E29-D29)*$E$23, ...              ← ΔRevenue × 聚合列 NWC%
  % of ΔRev,        =E62/(E29-D29), ...
Unlevered FCF,      =E45+E58-E60-E62, ...              ← NOPAT+D&A-CapEx-ΔNWC
```
#### 关键公式：
``` 
FCF = NOPAT + D&A - CapEx - ΔNWC
```

### Section 6: WACC & Discounting（折现计算）
```csv 
Item,                       Value
WACC,                       =WACC!B20                  ← 绿色跨 Sheet 引用
Discount Period (Mid-Year), 0.5, 1.5, 2.5, 3.5, 4.5
Discount Factor,            =1/(1+WACC)^Period
PV of FCF,                  =FCF × Discount Factor
```
半年惯例：现金流发生在年中，折现期 = 0.5, 1.5, 2.5...

### Section 7: Terminal Value（终值）
```csv 
Item,                           Value
Terminal Growth Rate,           =聚合列 TGR               ← 蓝色/引用
Terminal FCF,                   =Final Year FCF × (1+TGR)
Terminal Value (Perpetuity),    =Terminal FCF / (WACC - TGR)
PV of Terminal Value,           =Terminal Value / (1+WACC)^FinalPeriod
```
#### 合理性检验：
- TV 占 EV 比例应在 50–70%
    * 75% → 过度依赖远期假设
- <40% → 终值假设过于保守
- TGR 必须 < WACC（否则无限大值）

### Section 8: Valuation Summary（估值汇总 → EV→Equity Bridge）
``` 
┌──────────────────────────────────────────────┐
│ VALUATION SUMMARY                            │
├──────────────────────────────────────────────┤
│ (+) Sum of PV of Projected FCFs  = $X M      │
│ (+) PV of Terminal Value         = $Y M      │
│ = Enterprise Value               = $Z M      │
│                                              │
│ (-) Net Debt                     = $A M      │
│ = Equity Value                   = $B M      │
│                                              │
│ ÷ Diluted Shares Outstanding     = C M shares│
│ = Implied Price per Share        = $XX.XX    │
│                                              │
│ Current Stock Price              = $YY.YY    │
│ Implied Upside/(Downside)        = +XX%      │
└──────────────────────────────────────────────┘
```
关键调整：
- Net Debt 为正 → 从 EV 中减去
- Net Debt 为负（Net Cash）→ 加到 EV 上
- 用稀释股数（含 options、RSUs、可转债）

### Section 9: Sensitivity Analysis（敏感性分析，3 张 5×5 表）
位于 DCF sheet 底部，不是独立 sheet。

#### 表 1: WACC × Terminal Growth Rate → Implied Share Price

|          | 2.0% | 2.5%  | 3.0%  | 3.5%  | 4.0%  |
|----------|------|-------|-------|-------|-------|
| 8.0%     | [完整DCF重算] | [fml] | [fml] | [fml] | [fml] |
| 8.5%     | [fml] | [fml] | [fml] | [fml] | [fml] |
| **9.0%** | [fml] | [fml] | ★     | [fml] | [fml] |
| 9.5%     | [fml] | [fml] | [fml] | [fml] | [fml] |
| 10.0%    | [fml] | [fml] | [fml] | [fml] | [fml] |
★ 中心格 = Base case 隐含股价，必须等于 Valuation Summary 的输出值

#### 表 2: Revenue Growth × EBIT Margin → Implied Share Price
轴值围绕 base case 对称展开：[base-2step, base-step, base, base+step, base+2step]

#### 表 3: Beta × Risk-Free Rate → Implied Share Price
实现要求：
- 奇数维度（5×5），保证中心格存在
- 每格都是完整 DCF 重算公式（不是线性近似），共 75 个公式（3×25）
- 中心格 #BDD7EE 蓝色填充 + 粗体
- 不使用 Excel Data Table 功能

## Sheet 2: WACC（资本成本构建）
### 结构
```csv 
Item,                                    Value
COST OF EQUITY (CAPM)
Risk-Free Rate (10Y Treasury),           XX.X%      ← 蓝色输入 + comment
Equity Risk Premium,                     5.5%       ← 蓝色输入 + comment
Beta (5Y Monthly),                       X.XX       ← 蓝色输入 + comment
Cost of Equity,                          =Rf+β×ERP  ← 黑色公式
 
COST OF DEBT
Pre-Tax Cost of Debt,                    XX%        ← 蓝色输入 + comment
Tax Rate,                                XX%        ← 蓝色输入 + comment
After-Tax Cost of Debt,                  =Pre×(1-T) ← 黑色公式
 
CAPITAL STRUCTURE
Market Cap ($M),                         =DCF!引用   ← 绿色跨 Sheet
Net Debt ($M),                           =DCF!引用   ← 绿色跨 Sheet
Enterprise Value ($M),                   =MCap+NetD ← 黑色公式
Equity Weight,                           =MCap/EV   ← 黑色公式
Debt Weight,                             =NetD/EV   ← 黑色公式
 
WACC
WACC,                                    =Ke×We+Kd×Wd ← 黑色公式（关键产出）
```
### WACC 典型区间

|   公司类型       | WACC | 说明 | 
|----------|------|-------|
| 大型稳健   | 7–9%| 低 β、高债务比例降低整体成本 |
| 成长型   |9–12%	 | 高 β、净现金多于债务 | 
| 高风险 |12–15% |β 远大于 1  | 

特殊情况
- Net Cash 公司：Debt Weight 为负，WACC 相应降低
- 无债务公司：WACC = Cost of Equity
		
## 跨 Sheet 数据流
``` 
WACC Sheet ──→ DCF Sheet
  WACC 值 ─────→ 折现因子计算
  WACC 值 ─────→ 终值分母 (WACC - TGR)

DCF Sheet 内部：
  Section 3 (Assumptions) ──→ Section 4 (Financials) ──→ Section 5 (FCF)
  Section 2 (Market Data) ──→ Section 8 (Valuation Summary)
  Section 6+7 (PV+TV) ────→ Section 8 (Valuation Summary)
  Section 8 (Valuation) ──→ Section 9 (Sensitivity 中心格验证)

```
## 颜色编码总结

### 字体颜色（标识"是什么"）
		
|   颜色       | 含义 | 示例 | 
|----------|------|-------|
| 蓝色 0000FF   |硬编码输入| Stock Price, Growth %, Beta, TGR |
| 黑色 000000   |公式计算	 | =E29*(1+$E$10), =SUM() | 
| 绿色 008000 |跨 Sheet 引用 |=WACC!B20, =DCF!C10  | 
	
		
### 填充颜色（标识"在哪里"）

原则：字体颜色告诉你是什么（输入/公式/链接），填充颜色告诉你在哪里（标题/数据/产出）。
颜色与用途的对照关系：
- #1F4E79 深蓝 ：	Section headers
- #D9E1F2 浅蓝 ：	Column / sub-headers
- #F2F2F2 浅灰 ：	Input cells（配合蓝色字体）
- 白色 ：	Formula / calculated cells
- #BDD7EE 中蓝 ： Key outputs（Implied Price, EV, Equity Value）
## 边框标准
线宽与应用范围对应如下：
- 1.5pt粗线：KEY INPUTS / PROJECTION / TERMINAL VALUE / VALUATION SUMMARY / 每个 SENSITIVITY 表
- 1pt中线：子分区之间（Company Details vs Historical / Growth vs EBIT vs FCF）
- 0.5pt细线：情景假设表 / 历史vs预测矩阵 
- 无边框：表格内单元格

## 数字格式标准
- 年份:文本字符串，例如 "2024"（不是 2,024）
- 百分比：0.0%，例如 12.3% 
- 美元金额：$#,##0，例如 69,632 
- 每股值：\$#,##0.00，例如 $142.58 
- 倍数：0.0"x"，例如 13.5x 
- 零值：显示为 "-"，例如 \$#,##0;($#,##0);"-"
- 负数：括号表示，例如 (#,##0)，不用减号

## Cell Comment 规范（所有硬编码输入必须）
- 格式："Source: [System/Document], [Date], [Reference], [URL if applicable]"
- 示例：
  * Source: Market data script 2025-10-12 Close price
  * Source: 10-K FY2024 Page 45 Note 12
  * Source: Management guidance Q3 2024 earnings call
必须随单元格创建时同步添加，不可延后或写 "TODO"。

# 验证清单
完成模型后必须通过以下检查：
- Margin 逻辑：Gross Margin > EBITDA Margin > Net Margin
- TGR < WACC（否则终值无限大）
- TV 占 EV 50–70%（>75% 或 <40% 需标注红旗）
- OpEx 基于 Revenue（不是 Gross Profit）
- 情景假设引用聚合列（不是散落 IF 嵌套）
- 敏感性表中心格 = Valuation Summary 实际输出值
- 所有蓝色输入有 cell comment 标注来源
- Net Debt 正/负正确反映在 EV→Equity bridge 中