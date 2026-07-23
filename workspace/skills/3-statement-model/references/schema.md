# 3-Statement Model — 标准模板结构说明

## 概述

3-Statement Model（三表联动模型）是财务建模的基础框架，将 **利润表**、**资产负债表** 和 **现金流量表** 三张报表通过公式勾稽关系链接在一起，确保任何输入变化自动传递到所有报表。

| Tab | 内容 | 核心功能 |
|---|---|---|
| Assumptions | 关键假设输入 | 驱动所有预测的核心参数 |
| Income Statement | 利润表 | 收入、成本、利润预测 |
| Balance Sheet | 资产负债表 | 资产、负债、权益的滚动推导 |
| Cash Flow | 现金流量表 | 经营/投资/筹资现金流，连接 IS 与 BS |
| Supporting Schedules | 辅助计算表 | 债务滚动、折旧摊销、营运资金等 |

**关键设计**：三张报表的勾稽关系是模型的核心 — BS 必须平衡（Assets = Liabilities + Equity），CF 的期末现金必须等于 BS 上的现金余额。
 
---

## Tab 1: Assumptions（关键假设）

### 结构

```csv
Section,                     Item,                   Value
HEADER:                      Company Name,           [蓝色输入]
                             Ticker,                 [蓝色输入]
                             Date,                   [蓝色输入]
                             Fiscal Year End,        [蓝色输入]
 
MARKET DATA:                 Current Stock Price,    $XX.XX  ← 蓝色输入 + comment
                             Shares Outstanding,     XX.X M  ← 蓝色输入 + comment
 
REVENUE ASSUMPTIONS:         Revenue Growth %,       FY1→FY5 ← 蓝色输入（每年一列）
                             Revenue Segments,       分拆增长率（可选）
 
COST ASSUMPTIONS:            COGS % of Revenue,      XX%     ← 蓝色输入
                             Gross Margin %,         XX%     ← 蓝色输入
                             S&M % of Revenue,       XX%     ← 蓝输入（可逐年变化）
                             R&D % of Revenue,       XX%     ← 蓝输入
                             G&A % of Revenue,       XX%     ← 蓝输入
                             D&A % of Revenue,       XX%     ← 蓝输入（或单独 schedule）
                             Tax Rate,               XX%     ← 蓝色输入
 
BALANCE SHEET ASSUMPTIONS:   D&A Schedule,           → D&A tab 详细推导
                             CapEx % of Revenue,     XX%     ← 蓝色输入
                             AR Days,                XX days ← 蓝色输入
                             Inventory Days,         XX days ← 蓝色输入
                             AP Days,                XX days ← 蓝色输入
                             Other Current Assets,   % of Rev← 蓝色输入
 
DEBT ASSUMPTIONS:            Debt Schedule,           → Debt tab 详细推导
                             Interest Rate,          XX%     ← 蓝色输入（每 tranche）
                             Mandatory Repayment,    $XX M/yr← 蓝色输入
                             Cash Sweep %,           XX%     ← 蓝色输入
 
DIVIDEND ASSUMPTIONS:        Dividend Payout Ratio,  XX%     ← 蓝色输入（或 $/share）
                             Share Repurchase,       $XX M   ← 蓝色输入（可选）
```
**关键原则**：所有假设集中在此 tab，后续报表只引用不重复输入。蓝色字体 + cell comment 标注来源（10-K page / earnings call / management guidance）。

