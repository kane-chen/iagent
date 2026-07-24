---
name: 3-statement-model
description: This skill generates integrated 3-statement financial models (Income Statement, Balance Sheet, Cash Flow Statement) from scratch. It extracts historical data from financial Excel files, applies projection assumptions, and builds a fully linked model with live formulas following professional investment banking standards.
---

# 3-Statement Financial Model

## 概述

本 skill 用 `openpyxl` 生成对齐 `references/schema.md` 的三表联动 Excel:

| Tab | 内容 |
|---|---|
| **Assumptions**       | 分区式假设 (HEADER / MARKET DATA / REVENUE / COST / BS / DEBT / DIVIDEND) |
| **Income Statement**  | 5 期历史 + 5 期预测, 含 Margin% 展示行 (Gross/EBIT/Net) |
| **Balance Sheet**     | Days-driven, 含 Balance Check + Cash Tie-Out (红色条件格式) |
| **Cash Flow**         | OCF/CFI/CFF 三段, ΔNWC 严格符号规则, Ending Cash → BS Cash |
| **D&A Schedule**      | PP&E Beg → CapEx → Dep → End (Roll-forward) |
| **Debt Schedule**     | Beg → Issue → Repay → Sweep → End, Interest = Beg × Rate |
| **Working Capital**   | AR Days / Inv Days / AP Days 驱动 |

## 运行方式

```bash
python scripts/build_3_statement_model.py --ticker BABA --workspace /path/to/workspace
```

**前置条件**: `workspace/excels/{ticker}_income_*.xlsx`, `{ticker}_balance_*.xlsx`, `{ticker}_cashflow_*.xlsx` 已由 `futu-financial-report` 生成 (最好是最新版, cashflow Excel 含 `资本开支(CapEx明细)` 行)。
**运行时依赖**: FutuOpenD 需运行 (用于 `get_market_snapshot` 获取股价 / 总股本 / FX 汇率)。

## 关键设计原则

### 1. 每个计算都必须是 Excel 公式
写 `ws.cell(r,c).value = "=Prior*(1+Growth)"`，从不硬编码 Python 计算结果。所有数字随 Assumptions 输入变化自动重算。

### 2. 币种一致性 (与 dcf-model 共享)
- **Reporting Currency**: 从财报 Excel 单位列 (如"百万人民币") 反向解析 → CNY
- **Trading Currency**: 从 stock_code 前缀推断 (US.*→USD, HK.*→HKD, SH./SZ.*→CNY)
- **FX Rate**: Futu FX snapshot (`HK.USDCNH` 等), 失败回退常量, 用户在 Assumptions 可覆盖
- 报表主体全部按 Reporting Currency 计算

### 3. CapEx 严格口径 (三级回退)
1. `cashflow` Excel 的 `资本开支(CapEx明细)` 加工行 (仅明细字段, 不含投资活动净额兜底)
2. `cashflow` 里 `购建固定资产及无形资产净额` / `购建固定资产` / `购建固定资产、无形资产...`
3. `income` Excel 的 `资本开支(CapEx)` (含兜底口径, 会告警)

### 4. Interest = Beginning Debt × Rate
断开循环引用 (Interest → NI → Cash → Debt → Interest)。

### 5. BS 平衡强制
- **PP&E End**: 历史列引用 `hist_ppe` (蓝色输入), 预测列 = Beg + CapEx − D&A
- **Cash**: 历史列 CF Ending = hist_cash, 预测列 = Beg + Net Change (核心勾稽)
- **ONCL (Other Non-Current Liabilities)**: 历史列 0.6 × plug 差额, **预测列 = TA − AP − OCL − Debt − Equity** (强制 BS 平衡, 相当于把资产/权益变动的剩余项汇入长期负债 plug)
- **Retained Earnings**: 预测 = Prior + NI + Dividends (Div 已带负号)

