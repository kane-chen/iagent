---
name: 3-statement-model
description: This skill generates integrated 3-statement financial models (Income Statement, Balance Sheet, Cash Flow Statement) from scratch plus a fully-linked DCF valuation (DCF + WACC sheets). It fetches historical data directly from the Futu OpenAPI (`get_financials_statements`), applies projection assumptions, and builds a fully linked model with live formulas following professional investment banking standards. DCF sheet references 3-Statement's Revenue/EBIT/D&A/CapEx/ΔNWC via cross-sheet formulas, guaranteeing DCF and 3-Statement stay in lockstep.
---

# 3-Statement Financial Model + DCF Valuation

## 概述

本 skill 用 `openpyxl` 生成对齐 `references/schema.md` 的三表联动 Excel + DCF 估值:

| Tab | 内容 |
|---|---|
| **Assumptions**       | 分区式假设 (HEADER / MARKET DATA / REVENUE / COST / BS / DEBT / DIVIDEND) |
| **Income Statement**  | 5 期历史 + 5 期预测, 含 Margin% 展示行 (Gross/EBIT/Net) |
| **Balance Sheet**     | Days-driven, 含 Balance Check + Cash Tie-Out (红色条件格式) |
| **Cash Flow**         | OCF/CFI/CFF 三段, ΔNWC 严格符号规则, Ending Cash → BS Cash |
| **D&A Schedule**      | PP&E Beg → CapEx → Dep → End (Roll-forward) |
| **Debt Schedule**     | Beg → Issue → Repay → Sweep → End, Interest = Beg × Rate |
| **Working Capital**   | AR Days / Inv Days / AP Days 驱动 |
| **DCF** (NEW)         | 主估值模型 (Header → Case Selector → Market Data → 3 情景假设 → 选中情景聚合 → 历史/预测财务 → FCF → 折现 → 终值 → 估值汇总 → 3 张敏感性表) |
| **WACC** (NEW)        | CAPM 股权成本 + 债务成本 + 资本结构权重 + WACC |

## DCF & WACC Sheet 设计原则

- **数据完全跨表引用**: Revenue/EBIT (`'Income Statement'`), D&A/CapEx (`'D&A Schedule'`), ΔWC (`'Working Capital'`), Net Debt/Gross Debt (`'Balance Sheet'` L3), Stock Price/FX/Shares (`Assumptions`). 无二次抽取, DCF 与 3-Statement 主体保证严格一致。
- **3 情景 (Bear/Base/Bull) + CHOOSE 聚合**: 用户在 `DCF!B4` 修改 Case Selector (1/2/3), 下游所有公式立即切换。默认 Base 情景围绕最近 FY 实际比率, Bear/Bull 相对 Base ±3%。
- **WACC (CAPM)**: `Ke = Rf + β × ERP`, `Kd(after-tax) = Kd × (1-Tax)`, `WACC = IF(Gross Debt ≤ 0.1% × MC, Ke, We×Ke + Wd×Kd)`. 无债公司自动退化为 Ke (unlevered), 净现金公司 (BABA/腾讯类) 也不出现负 WACC。Enterprise Capital 用 `MAX(MC + Gross Debt, MC × 0.5)` 下限保护, 避免极端情境分母坍缩。
- **Beta 计算**: Futu `request_history_kline` 拉 60 个月月线, `cov / var` 计算; 基准按交易场所选 (US.SPY / HK.800000 恒生 / SH.000300 沪深300); 样本 < 24 个月回退到 `_BETA_FALLBACK = 1.20`.
- **Rf / ERP**: 按报表币种查 `_RF_ERP_BY_CURRENCY` 常量表 (USD 4.3%/5.5% / HKD 4.0%/6.0% / CNY 2.5%/6.5% ...), 用户可在 `WACC!B2` / `WACC!B4` 覆盖为实时值。
- **3 张 5×5 敏感性表** (WACC×TGR / Growth×Margin / Beta×Rf → Implied Price), 每张 25 格独立闭式重算, 中心格精确 = Valuation Summary。

