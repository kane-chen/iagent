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

### 6. IS 分市场策略 (EBIT/EBT/NI 直读富途, 避免组装误差)

历史列直接引用富途原始数字, 预测列公式化:

| 指标 | 历史列 | 预测列 |
|---|---|---|
| **EBIT** | `hist_ebit` (富途"营业利润", 蓝色输入) | `Revenue × EBIT Margin` |
| **EBITDA** | = EBIT + \|IS-side D&A\| | 同 |
| **EBT** | 港股 = EBIT + Fin Inc − Fin Cost + Eq Aff + Other Income; 美股/A股 = EBIT + Non-Operating Items (Net) | 同 |
| **Less: Taxes** | `hist_tax` (富途"所得税", 蓝色输入, 负号展示) | `-MAX(0, EBT) × Tax Rate` (亏损不缴) |
| **Net Income** | `hist_ni` (归母口径, 蓝色输入) | = EBT + Tax |

**为什么 Taxes 历史列直接引用富途, 而非 IS 自算?** IS 自算 (EBT × Tax Rate) 会因 Tax Rate 假设仅填 FCST 列导致历史列为 0; 直接读富途"所得税"保证与财报一致, 且不依赖 Assumptions 是否 pre-fill hist。

**分市场 EBITDA→EBT 中间项 (US/HK/CN)**:
- **港股 (HK)**: 独立展示 4 行 — `(+) Finance Income` (5035 融资收入) / `(-) Finance Cost / Interest Expense` (5036 融资成本) / `(+) Equity in Affiliates` (5037 应占联营公司利润) / `(+) Other Income / (Loss)` 残差
- **美股 (US) / A股 (CN)**: 富途利润表无对应明细字段 (利息/投资收益/汇兑等被合并归入营业外净收支), 展示单行 `(+/-) Non-Operating Items (Net) / 营业外净收支`; 历史 = `hist_ebt - hist_ebit`; 预测 = `Revenue × Other Income % - Debt Schedule Interest` (显式扣除新增有息负债利息, 保证 Debt Schedule 与 IS 联动)

**D&A 双口径 (IS-side vs CF-side, 见 §11)**:
- IS `Less: D&A` 用 **IS-side** (窄口径, 仅固定资产折旧, 与 EBIT 口径一致 → EBITDA 不高估)
- D&A Schedule 用 **CF-side** (广口径, 含无形/使用权/减值, 匹配 CF NI + D&A 加回)

**为什么不用 `GP − OpEx − D&A` 组装 EBIT?** 富途"营业总成本"或"营业费用"已经包含 D&A, 若再减一次 D&A 会重复扣除 (BABA 2026FY 差 5,079)。直接读富途"营业利润"确保历史值与财报一致。

**为什么 NI 用 `hist_ni` 而非 IS 自算?** BABA 类公司 EBT 与营业利润差距很大 (2026FY 差 69,722, 主要是投资收益等非营业项), IS 自算 EBT/NI 会导致历史 NI 偏离真实值 57%+。

### 11. D&A 双口径 (IS-side vs CF-side)

富途利润表与现金流表的"折旧摊销及损耗"数值常不一致, 本模型分别抽取:

| 口径 | 数据源 | 含义 | 用途 |
|---|---|---|---|
| **IS-side D&A** (窄) | 富途利润表 `折旧摊销及损耗` (fid 8011 / 3020) | 仅固定资产折旧 (计入营业成本/费用的部分) | IS `Less: D&A` (保证 EBITDA = EBIT + IS-D&A 口径一致) |
| **CF-side D&A** (广) | 富途现金流表 `折旧摊销及损耗` (fid 5059 / 8046) | 固定资产折旧 + 无形摊销 + 使用权资产摊销 + 减值等所有非现金项 | D&A Schedule PP&E 滚动; CF `(+) D&A` 加回 (NI + D&A + ΔWC = OCF) |

**BABA 2026FY 示例**: IS-D&A = 5,079, CF-D&A = 47,118, 差额 42,039 主要为无形资产摊销与使用权资产摊销 — 已包含在 EBIT 但富途利润表未单列。

**回退规则**: 任一口径缺失 (公司未披露) → 用另一口径值补齐, 并记 log warning (`利润表 D&A 缺失, IS-side 回退到 CF-side (EBITDA 可能高估)`)。