### 6. 颜色编码 (schema.md 4 色)
| 颜色 | 用途 |
|---|---|
| 蓝 `0000FF` | 硬编码输入 (历史数据 / 假设值) |
| 黑 `000000` | 同表公式计算 |
| 紫 `800080` | 同 Sheet 引用 |
| 绿 `008000` | 跨 Sheet 引用 |

### 7. 填充色
| 颜色 | 用途 |
|---|---|
| `#1F4E79` 深蓝 | Section headers |
| `#D9E1F2` 浅蓝 | Sub-headers |
| `#F2F2F2` 浅灰 | Input cells |
| `#BDD7EE` 中蓝 | Check rows / Key outputs |
| `#E2F0D9` 浅绿 | 预测列辨识 |

### 8. 数字格式
- 金额: `#,##0;(#,##0);"-"` (负数括号, 零破折号)
- 百分比: `0.0%`
- Days: `0" days"`
- Check rows: `[Red][<>0]#,##0.00;[Red][<>0](#,##0.00);0` (非零红色高亮)

### 9. Cell Comment 规范
- **硬编码输入**: `Source: [System/Document], [Date], [Reference]` (由 `add_source_comment()` 自动生成)
- **公式**: 显示计算逻辑与单元格引用

## 数据抽取规则 (对齐 dcf-model)

| 字段 | 来源 | 备注 |
|---|---|---|
| Revenue / COGS / OpEx / EBIT / Tax / NI | 富途利润表 (5 期历史) | |
| D&A | Cash Flow "折旧摊销及损耗" 优先, Income 备选 | |
| CapEx | cashflow `资本开支(CapEx明细)` (严格明细口径, 无投资活动净额兜底) | |
| Cash | 现金及等价物 + 短期投资 + 定期存款(流动+非流动) | 与 dcf-model 一致 |
| Debt | 短期借款(含融资租赁) + 长期借款(含长期融资租赁) | |
| AR / Inv / AP / PPE / Intangible / Equity / RE | 富途 BS 各期 | |
| Stock Price / Shares | Futu `get_market_snapshot` | |
| Reporting Currency | 财报 Excel 单位列 (反向映射) | |
| Trading Currency | stock_code 前缀推断 | |
| FX Rate | Futu FX snapshot / 常量回退 | |

## 验证清单 (schema.md 定义)

生成后自动满足:
- ✔ Balance Check (每年) = 0: `Total Assets - Total Liabilities & Equity = 0`
- ✔ Cash Tie-Out (每年) = 0: `BS Cash - CF Ending Cash = 0`
- ✔ Retained Earnings 滚动: `Prior + NI - Dividends = Ending`
- ✔ AR/Inv/AP = Days × 基数 / 365 (Days-driven)
- ✔ PP&E End = Beg + CapEx - D&A (Roll-forward)
- ✔ Debt End = Beg + Iss - Repay - Sweep
- ✔ Interest = Beginning Debt × Rate (不循环)
- ✔ 所有蓝色输入带 `Source:` comment
- ✔ 无 `#REF!` / `#DIV/0!` / `#NAME?` 错误

## 常见问题

**Q: BS Balance Check 预测期不为零?**
A: 检查 Assumptions 是否合理。ONCL 是最终 plug 项 (`= TA - AP - OCL - Debt - Equity`), 保证 BS 平衡。若 Balance Check 显示非零红色, 通常是公式引用错误 (需检查各 Sheet 行号一致性)。

**Q: BABA CapEx 历史各期为 0?**
A: 部分历史期 (2025FY 等) 富途 API 未返回 8046 明细字段, 因此严格口径无值 (fallback 到 income 的兜底口径会失真, 已被剔除)。用户可查 10-K 手动填入。

**Q: 币种不一致?**
A: BABA 报表币种 = CNY, 股价 = USD, 通过 `Assumptions!FX Rate` (默认 7.2) 换算。若 Futu FX API 失败, 用户可在 Assumptions 手工覆盖。