## 运行方式

```bash
python scripts/build_3_statement_model.py --ticker BABA --workspace /path/to/workspace
```

**运行时依赖**: FutuOpenD 需运行并已登录 (获取三表历史财务数据 + `get_market_snapshot` 股价/总股本 + 月线 K 线用于 Beta 计算)。

**汇率来源**: 优先新浪财经开放接口 (`https://hq.sinajs.cn/list=fx_s{ccy_pair}`, Referer 白名单要求 sina.com.cn), 失败回退 Futu FX snapshot, 再兜底常量。用户可在 `Assumptions!B{fx_row}` 覆盖。

**数据源**: 直接调用 `ctx.get_financials_statements(code, statement_type, financial_type=7, num=50)` 拉取三表 (利润表/资产负债表/现金流量表), 年报口径 (financial_type=7); 与 `workspace/skills/futu-financial-report/scripts/get_financials_statements.py` 的调用方式一致。分页聚合 (每页 50 条, 直到 `next_key == "-1"`, 最多 10 页)。

**输入参数 (未变)**: `--ticker <code>` (如 BABA / 00700 / SH.600519) 与 `--workspace <path>` (仍作为输出目录使用: `{workspace}/excels/{ticker}_3Statement_{date}.xlsx`)。

## 关键设计原则

### 1. 每个计算都必须是 Excel 公式
写 `ws.cell(r,c).value = "=Prior*(1+Growth)"`，从不硬编码 Python 计算结果。所有数字随 Assumptions 输入变化自动重算。

### 2. 币种一致性 (与 dcf-model 共享)
- **Reporting Currency**: 从 API 返回的 `currency_code` 字段直接解析 (ISO 4217 如 CNY / USD / HKD)
- **Trading Currency**: 从 stock_code 前缀推断 (US.*→USD, HK.*→HKD, SH./SZ.*→CNY)
- **FX Rate**: Futu FX snapshot (`HK.USDCNH` 等), 失败回退常量, 用户在 Assumptions 可覆盖
- 报表主体全部按 Reporting Currency 计算
- **数值单位**: API 返回原始货币单位 (元), 抽取时统一 ÷1e6 归一化为 M (百万) 与展示保持一致

### 3. CapEx 逐年跨市场 fallback
按顺序尝试匹配字段 (每年独立 fallback):
1. **HK**: 现金流表 `购买固定资产` (fid 5071) + `购买无形资产` (fid 5073), 两个字段绝对值累加
2. **US**: 现金流表 `固定资产交易净额` (fid 8046) + `无形资产交易净额` (fid 8047), 两个字段绝对值累加
3. **A股**: 现金流表 `购建固定资产、无形资产和其他长期资产支付的现金` (fid 3043)
4. 兜底: 原来 Excel 加工字段 `资本开支(CapEx明细)` / `资本开支(CapEx)` (当数据源为 Excel 时保留兼容)

### 4. Interest = Beginning Debt × Rate
断开循环引用 (Interest → NI → Cash → Debt → Interest)。

### 5. BS 三级结构 + 强制平衡 (L1 → L2 → L3)

**Level 1** (三大类): Assets / Liabilities / Equity
**Level 2** (6 项, 直接从 API 抽取, 蓝色输入):
- Assets → Current Assets / Non-Current Assets
- Liabilities → Current Liab / Non-Current Liab
- Equity → Parent Equity (归属母公司) / Minority Interest (少数股东权益)

**Level 3** (每组 3-5 项 aggregated buckets, 按变现难度/偿还优先级排序):

