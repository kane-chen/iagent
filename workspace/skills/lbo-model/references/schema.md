# LBO Model — 输出Excel结构说明

## 概述

标准 LBO 模型模板包含四个 Tab，按计算依赖顺序排列：

| Tab | 内容 | 核心功能 |
|---|---|---|
| Sources & Uses | 资金来源与用途 | 确定交易结构和股权出资额 |
| Operating Model | 经营模型 | 预测持有期内的收入、利润和现金流 |
| Debt Schedule | 债务偿还计划 | 用 FCF 逐年偿还债务 |
| Returns Analysis | 回报分析 + 敏感性表 | 计算 IRR/MOIC 并展示关键假设敏感性 |

## 结构示例
``` 
┌──────────────────────────────────────────────────────────────────────────────┐
│ Section 1: Sources & Uses (资金来源与用途)                                   │
│                                                                            │
│ SOURCES                     │ Amount ($M)                                  │
│ Revolving Credit Facility   │ [蓝色输入]                                    │
│ Term Loan A                 │ [蓝色输入]                                    │
│ Term Loan B                 │ [蓝色输入]                                    │
│ Senior Notes                │ [蓝色输入]                                    │
│ Sponsor Equity              │ [Plug公式: =Total Uses - Total Debt]         │
│ Total Sources               │ =SUM(各来源)                                 │
│                                                                            │
│ USES                        │ Amount ($M)                                  │
│ Enterprise Value             │ [公式/输入]                                  │
│ Financing Fees               │ [蓝色输入]                                   │
│ Transaction Fees             │ [蓝色输入]                                   │
│ Cash to Balance Sheet        │ [蓝色输入]                                   │
│ Total Uses                  │ =SUM(各用途)                                 │
│                                                                            │
│ ✔ Total Sources = Total Uses (必须平衡)                                     │
├──────────────────────────────────────────────────────────────────────────────┤
│ Section 2: Operating Model (经营模型)                                       │
│                                                                            │
│ ($M)        │ Closing │ Year 1 │ Year 2 │ Year 3 │ Year 4 │ Year 5        │
│ Revenue     │ [输入]  │ =前年*(1+增长率) │ ...   │ ...    │ ...   │ ...   │
│ COGS        │ [输入]  │ =Revenue*毛利率  │ ...   │ ...    │ ...   │ ...   │
│ Gross Profit│ [公式]  │ [公式]  │ ...    │ ...    │ ...   │ ...          │
│ SG&A        │ [输入]  │ =Revenue*占比    │ ...   │ ...    │ ...   │ ...   │
│ EBITDA      │ [公式]  │ [公式]  │ ...    │ ...    │ ...   │ ...          │
│ D&A         │ [输入]  │ [输入]  │ ...    │ ...    │ ...   │ ...          │
│ EBIT        │ [公式]  │ =EBITDA-D&A      │ ...   │ ...    │ ...   │ ...   │
│ Taxes       │ [公式]  │ =EBIT*税率        │ ...   │ ...    │ ...   │ ...   │
│ NOPAT       │ [公式]  │ [公式]  │ ...    │ ...    │ ...   │ ...          │
├──────────────────────────────────────────────────────────────────────────────┤
│ Section 3: Debt Schedule (债务计划)                                         │
│                                                                            │
│ Revolver:                                                                  │
│ Beginning Balance  │ [输入]  │ =上年End │ ...                              │
│ Draw/(Paydown)     │ [公式]  │ [公式]   │ ...                              │
│ Ending Balance     │ =Beg+Draw│ [公式]  │ ...                              │
│                                                                            │
│ Term Loan A / Term Loan B / Senior Notes (同样结构)                         │
│ → Interest = Beginning Balance × 利率 (用期初余额避免循环引用)               │
│ → Paydown 遵循优先级瀑布 (Cash Sweep)                                      │
│ → Ending Balance 不能为负 (用 MAX(0,...) 约束)                              │
│                                                                            │
│ Total Debt Beginning │ ...                                                 │
│ Total Interest       │ ...                                                 │
│ Total Mandatory Pay  │ ...                                                 │
│ Total Cash Sweep     │ ...                                                 │
│ Total Debt Ending    │ ...                                                 │
├──────────────────────────────────────────────────────────────────────────────┤
│ Section 4: Returns Analysis (回报分析)                                      │
│                                                                            │
│ Exit Year:              │ Year 5                                           │
│ Exit Multiple:          │ [蓝色输入] e.g., 10.0x                           │
│ Exit EBITDA:            │ [紫色同Sheet链接]                                │
│ Exit Enterprise Value:  │ =Exit Multiple × Exit EBITDA                     │
│ (-) Net Debt at Exit:   │ [公式]                                           │
│ Exit Equity Value:      │ =Exit EV - Net Debt                              │
│                                                                            │
│ IRR:  =XIRR(现金流系列, 日期系列) 或 =IRR(投资→回报)                        │
│ MOIC: =Exit Equity / Sponsor Equity                                        │
│                                                                            │
│ Cash Flow Series (IRR 计算):                                               │
│ Year 0: -Sponsor Equity (投资, 负数)                                       │
│ Year 1-4: Any dividends received (如有)                                    │
│ Year 5: +Exit Equity Value (退出, 正数)                                    │
├──────────────────────────────────────────────────────────────────────────────┤
│ Section 5: Sensitivity Tables (5×5, 奇数维度)                               │
│                                                                            │
│ Table 1: Entry Multiple vs Exit Multiple → IRR                             │
│             │  8.0x  │  9.0x  │ 10.0x │ 11.0x │ 12.0x                     │
│   8.0x     │ [fml]  │ [fml]  │ [fml]  │ [fml]  │ [fml]                    │
│   9.0x     │ [fml]  │ [fml]  │ [fml]  │ [fml]  │ [fml]                    │
│  10.0x     │ [fml]  │ [fml]  │  [★]  │ [fml]  │ [fml]  ← 中心格=Base    │
│  11.0x     │ [fml]  │ [fml]  │ [fml]  │ [fml]  │ [fml]                    │
│  12.0x     │ [fml]  │ [fml]  │ [fml]  │ [fml]  │ [fml]                    │
│                                                                            │
│ Table 2: Entry Multiple vs Exit Multiple → MOIC (同样结构)                  │
└──────────────────────────────────────────────────────────────────────────────┘

```