## Tab 2: Income Statement（利润表）
### 结构
```csv
Item,                              2020A,  2021A,  2022A,  2023A,  2024E,  2025E,  2026E
 
ASSETS
Current Assets:
  Cash & Equivalents,              绿引用,  绿引用,  绿引用,  绿引用,  =CF!Ending Cash, ...  ← 从 CF 引入
  Accounts Receivable,             黑公式,  黑公式,  黑公式,  黑公式,  =Rev×(AR Days/365), ...
  Inventory,                       黑公式,  黑公式,  黑公式,  黑公式,  =COGS×(Inv Days/365), ...
  Other Current Assets,            黑公式,  黑公式,  黑公式,  黑公式,  =Rev×Other CA %, ...
  Total Current Assets,            黑公式,  黑公式,  黑公式,  黑公式,  =Cash+AR+Inv+Other, ...
 
Non-Current Assets:
  PP&E (Gross),                    黑公式,  黑公式,  黑公式,  黑公式,  =Prior+CapEx, ...
  Accumulated D&A,                 黑公式,  黑公式,  黑公式,  黑公式,  =Prior+D&A, ...  ← 从 D&A Schedule
  PP&E (Net),                      黑公式,  黑公式,  黑公式,  黑公式,  =Gross-AccD&A, ...
  Intangible Assets,               蓝输入,  蓝输入,  蓝输入,  蓝输入,  蓝输入, ...       ← 或 Amort Schedule
  Other Non-Current Assets,        蓝输入,  蓝输入,  蓝输入,  蓝输入,  蓝输入, ...
  Total Non-Current Assets,        黑公式,  黑公式,  黑公式,  黑公式,  =Net PP&E+Intang+Other, ...
 
Total Assets,                      黑公式,  黑公式,  黑公式,  黑公式,  =CA+NCA, ...
 
LIABILITIES
Current Liabilities:
  Accounts Payable,                黑公式,  黑公式,  黑公式,  黑公式,  =COGS×(AP Days/365), ...
  Short-Term Debt,                 绿引用,  绿引用,  绿引用,  绿引用,  =Debt Schedule!Current, ...
  Other Current Liabilities,       蓝输入,  蓝输入,  蓝输入,  蓝输入,  =Rev×Other CL %, ...
  Total Current Liabilities,       黑公式,  黑公式,  黑公式,  黑公式,  =AP+STD+Other, ...
 
Non-Current Liabilities:
  Long-Term Debt,                  绿引用,  绿引用,  绿引用,  绿引用,  =Debt Schedule!LT, ...
  Other LT Liabilities,            蓝输入,  蓝输入,  蓝输入,  蓝输入,  蓝输入, ...
  Total Non-Current Liabilities,   黑公式,  黑公式,  黑公式,  黑公式,  =LTD+Other, ...
 
Total Liabilities,                 黑公式,  黑公式,  黑公式,  黑公式,  =CL+NCL, ...
 
EQUITY
  Common Stock / Paid-in Capital,  蓝输入,  蓝输入,  蓝输入,  蓝输入,  蓝输入, ...       ← 通常不变
  Retained Earnings,               黑公式,  黑公式,  黑公式,  黑公式,  =Prior+NI-Dividends, ...
  Treasury Stock,                  蓝输入,  蓝输入,  蓝输入,  蓝输入,  =Prior+Repurchase, ...
  Other Equity,                    蓝输入,  蓝输入,  蓝输入,  蓝输入,  蓝输入, ...
  Total Equity,                    黑公式,  黑公式,  黑公式,  黑公式,  =Common+RE-TS+Other, ...
 
Total Liabilities & Equity,        黑公式,  黑公式,  黑公式,  黑公式,  =TL+TE, ...
 
Balance Check,                     黑公式,  黑公式,  黑公式,  黑公式,  =TA-TL&TE, ...   ← 必须为 0
```
关键勾稽关系：
- Cash = CF Statement 的期末现金（绿色跨 Tab 引用）
- AR / Inventory / AP → 引用 Assumptions 的 Days 指标 × 对应基数
- D&A → 引用 D&A Schedule（影响 PP&E Net 和 Acc D&A）
- Debt → 引用 Debt Schedule（拆分 Current / Long-Term）
- Retained Earnings = Prior + Net Income - Dividends（从 IS 引入 NI）
- Balance Check = Total Assets - Total Liabilities & Equity = 0（硬性勾稽）