| L2 分组 | L3 桶 (按顺序) | 合并策略 |
|---|---|---|
| Current Assets | Cash & ST Investments → AR & Prepayments → Inventory → Other CA (plug) | 前 3 桶从 API 抽, Other = L2 − Σ前 3 |
| Non-Current Assets | PP&E & Property → LT Investments → Goodwill & Intangibles → Other NCA (plug) | 前 3 桶抽, Other = L2 − Σ前 3 |
| Current Liab | Short-term Debt → AP & Accrued → Taxes & Div Payable → Other CL (plug) | 前 3 桶抽, Other = L2 − Σ前 3 |
| Non-Current Liab | Long-term Debt → Deferred Liab → Other NCL (plug) | 前 2 桶抽, Other = L2 − Σ前 2 |
| Parent Equity | Common Stock + APIC → Retained Earnings → Other Equity (plug) | 前 2 桶抽, Other = L2 − Σ前 2 |

**Plug 桶设计原理**: 前 N-1 桶用 field_id 精确抽取 (跨市场消歧, 见 §12), 最后 "Other" 桶自动 = L2 − Σ前 N-1, 保证 L3 sum = L2 精确, 历史列 Balance Check 自动 = 0。

**预测列 BS 平衡强制**:
- **Cash & ST Investments** (L3): 从 CF Ending Cash 引用 → 与 CF 自动勾稽
- **PP&E & Property** (L3): 从 D&A Schedule PP&E End 引用 → Beg + CapEx − D&A 滚动
- **AR & Prepayments** (L3): 从 Working Capital AR (Days-driven) 引用
- **AP & Accrued** (L3): 从 Working Capital AP (Days-driven) 引用
- **Long-term Debt** (L3): 从 Debt Schedule 期末余额引用
- **Retained Earnings** (L3): Prior + NI + Dividends (滚动)
- **其他 L3 桶**: 预测保持 prev year
- **Other Non-Current Liab (plug)**: 预测期强制平衡 `= TA − TE − CL − LT Debt − Deferred Liab`

### 6. CF 结构 + API 精确映射

**L1 (三段)**: Operating / Investing / Financing (保持不变)
**L2 (每段 4-5 项二级指标, 按 field_id 精确映射)**:

| L1 段 | L2 项 (按顺序) | US fid / HK fid |
|---|---|---|
| OCF | Net Income → (+) D&A → (+/-) ΔWorking Capital → (+/-) Other Non-Cash Adj (plug) | US 8017/8019/8028 / HK 5003/5009/5034 |
| CFI | (-) CapEx → (+/-) Other Investing (plug) | US 8046+8047 / HK 5071+5073; plug = 其他投资活动 |
| CFF | (+) Debt Iss → (-) Debt Repay → (-) Dividends → (-) Repurchase → (+/-) Other Fin (plug) | US 8058分正/负 / HK 5087+5088; Div US 8061 / HK 5094; Repurch US 8059 / HK 5089 |

**plug 桶原理**: 历史列前 N-1 桶从 API fid 抽取, plug 桶 = L1 Net Total − Σ前 N-1 → 保证 CF L1 Net (OCF/CFI/CFF) 精确等于 API 报告值, 历史 Cash Tie-Out 自动 = 0。

### 7. IS 分市场策略 (EBIT/EBT/NI 直读富途, 避免组装误差)

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

### 12. Schedule 一致性设计 (D&A / Debt / Working Capital)

**目标**: 三个 Schedule 与 BS L3 严格勾稽, 每个 Schedule 底部有 Rollforward Check 诊断行。

**D&A / PP&E Schedule**:
- 追踪对象: **BS L3 "PP&E & Property"** 广口径 (物业厂房 + 在建工程 + 投资物业 + 土地使用权)
- Roll-forward: `Beg + CapEx − D&A = End`
- 最老一期 Beg 用 `End − CapEx + D&A` 反推 (保证首期 rollforward 平衡)
- 其他期 Beg = 前期 End (跨表引用)
- 预测期 End = Beg + CapEx − D&A (BS L3 PP&E 直接引用此行)
- **BS L3 PP&E & Property (预测) = D&A Schedule PPE End** — 严格一致 ✓
- Rollforward Check 行: `Beg + CapEx − D&A − End`, 历史列非零表示 API 未捕获的非现金 PP&E 变动 (收购/减值/汇兑)