## Tab 1: Sources & Uses（资金来源与用途）

### 结构
```
┌─────────────────────────────────────────────────────────┐
│ SOURCES & USES                                          │
├──────────────────────┬──────────┬───────────────────────┤
│ Sources              │ Amount   │ Uses                  │
├──────────────────────┤          ├───────────────────────┤
│ Senior Debt          │ $XXX M   │ Purchase Price        │
│ Subordinated Debt    │ $XXX M   │  (= Entry Multiple    │
│ Equity (Plug)        │ $XXX M   │    × EBITDA)          │
│                      │          │ Transaction Fees      │
│                      │          │ Financing Fees        │
├──────────────────────┤          ├───────────────────────┤
│ Total Sources        │ $XXX M   │ Total Uses            │
└──────────────────────┴──────────┴───────────────────────┤
│  ← Total Sources 必须等于 Total Uses（勾稽检查）          │
│  ← Equity 是 Plug：= Total Uses - Senior Debt - Sub Debt │
└─────────────────────────────────────────────────────────┘
```

### 关键公式

- `Purchase Price = Entry Multiple × LTM EBITDA`
- `Equity = Total Uses - Senior Debt - Sub Debt`（平衡项 / Plug）
- `Total Sources = Total Uses`（硬性勾稽检查，必须相等）

### 颜色约定

- Entry Multiple、Debt 倍数、Fee 比率等 → 蓝色字体（硬编码输入）
- Purchase Price、Equity、Total → 黑色字体（公式计算）
- 跨 Tab 引用（如 EBITDA 来自 Operating Model）→ 绿色字体


## Tab 2: Operating Model（经营模型）

### 结构