### 7. 颜色编码 (schema.md 4 色)
| 颜色 | 用途 |
|---|---|
| 蓝 `0000FF` | 硬编码输入 (历史数据 / 假设值) |
| 黑 `000000` | 同表公式计算 |
| 紫 `800080` | 同 Sheet 引用 |
| 绿 `008000` | 跨 Sheet 引用 |

### 8. 填充色
| 颜色 | 用途 |
|---|---|
| `#1F4E79` 深蓝 | Section headers |
| `#D9E1F2` 浅蓝 | Sub-headers |
| `#F2F2F2` 浅灰 | Input cells |
| `#BDD7EE` 中蓝 | Check rows / Key outputs |
| `#E2F0D9` 浅绿 | 预测列辨识 |

### 9. 数字格式
- 金额: `#,##0;(#,##0);"-"` (负数括号, 零破折号)
- 百分比: `0.0%`
- Days: `0" days"`
- Check rows: `[Red][<>0]#,##0.00;[Red][<>0](#,##0.00);0` (非零红色高亮)

### 10. Cell Comment 规范
- **硬编码输入**: `Source: [System/Document], [Date], [Reference]` (由 `add_source_comment()` 自动生成)
- **公式**: 显示计算逻辑与单元格引用

## 数据抽取规则 (对齐 dcf-model)

| 字段 | 来源 | 备注 |
|---|---|---|
| Revenue / COGS / OpEx / EBIT / NI | 富途利润表 (5 期历史) | EBIT/NI 直读富途, 避免组装误差 |
| Taxes | 富途利润表 `所得税` (5 期历史, 蓝色输入) | 历史直读富途, 不受 Assumptions.Tax Rate 影响 |
| D&A (IS-side, 窄口径) | 富途利润表 `折旧摊销及损耗` (缺失时回退 CF-side) | IS `Less: D&A` 用, 保证 EBITDA 与 EBIT 口径一致 |
| D&A (CF-side, 广口径) | 富途现金流表 `折旧摊销及损耗` (缺失时回退 IS-side) | D&A Schedule + CF `(+) D&A` 用, 含无形/使用权/减值; 逐年 fallback |
| CapEx | 优先 income `资本开支(CapEx)` (Futu TTM 加工, 覆盖长); 逐年 fallback 到 CF 明细 `资本开支(CapEx明细)` → `固定资产交易净额` | 富途 CF 明细字段常只覆盖近 2-4 年, income 覆盖全部 FY |
| Cash | 现金及等价物 + 短期投资 + 定期存款(流动+非流动) | 与 dcf-model 一致 |
| Debt | 短期借款(含融资租赁) + 长期借款(含长期融资租赁) | 港股场景兼容 `银行贷款及透支`/`长期银行贷款`/`长期融资租赁负债` |
| AR / Inv / AP | 富途 BS 各期 | 部分 tech 公司无 `存货` 字段, 返回 0 |
| PPE | `固定资产净额` / `物业厂房及设备` / `固定资产合计` / `固定资产` | 兼容 US 8024 / HK 5023 / A 股 3026 |
| Intangible | `无形资产` / `土地使用权` / `商誉` (港股腾讯类合并) | 港股场景常用 `土地使用权` 替代 |
| Equity | `归属于母公司股东权益合计` / `股东权益合计` | 归母口径优先 |
| RE (Retained Earnings) | `留存收益` / `未分配利润`; 缺失时用 `Equity - 股本溢价/股本` 近似 | 港股腾讯类无独立 RE 字段 |
| Stock Price / Shares | Futu `get_market_snapshot` | |
| Reporting Currency | 财报 Excel 单位列 (反向映射) | |
| Trading Currency | stock_code 前缀推断 | |
| FX Rate | Futu FX snapshot / 常量回退 | |

### 历史期数动态适配 (n_hist 自动裁剪)

**问题**: 富途 API 对不同公司的 BS 覆盖不一致 — 例如 HK 00700 腾讯 BS 只覆盖 2 期 (2024FY / 2025FY), 而 income/cashflow 覆盖 8 期。若强行以 income 的 5 期扩展到 BS, 会产生大面积 0 值。

**修复**: 抽取 `hist_fys` 时以 **BS 覆盖** 为主约束。判定标准: 该 FY 至少有 2 项 BS 核心字段 (资产合计/负债合计/股东权益/现金) 非零, 才计入 `hist_fys`。

**效果**:
- HK 00700: `n_hist = 2` (BS 只有 2 期), 历史列仅 2024FY/2025FY, 前 3 列留空
- 83690 美团 / BABA / GOOG / AAPL 等: `n_hist = 5` (全部 5 期填满)