**Debt Schedule** (仅追踪长期债务):
- 追踪对象: **BS L3 "Long-term Debt"** (长期借款 + 长期融资租赁 + 可转换票据); 短期借款不在此表内
- Roll-forward: `Beg + Issuance − Mandatory Repay − Cash Sweep = End`
- 历史列: End = 实际 BS L3 LT Debt; Issuance/Repay 用 `MAX(0, ±(End−Beg))` 反推 (End 增/减方向)
- 预测期: Issuance/Repay 从 Assumptions; End 用公式计算
- Interest = Beg × Interest Rate (期初余额, 断循环引用)
- **BS L3 Long-term Debt (预测) = Debt Schedule Ending Balance** — 严格一致 ✓
- Rollforward Check = 0 精确 (由公式保证)

**Working Capital Schedule** (Days-driven):
- 历史 Days = 实际 Balance × 365 / (Revenue for AR, COGS for Inv/AP)
- 关键修复: `hist_cogs / hist_opex / hist_tax` 统一取绝对值, 避免港股 COGS 为负导致 Days 为负
- 预测: Balance = Revenue/COGS × Days_forecast / 365
- Δ 项符号约定: `Δ AR = Prior − Current` (增加视为现金流出), `Δ AP = Current − Prior` (增加视为现金流入)
- **BS L3 AR & Prepayments / AP & Accrued (预测) = WC AR/AP Balance** — 严格一致 ✓

### 13. BS L3 field_id 分市场映射表 (跨市场消歧)

**关键点**: 同一 display_name 在美股/港股/A 股可能语义不同 (例如 "预付款项" 在美股 fid 8016 是流动资产, 在港股 fid 5044 是非流动资产). 本模型用 **field_id** 精确访问, 避免 display_name 歧义。

**美股 (US.*, fid 8xxx)**:

| L3 桶 | field_id | 说明 |
|---|---|---|
| Cash & ST Investments | 8003 | 现金及现金等价物和短期投资 (父项, 含 8004+8005 子项) |
| AR & Prepayments | 8006 + 8016 | 应收款项 (父项) + 预付款项 |
| Inventory | 8017 | 存货 |
| PP&E & Property | 8024 | 固定资产净额 (父项, 含 8025/8026 子项) |
| LT Investments | 8028 + 8035 | 总投资 (父项) + 金融资产 |
| Goodwill & Intangibles | 8039 | 商誉及其他无形资产 (父项, 含 8040/8041 子项) |
| Short-term Debt | 8057 | 短期借款与融资租赁负债 (父项) |
| AP & Accrued | 8050 − 8052 + 8056 | 应付款项 − 应交税费 + 应计费用 (避免 8052 与 taxes_payable 重复) |
| Taxes & Div Payable | 8052 | -应交税费 |
| Long-term Debt | 8068 | 长期借款与租赁负债 (父项) |
| Deferred Liab | 8074 | 递延负债 (非流动) |
| Common Stock + APIC | 8086 + 8090 | 股本 + 资本公积 |
| Retained Earnings | 8091 | 留存收益 |

**港股 (HK.*, fid 5xxx)**:

| L3 桶 | field_id | 说明 |
|---|---|---|
| Cash & ST Investments | 5003 + 5005 + 5006 + 5017 | 现金 + 定期存款(流) + 短期投资 + FVTPL(流) |
| AR & Prepayments | 5007 + 5014 | 应收账款 + 预付款按金及其他应收款 |
| Inventory | 5019 | 存货 |
| PP&E & Property | 5031 + 5032 + 5033 + 5034 | 物业厂房 + 在建工程 + 投资物业 + 土地使用权 |
| LT Investments | 5036 + 5037 + 5039 + 5050 + 5053 + 5054 | AFS + FVTPL(非流) + 长期投资 + 联营 + 合营 + 定期存款(非流) |
| Goodwill & Intangibles | 5046 | 无形资产 |
| Short-term Debt | 5070 + 5072 | 银行贷款及透支 + 短期融资租赁 |
| AP & Accrued | 5062 + 5066 + 5067 + 5068 | 应付账款 + 应付票据 + 其他应付+应计 + 预收款 |
| Taxes & Div Payable | 5063 + 5064 | 应交税费 + 应付股利 |
| Long-term Debt | 5091 + 5093 + 5104 | 长期银行贷款 + 长期融资租赁 + 可转换票据 |
| Deferred Liab | 5101 + 5102 | 递延税项 + 递延收入(非流) |
| Common Stock + APIC | 5111 + 5112 | 股本 + 股本溢价 |
| Retained Earnings | 5115 | 保留溢利 |

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