## Tab 4: Cash Flow Statement（现金流量表）
### 结构
```csv 
Item,                              2020A,  2021A,  2022A,  2023A,  2024E,  2025E,  2026E
 
OPERATING CASH FLOW:
Net Income,                        绿引用,  绿引用,  绿引用,  绿引用,  =IS!Net Income, ...   ← 从 IS 引入
(+) D&A,                           绿引用,  绿引用,  绿引用,  绿引用,  =D&A Schedule!D&A, ...
(-) Δ Accounts Receivable,         黑公式,  黑公式,  黑公式,  黑公式,  =Prior AR-Current AR, ...
(-) Δ Inventory,                   黑公式,  黑公式,  黑公式,  黑公式,  =Prior Inv-Current Inv, ...
(+) Δ Accounts Payable,            黑公式,  黑公式,  黑公式,  黑公式,  =Current AP-Prior AP, ...
(-) Δ Other Current Assets,        黑公式,  黑公式,  黑公式,  黑公式,  =Prior OCA-Current OCA, ...
(+) Δ Other Current Liabilities,   黑公式,  黑公式,  黑公式,  黑公式,  =Current OCL-Prior OCL, ...
Net Operating Cash Flow,           黑公式,  黑公式,  黑公式,  黑公式,  =NI+D&A+ΔNWC, ...
 
INVESTING CASH FLOW:
(-) Capital Expenditure,           黑公式,  黑公式,  黑公式,  黑公式,  =Rev×CapEx%, ...       ← 或 =D&A Schedule!CapEx
(-) Acquisitions,                  蓝输入,  蓝输入,  蓝输入,  蓝输入,  蓝输入, ...
(+) Asset Dispositions,            蓝输入,  蓝输入,  蓝输入,  蓝输入,  蓝输入, ...
Net Investing Cash Flow,           黑公式,  黑公式,  黑公式,  黑公式,  =-CapEx+Acq+Disp, ...
 
FINANCING CASH FLOW:
(-) Debt Repayment,                绿引用,  绿引用,  绿引用,  绿引用,  =Debt Schedule!Total Paydown, ...
(+) Debt Issuance,                 绿引用,  绿引用,  绿引用,  绿引用,  =Debt Schedule!Issuance, ...
(-) Dividends Paid,                黑公式,  黑公式,  黑公式,  黑公式,  =NI×DivPayout%, ...
(-) Share Repurchases,             黑公式,  黑公式,  黑公式,  黑公式,  =Assump!引用, ...
(+) Other Financing,               蓝输入,  蓝输入,  蓝输入,  蓝输入,  蓝输入, ...
Net Financing Cash Flow,           黑公式,  黑公式,  黑公式,  黑公式,  =Debt Repay-Issue+Div+Rep+Other, ...
 
NET CHANGE IN CASH:
Net Cash Flow,                     黑公式,  黑公式,  黑公式,  黑公式,  =OpCF+InvCF+FinCF, ...
Beginning Cash,                    黑公式,  黑公式,  黑公式,  黑公式,  =Prior Ending Cash, ...
Ending Cash,                       黑公式,  黑公式,  黑公式,  黑公式,  =Beg+Net CF, ...      ← → BS Cash
```
**关键勾稽关系**：
- Net Income → 从 IS 引入（绿色）
- D&A → 从 D&A Schedule 引入（绿色）
- ΔNWC → 计算：资产增加是现金流出（-），负债增加是现金流入（+）
- CapEx → 引用 Assumptions 或 D&A Schedule
- Debt Repayment / Issuance → 从 Debt Schedule 引入（绿色）
- Ending Cash → 传递到 BS 的 Cash & Equivalents

**ΔNWC 符号规则**：


| 项目 | 增加 | 减少 | CF 影响 |
|---|---|---|---|
| AR | 现金流出 | 现金流入 | Δ = Prior - Current |
| Inventory | 现金流出 | 现金流入| Δ = Prior - Current |
| AP | 现金流入 | 现金流出 | Δ = Current - Prior |