| Line Item | Year 1 | Year 2 | Year 3 | Year 4 | Year 5 |
|---|---|---|---|---|---|
| Revenue | 蓝输入 | 公式 | 公式 | 公式 | 公式 |
| Revenue Growth % | 蓝输入 | 蓝输入 | 蓝输入 | 蓝输入 | 蓝输入 |
| EBITDA | 公式 | 公式 | 公式 | 公式 | 公式 |
| EBITDA Margin % | 蓝输入/公式 | 蓝输入 | 蓝输入 | 蓝输入 | 蓝输入 |
| D&A | 公式 | 公式 | 公式 | 公式 | 公式 |
| EBIT | 公式 | 公式 | 公式 | 公式 | 公式 |
| Taxes | 公式 | 公式 | 公式 | 公式 | 公式 |
| Net Income | 公式 | 公式 | 公式 | 公式 | 公式 |
| CapEx | 公式 | 公式 | 公式 | 公式 | 公式 |
| ΔNWC | 公式 | 公式 | 公式 | 公式 | 公式 |
| **Unlevered FCF** | 公式 | 公式 | 公式 | 公式 | 公式 |

### 关键公式

- `Revenue(N) = Revenue(N-1) × (1 + Growth %)`
- `EBITDA = Revenue × EBITDA Margin %`
- `EBIT = EBITDA - D&A`
- `Net Income = (EBIT - Interest) × (1 - Tax Rate)`
- `FCF = Net Income + D&A - CapEx - ΔNWC`

### 设计要点

- EBITDA Margin 通常逐年改善（体现 PE 经营改善假设）
- Growth % 和 Margin % 是蓝色输入，其余全部公式引用
- FCF 是 Debt Schedule 和 Returns 的核心输入


## Tab 3: Debt Schedule（债务偿还计划）

### 结构

| 行项 | Year 1 | Year 2 | Year 3 | Year 4 | Year 5 |
|---|---|---|---|---|---|
| **Senior Debt** | | | | | |
| Beginning Balance | =Sources!值 | =Prior Ending | =Prior Ending | =Prior Ending | =Prior Ending |
| Interest | =Beg × Rate | =Beg × Rate | =Beg × Rate | =Beg × Rate | =Beg × Rate |
| Mandatory Paydown | =MIN(Available, Remaining) | ... | ... | ... | ... |
| Cash Sweep | =剩余现金按优先级 | ... | ... | ... | ... |
| Ending Balance | =Beg - Total Paydown | ... | ... | ... | ... |
| **Sub Debt** | | | | | |
| Beginning Balance | =Sources!值 | =Prior Ending | ... | ... | ... |
| Interest | =Beg × Rate | ... | ... | ... | ... |
| Mandatory Paydown | =MIN(Available, Remaining) | ... | ... | ... | ... |
| Cash Sweep | =Senior 还完后的剩余 | ... | ... | ... | ... |
| Ending Balance | =Beg - Total Paydown | ... | ... | ... | ... |
| **Total Debt** | =Sum | =Sum | =Sum | =Sum | =Sum |

### 关键公式

- `Interest = Beginning Balance × Interest Rate`（必须用期初余额，断开循环引用）
- `Mandatory Paydown = MIN(Cash Available, Remaining Balance)`（余额不能为负）
- Cash Sweep：超出强制还款额的可用现金，按 Senior → Sub 优先级瀑布加速偿还
- Roll-forward：`Ending(N) = Beginning(N) - Total Paydown(N)`，`Beginning(N+1) = Ending(N)`

### 循环引用处理

利息如果用期末余额计算会形成循环：
```
Interest → 减少现金流 → 减少还款 → 影响期末余额 → 影响利息

```

解决方案：**利息始终用期初余额计算**，断开循环链。


## Tab 4: Returns Analysis（回报分析 + 敏感性表）

### 回报计算区
```
┌─────────────────────────────────────────────────────────┐
│ RETURNS ANALYSIS                                        │
├─────────────────────────────────────────────────────────┤
│ Initial Equity Investment:  $XXX M                      │
│ Exit EBITDA:               $XXX M (Year 5)              │
│ Exit Multiple:             X.Xx                         │
│ Exit Enterprise Value:     $XXX M                       │
│ Net Debt at Exit:          $XXX M                       │
│ Exit Equity Value:         $XXX M                       │
│                                                         │
│ MOIC:  X.Xx  (= Exit Equity / Initial Equity)          │
│ IRR:   XX%   (= XIRR or IRR of cash flow series)       │
├─────────────────────────────────────────────────────────┤
│ SENSITIVITY TABLE 1: Entry × Exit Multiple → IRR       │
│     8.0x   9.0x  10.0x★  11.0x  12.0x                 │
│ 8.0x [fml] [fml] [fml]  [fml]  [fml]                   │
│ 9.0x [fml] [fml] [fml]  [fml]  [fml]                   │
│10.0x [fml] [fml]  ★    [fml]  [fml] ← center=base     │
│11.0x [fml] [fml] [fml]  [fml]  [fml]                   │
│12.0x [fml] [fml] [fml]  [fml]  [fml]                   │
├─────────────────────────────────────────────────────────┤
│ SENSITIVITY TABLE 2: Entry Multiple × Leverage → MOIC   │
│ SENSITIVITY TABLE 3: Revenue Growth × EBITDA Margin →IRR│
└─────────────────────────────────────────────────────────┘
```
#### 关键公式：