## 数据抽取规则 (Futu API 直连)

**统一入口**: `ctx.get_financials_statements(code, statement_type=<1|2|3>, financial_type=7, num=50)` (年报口径, 分页拉取). API 返回 `{structure_list, report_list}`:
- `structure_list`: `[{field_id, display_name}]` — 字段 ID → 中文显示名映射
- `report_list`: `[{fiscal_year, period_text, financial_type, currency_code, item_list: [{field_id, data, yoy, qoq}]}]`
- 抽取时转换成 `{display_name: {"{FY}FY": value ÷ 1e6}}` shape 便于按 display_name 匹配

**字段映射跨市场兼容表** (display_name):

| 字段 | 美股 (US.*) | 港股 (HK.*) | A股 (SH./SZ.*) |
|---|---|---|---|
| Revenue | `总收入` (8001) / `营业总收入` (8002) | `营业总收入` (5001) | `营业总收入` (3001) |
| COGS | `营业总成本` (8003) | `营业总成本` (5005) | `营业总成本` (3009) |
| OpEx | `营业费用` (8005) | `营业费用` (5013) | `营业费用` (3005) |
| EBIT | `营业利润` (8017) | `营业利润` (5034) | `营业利润` (3032) |
| Taxes | `所得税` (8035) | `所得税` (5043) | `所得税` (3039) |
| Net Income | `归属于母公司股东净利润` (8043) | `归属母公司净利润` (5051) | `归属母公司净利润` (3047) |
| Finance Income | (无) | `融资收入` (5035) | (无) |
| Finance Cost | (无) | `融资成本` (5036) | (无) |
| Equity Affiliates | (无) | `应占联营公司利润` (5037) | (无) |
| D&A (IS-side) | `折旧摊销及损耗` (8011) | (无, 回退 CF-side) | `折旧与摊销` (3020) |
| D&A (CF-side) | `折旧摊销及损耗` (8019) | `折旧及摊销:` (5009, 注意冒号) | `折旧与摊销` (3002) |
| CapEx | `固定资产交易净额` (8046) + `无形资产交易净额` (8047) | `购买固定资产` (5071) + `购买无形资产` (5073) | `购建固定资产、无形资产和其他长期资产支付的现金` (3043) |
| Cash | `-现金和现金等价物` (8004) + `-短期投资` (8005) | `现金及等价物` (5003) + `定期存款-流动/非流动资产` | `货币资金` (3003) |
| AR | `-应收账款净额` (8007) | `应收账款` (5007) | `应收账款` (3009) |
| AP | `-应付账款` (8051) | `应付账款` (5062) | `应付账款` (3059) |
| Inventory | `存货` (8017) | `存货` (5019) | `存货` (3016) |
| PPE | `固定资产净额` (8024) | `物业厂房及设备` (5031) | `固定资产合计` (3026) |
| Intangible | `商誉及其他无形资产` (8039) | `无形资产` (5046) / `土地使用权` (5034) | `无形资产` (3030) |
| Equity | `归属于母公司股东权益合计` (8085) | `归属于母公司股东权益合计` (5110) | `归属母公司所有者权益合计` (3097) |
| RE | `留存收益` (8091) | `保留溢利` (5115) | `留存收益` / `未分配利润` |
| Total Assets | `资产合计` (8001) | `资产合计` (5001) | `资产合计` (3001) |
| Total Liab | `负债合计` (8048) | `负债合计` (5060) | `负债合计` |
| Short-term Debt | `短期借款与融资租赁负债` (8057) | `银行贷款及透支` (5070) + `短期融资租赁负债` (5072) | `短期借款` |
| Long-term Debt | `-长期借款` (8069) + `-长期融资租赁负债` (8070) | `长期银行贷款` (5091) + `长期融资租赁负债` (5093) | `长期借款` |