`hist_start_col` 在 `n_hist < 5` 时自动右移, 保证 FY 列右对齐 (与预测列 H-L 相邻)。

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
A: 已修复。富途 cashflow 表 `资本开支(CapEx明细)` 字段只覆盖近 2 期 (2025FY/2026FY), 早期为 None。修复后 CapEx 优先从 income 表 `资本开支(CapEx)` 拉取 (Futu TTM 加工口径, 覆盖全部 7 期), 缺失时才用 CF 明细做 per-year fallback。BABA 5 期现均有 CapEx 数据。

**Q: HK 00700 / 83690 港股 BS 显示大量空列?**
A: 富途对港股的 BS 覆盖比 income/cashflow 少 (00700 只有 2024FY/2025FY 两期)。已修复: 抽取时以 BS 覆盖为主约束, 若 BS 只有 2 期则历史列仅显示 2 期, 前 3 列留空 (不再强行以 income 5 期填充导致 BS/CF 全零)。用户可查年报手动填入历史 BS 数据。

**Q: 币种不一致?**
A: BABA 报表币种 = CNY, 股价 = USD, 通过 `Assumptions!FX Rate` (默认 7.2) 换算。若 Futu FX API 失败, 用户可在 Assumptions 手工覆盖。

**Q: 历史 Taxes 行为什么现在是蓝色输入而不是公式?**
A: 早期版本历史列也用 `-MAX(0, EBT) × Tax Rate` 公式, 但 Assumptions.Tax Rate 只填 FCST 列 (H..L), 导致历史列参数为空 → Tax 为 0。修复后历史列直接读富途利润表"所得税" (蓝色输入, 负号展示), 与财报一致; 预测列保留 EBT × 假设税率公式 (支持用户调节)。

**Q: IS 的 D&A (5,079) 与 D&A Schedule 的 D&A (47,118) 不一致 (BABA 2026FY)?**
A: 这是**故意的分口径设计**, 见 §11。
- **IS `Less: D&A`** = 富途利润表 `折旧摊销及损耗` (窄口径, 仅固定资产折旧). 保证 EBITDA = EBIT + IS-D&A 不重复扣除或高估 (EBIT 已经包含 IS-D&A 扣除, 加回来才是 EBITDA).
- **D&A Schedule / CF `(+) D&A`** = 富途现金流表 `折旧摊销及损耗` (广口径, 含无形资产摊销 + 使用权资产摊销 + 减值等所有非现金项). 用于 CF 加回 (NI + D&A + ΔWC = OCF) 与 PP&E 滚动.

差额 42,039 是无形/使用权/减值等, 已包含在 EBIT 但富途利润表不单列。两口径都对, 用途不同。

**Q: 美股/A股为什么看不到 Finance Income / Finance Cost / Equity in Affiliates 三行?**
A: 富途美股/A股利润表在 EBIT 与 EBT 之间无明细拆分 (利息费用/投资净收益/汇兑损益等全部合并), 显示 3 行零值反而误导。当前设计:
- **港股**: 4 行明细展示 (5035/5036/5037 + Other Income 残差)
- **美股/A股**: 合并单行 `(+/-) Non-Operating Items (Net) / 营业外净收支`, 历史 = `hist_ebt - hist_ebit` (蓝色输入), 预测 = `Revenue × Other Income % - Debt Schedule Interest`

预测列显式扣除 `Debt Schedule Interest`, 保证新增有息负债的利息费用能传导到 IS。

**Q: PDD Retained Earnings 历史值缺失?**
A: 已修复。部分公司 (PDD / 港股腾讯类) 富途 BS 无独立"留存收益"/"未分配利润"字段, 只有 `归属于母公司股东权益合计` 和 `股本溢价`/`股本`。修复后使用 `Equity − Common Stock` 近似 RE (若 CS 也缺失则 RE = Equity)。BABA/GOOG/AAPL 等有独立 RE 字段的仍直接读取。

**Q: 00700 / 83690 / PDD 出现 `所有源均无 D&A 数据` 警告?**
A: 部分公司的富途利润表 + 现金流表**都**未列示 `折旧摊销及损耗` 字段 (港股腾讯 / 美团 / PDD 拼多多)。此时 D&A Schedule / IS `Less: D&A` / CF `(+) D&A` 均为 0, EBITDA ≈ EBIT (可能低估)。用户可查年报手动填入 `D&A Schedule!C5..G5` (蓝色输入)。