## Tab 5: Supporting Schedules（辅助计算表）
### 5a: D&A & PP&E Schedule（折旧摊销与固定资产）
```csv
Item,                              2020A,  2021A,  2022A,  2023A,  2024E,  2025E,  2026E
PP&E Beginning Balance,            蓝输入,  黑公式,  黑公式,  黑公式,  =Prior Ending, ...
(+) Capital Expenditure,           蓝输入,  蓝输入,  蓝输入,  蓝输入,  =Rev×CapEx%, ...
(-) Dispositions,                  蓝输入,  蓝输入,  蓝输入,  蓝输入,  蓝输入, ...
PP&E Ending Balance (Gross),       黑公式,  黑公式,  黑公式,  黑公式,  =Beg+CapEx-Disp, ...
 
Acc D&A Beginning,                 蓝输入,  黑公式,  黑公式,  黑公式,  =Prior Ending, ...
(+) Depreciation,                  黑公式,  黑公式,  黑公式,  黑公式,  =PP&E Net×Dep Rate, ...  ← 或直线法
(+) Amortization (Intangibles),    黑公式,  黑公式,  黑公式,  黑公式,  =Intang×Amort Rate, ...
(-) D&A on Dispositions,           蓝输入,  蓝输入,  蓝输入,  蓝输入,  蓝输入, ...
Acc D&A Ending,                    黑公式,  黑公式,  黑公式,  黑公式,  =Beg+Dep+Amort-DispD&A, ...
 
Total D&A,                         黑公式,  黑公式,  黑公式,  黑公式,  =Dep+Amort, ...      ← → IS 和 CF
PP&E Net,                          黑公式,  黑公式,  黑公式,  黑公式,  =Gross-AccD&A, ...   ← → BS
```
计算方法（取决于行业和公司政策）：
- 直线法：Depreciation = PP&E Gross / Useful Life
- 加速折旧法：每年递减比例
- 按比例法：Depreciation = PP&E Net × X%
### 5b: Debt Schedule（债务滚动）
```csv
Item,                              2020A,  2021A,  2022A,  2023A,  2024E,  2025E,  2026E
 
[Tranche A — Senior Debt]:
Beginning Balance,                 蓝输入,  黑公式,  黑公式,  黑公式,  =Prior Ending, ...
(+) Issuance,                      蓝输入,  蓝输入,  蓝输入,  蓝输入,  蓝输入, ...         ← 仅在新增借款年
(-) Mandatory Repayment,           蓝输入,  蓝输入,  蓝输入,  蓝输入,  蓝输入, ...
(-) Cash Sweep (Optional),         黑公式,  黑公式,  黑公式,  黑公式,  =MIN(AvailCash×Sweep%, Remaining), ...
Interest,                          黑公式,  黑公式,  黑公式,  黑公式,  =Beg×Rate, ...      ← 用期初余额
Ending Balance,                    黑公式,  黑公式,  黑公式,  黑公式,  =Beg+Issuance-Repay-Sweep, ...
 
[Tranche B — Sub / Revolver]:
(同结构，优先级低于 Tranche A)
 
Total Debt,                        黑公式,  黑公式,  黑公式,  黑公式,  =Sum(All Tranches Ending), ...
Total Interest,                    黑公式,  黑公式,  黑公式,  黑公式,  =Sum(All Tranches Interest), ... ← → IS
Current Portion,                   黑公式,  黑公式,  黑公式,  黑公式,  =Mandatory+Sweep(NextYr), ...   ← → BS Current
Long-Term Portion,                 黑公式,  黑公式,  黑公式,  黑公式,  =Total-Current, ...             ← → BS Non-Current
```
关键公式：
- Interest = Beginning Balance × Rate（断开循环引用）
- Cash Sweep = MIN(Available Cash × Sweep %, Remaining Balance)
- Current Portion = 下一年 Mandatory + Sweep → 传递到 BS Short-Term Debt
- Revolver：余额可增可减，但有上限和下限限制
### 5c: Working Capital Schedule（营运资金）
```csv
Item,                              2020A,  2021A,  2022A,  2023A,  2024E,  2025E,  2026E
 
Accounts Receivable:
AR Days,                           蓝输入,  蓝输入,  蓝输入,  蓝输入,  =Assump!引用, ...
AR Balance,                        黑公式,  黑公式,  黑公式,  黑公式,  =Rev×(AR Days/365), ...
Δ AR,                              黑公式,  黑公式,  黑公式,  黑公式,  =Prior-Current, ...
 
Inventory:
Inventory Days,                    蓝输入,  蓝输入,  蓝输入,  蓝输入,  =Assump!引用, ...
Inventory Balance,                 黑公式,  黑公式,  黑公式,  黑公式,  =COGS×(Inv Days/365), ...
Δ Inventory,                       黑公式,  黑公式,  黑公式,  黑公式,  =Prior-Current, ...
 
Accounts Payable:
AP Days,                           蓝输入,  蓝输入,  蓝输入,  蓝输入,  =Assump!引用, ...
AP Balance,                        黑公式,  黑公式,  黑公式,  黑公式,  =COGS×(AP Days/365), ...
Δ AP,                              黑公式,  黑公式,  黑公式,  黑公式,  =Current-Prior, ...
 
Other Current Assets:
OCA % of Revenue,                  蓝输入,  蓝输入,  蓝输入,  蓝输入,  =Assump!引用, ...
OCA Balance,                       黑公式,  黑公式,  黑公式,  黑公式,  =Rev×OCA%, ...
Δ OCA,                             黑公式,  黑公式,  黑公式,  黑公式,  =Prior-Current, ...
 
Other Current Liabilities:
OCL % of Revenue,                  蓝输入,  蓝输入,  蓝输入,  蓝输入,  =Assump!引用, ...
OCL Balance,                       黑公式,  黑公式,  黑公式,  黑公式,  =Rev×OCL%, ...
Δ OCL,                             黑公式,  黑公式,  黑公式,  黑公式,  =Current-Prior, ...
 
Total Δ NWC,                       黑公式,  黑公式,  黑公式,  黑公式,  =ΔAR-ΔInv+ΔAP-ΔOCA+ΔOCL, ... ← → CF
```
## 跨 Tab 数据流
```
Assumptions ───────────→ 所有 Tab 的蓝色输入源
                         Growth %, Margin %, Days, Rates, etc.

Income Statement ─────→ Cash Flow Statement
  Net Income ────────→ Operating CF 起点

  Interest Expense ──← Debt Schedule（Total Interest）
  D&A ──────────────← D&A Schedule（Total D&A）

Balance Sheet ─────────────────────────────────
  Cash ─────────────← Cash Flow Statement（Ending Cash）
  AR/Inv/AP ───────← Working Capital Schedule
  PP&E Net ────────← D&A Schedule（PP&E Gross - Acc D&A）
  Debt (Current/LT)← Debt Schedule（Current / Long-Term Portion）
  Retained Earnings← IS Net Income - Dividends

Cash Flow Statement ──→ Balance Sheet
  Ending Cash ──────→ BS Cash & Equivalents（核心勾稽）

D&A Schedule ─────────→ IS（D&A）+ CF（D&A）+ BS（PP&E Net, Acc D&A）
Debt Schedule ────────→ IS（Interest）+ CF（Repayment/Issuance）+ BS（Debt）
WC Schedule ──────────→ BS（AR/Inv/AP/OCA/OCL）+ CF（ΔNWC）
```
			