- Exit Equity = Exit EBITDA × Exit Multiple - Net Debt at Exit
- MOIC = Exit Equity / Initial Equity
- IRR = XIRR([-Investment, FCF1, FCF2, ..., ExitEquity], [Dates])
- 敏感性表每格都是 完整 LBO 重算公式，75 个公式（3×25）

### 敏感性表（3 张 5×5）

#### 表 1: Entry Multiple × Exit Multiple → IRR

| | 8.0x | 9.0x | **10.0x** | 11.0x | 12.0x |
|---|---|---|---|---|---|
| 8.0x | [fml] | [fml] | [fml] | [fml] | [fml] |
| 9.0x | [fml] | [fml] | [fml] | [fml] | [fml] |
| **10.0x** | [fml] | [fml] | ★ | [fml] | [fml] |
| 11.0x | [fml] | [fml] | [fml] | [fml] | [fml] |
| 12.0x | [fml] | [fml] | [fml] | [fml] | [fml] |

★ 中心格 = Base case IRR，必须等于模型输出的 IRR 值

#### 表 2: Entry Multiple × Leverage → MOIC

轴值围绕 base case 对称展开：
`[base - 2×step, base - step, base, base + step, base + 2×step]`

#### 表 3: Revenue Growth × EBITDA Margin → IRR

### 敏感性表实现要求

- **奇数维度**（5×5 或 7×7），保证中心格存在
- **每格都是完整 LBO 重算公式**（不是线性近似），共 75 个公式（3×25）
- 中心格用 `#BDD7EE` 蓝色填充 + 粗体标注
- 不使用 Excel Data Table 功能，用独立公式实现


## 跨 Tab 数据流

```
Sources & Uses                    Operating Model
  Entry Multiple ──────────────→ Revenue, EBITDA projection
  Debt Amounts ────────────────→ Debt Schedule inputs
  Equity (Plug) ──────────────→ Returns Analysis (Initial Equity)

Operating Model                  Debt Schedule
  FCF ─────────────────────────→ Cash available for paydown

Debt Schedule                    Returns Analysis
  Ending Net Debt (Year 5) ────→ Exit Equity = Exit EV - Net Debt
  Interest ───────────────────→ 减少可用现金流（间接影响 paydown）

```
所有跨 Tab 引用用 绿色字体 标注（=Sources!B5），同 Tab 引用用 紫色字体（=B9），计算公式用黑色，硬编码输入用蓝色。


### 颜色编码总结

| 颜色 | 含义 | 示例 |
|---|---|---|
| 蓝色字体 `0000FF` | 硬编码输入 | Entry Multiple, Growth %, Debt Rate |
| 黑色字体 `000000` | 公式计算 | `=B5*B6`, `=SUM()` |
| 紫色字体 `800080` | 同 Tab 引用 | `=B9`, `=D45` |
| 绿色字体 `008000` | 跨 Tab 引用 | `=Sources!B5`, `='Operating Model'!C10` |

### 填充色总结

| 填充色 | 用途 |
|---|---|
| `#1F4E79` 深蓝 | Section headers |
| `#D9E1F2` 浅蓝 | Column headers |
| `#F2F2F2` 浅灰 / 白 | Input / Formula cells |
| `#BDD7EE` 中蓝 | Key outputs（IRR, MOIC, Exit Equity）|


## 验证清单

完成模型后必须通过以下检查：

- Sources = Uses（勾稽平衡）
- Debt 余额不为负（MAX/MIN 保护）
- Interest 用期初余额（循环引用断开）
- Cash Sweep 遵循优先级瀑布（Senior → Sub）
- IRR/MOIC 符号正确（Investment = 负，Proceeds = 正）
- 敏感性表中心格 = 模型实际输出值