**Retained Earnings 兜底**: 若 API 未返回 `留存收益` / `保留溢利` (如 PDD), 用 `Equity − 股本/股本溢价` 近似。

**Currency**: 直接从 API `currency_code` 读取 (ISO 4217: CNY/USD/HKD/EUR/...), 若三张表币种不一致 (罕见) 记 log warning。

### 历史期数动态适配 (n_hist 自动裁剪)

**问题**: API 对不同公司的 BS 覆盖不一致 — 例如某些港股/新股 BS 年数少于 income/cashflow, 若强行以 income 5 期扩展到 BS, 会产生大面积 0 值。

**修复**: 抽取 `hist_fys` 时以 **BS 覆盖** 为主约束。判定标准: 该 FY 至少有 2 项 BS 核心字段 (资产合计/负债合计/股东权益/现金) 非零, 才计入 `hist_fys`。

**效果 (API 数据源下)**:
- 大部分公司 BS 覆盖 5+ 期, 都能满 5 期展示 (GOOG 甚至 25 期 API 覆盖, 24 期 BS)
- 少数新股/退市/新上市公司: `n_hist < 5` 时自动右移 `hist_start_col`, FY 列右对齐

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
A: **API 直连后已修复**. 早期版本 (Excel 数据源) `资本开支(CapEx明细)` 加工字段仅覆盖近 2 期. 现在改为直接从 API 抽取 `固定资产交易净额` (fid 8046) + `无形资产交易净额` (fid 8047), 全部 5 期均有值 (BABA 2022-2026FY: 53k / 34k / 33k / 84k / 126k)。

**Q: HK 00700 / 83690 港股 BS 显示大量空列?**
A: 早期版本 (读取本地 Excel) 存在此问题, 因为 Futu Excel 生成器对港股 BS 输出仅 2 期。**现在改为 API 直连后已修复** — API 直接返回 5+ 期 BS 数据。00700 2021-2025FY 全部有值。若某公司 BS 期数少于 5, 会自动裁剪 `n_hist` 到 API 覆盖期数。

**Q: 数据源为什么改成 API 直连了?**
A: 原来读 `workspace/excels/{ticker}_{sheet}_*.xlsx` (由 `futu-financial-report` skill 预先生成) 存在几个问题:
1. **Excel 加工层丢失数据**: 港股 BS 生成器只输出 2 期 (2024FY/2025FY), 而 API 有 5+ 期
2. **依赖前置 skill**: 用户必须先跑 `futu-financial-report`, 增加工作流步骤
3. **数据陈旧**: Excel 文件可能是几天前生成, 期间已有新财报发布

API 直连 (`ctx.get_financials_statements`, financial_type=7 年报口径, 分页拉取) 一步到位, 数据即时刷新, 覆盖历史 5+ 期。CLI 参数保持不变 (`--ticker` + `--workspace`), workspace 仅作为输出目录。

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

**Q: 出现 `所有源均无 D&A 数据` 警告?**
A: **API 直连后大部分公司都能拿到 D&A** — 例如 00700 从 CF `折旧及摊销:` (fid 5009) 拿到 66,028M, BABA 从 CF `折旧摊销及损耗` (fid 8019) 拿到 47,118M. 仅当**利润表 + 现金流表两个来源都没有 D&A 字段**时才会告警 (罕见, 通常是新股或未审计的年度)。此时 D&A Schedule / IS `Less: D&A` / CF `(+) D&A` 均为 0, EBITDA ≈ EBIT (可能低估)。用户可查年报手动填入 `D&A Schedule!C5..G5` (蓝色输入)。