核心循环（必须断开）：
```
Interest → 减少 Net Income → 减少 Operating CF → 减少可用还款 → 影响 Ending Debt → 影响 Interest
```
解决方案：Interest 用期初债务余额计算（不依赖期末余额），与 LBO Debt Schedule 同理。	


## 颜色编码总结

### 字体颜色（标识"是什么"）

|   颜色       | 含义 | 示例 | 
|----------|------|-------|
| 蓝色 0000FF   |硬编码输入| Historical Revenue, Growth %, Days, Tax Rate |
| 黑色 000000   |公式计算	 | =E29*(1+Growth%), =NI+D&A+ΔNWC | 
|紫色 800080	|同 Tab 引用	|=B9, =D45|
| 绿色 008000 |跨 Sheet 引用 |=IS!NetIncome, =Debt!Interest, =CF!EndingCash  | 


### 填充颜色（标识"在哪里"）

原则：字体颜色告诉你是什么（输入/公式/链接），填充颜色告诉你在哪里（标题/数据/产出）。
颜色与用途的对照关系：
- #1F4E79 深蓝 ：	Section headers（"INCOME STATEMENT"、"BALANCE SHEET"等）
- #D9E1F2 浅蓝 ：	Column / sub-headers
- #F2F2F2 浅灰 ：	Input cells（配合蓝色字体）
- 白色 ：	Formula / calculated cells
- #BDD7EE 中蓝 ： Key outputs（Implied Price, EV, Equity Value）
## 边框标准
线宽与应用范围对应如下：
- 1.5pt粗线：每张报表主体（IS / BS / CF / 每个 Schedule）
- 1pt中线：子分区之间（Current Assets vs Non-Current / OpCF vs InvCF vs FinCF）
- 0.5pt细线：辅助表格（WC detail / D&A detail）
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
- 勾稽平衡（最关键）
  * BS Balance Check = 0：Total Assets = Total Liabilities + Equity（每年）
  * CF Ending Cash = BS Cash & Equivalents（每年）
  * Roll-forward schedules：Beginning + Changes = Ending（D&A, Debt, PP&E）
- 利润表逻辑
  * Gross Margin > EBITDA Margin > Net Margin（数学定义恒成立）
  * OpEx 基于 Revenue（不是 Gross Profit）
  * Interest 引用 Debt Schedule 期初余额
  * D&A 引用 D&A Schedule
- 资产负债表逻辑
  * AR = Revenue × (AR Days / 365)
  * Inventory = COGS × (Inv Days / 365)
  * AP = COGS × (AP Days / 365)
  * Retained Earnings = Prior + NI - Dividends
  * Debt Current/LT 拆分与 Debt Schedule 一致
- 现金流量表逻辑
  * ΔNWC 符号正确：资产增加 = 现金流出（-），负债增加 = 现金流入（+）
  * Operating CF = NI + D&A + ΔNWC
  * Ending Cash = Beginning + Net CF（与 BS Cash 一致）
- 公式完整性
  - 所有公式零错误（运行 recalc.py 返回 status: success）
  - 所有蓝色输入有 cell comment
  - 无硬编码在公式中（所有数字来自蓝色输入或引用单元格）
