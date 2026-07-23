---
name: dcf-model
description: 生成投行标准的 DCF (Discounted Cash Flow) Excel 估值模型 — 2 Sheet (DCF + WACC), 支持 Bear/Base/Bull 三情景切换 (Case Selector + CHOOSE 聚合), 内嵌 3 张 5×5 敏感性表 (75 个完整重算公式), 所有单元格均为活公式。严格对齐 `references/schema.md`。
---

## 概述

本 skill 用 `openpyxl` 生成两 Sheet 的 DCF 估值模型:

| Sheet | 内容 |
|---|---|
| **DCF** | 主估值模型 (Header → Case Selector → Market Data → 3 情景假设 → 选中情景聚合 → 历史/预测财务 → FCF → 折现 → 终值 → 估值汇总 → 3 张敏感性表) |
| **WACC** | CAPM 股权成本 + 债务成本 + 资本结构权重 + WACC |

**核心设计**: 用户在 `DCF!B4` 修改 Case Selector (1/2/3), CHOOSE 公式立即将所有下游计算切换到对应情景, 无需重跑脚本。

**币种一致性**: 财报币种 (Reporting, 如 CNY) 与股价币种 (Trading, 如 USD) 常不同 (ADR/H 股场景),
通过 `DCF!B10` 的 FX Rate (`1 Trading = X Reporting`) 把股价换算到报表币种下,
Enterprise Value → Equity Value → Implied Price → Current Price 全部在 Reporting 币种下计算与对比, Upside% 才有意义。

## 运行方式

```bash
python scripts/build_dcf_model.py --ticker BABA --workspace /path/to/workspace
```

**前置条件**: `workspace/excels/{ticker}_income_*.xlsx`, `{ticker}_balance_*.xlsx`, `{ticker}_cashflow_*.xlsx` 已由 `futu-financial-report` 生成。
**运行时依赖**: FutuOpenD 需运行 (用于 `get_market_snapshot` 获取实时股价和总股本)。

## 关键实现原则

### 1. 每个计算都必须是 Excel 公式
写 `cell.value = "=B5*B6"`, 从不硬编码 Python 计算结果。所有数字随 Case Selector / 假设输入变化自动重算。

### 2. Bear/Base/Bull 三情景 + CHOOSE 聚合
- **三个独立情景 block** (行 14-45), 每个含 8 行假设 (Revenue Growth / EBIT Margin / D&A% / CapEx% / NWC% / Tax / TGR / WACC), 每行同时展示 **5 期历史实际比率** (col B-F) + **5 期预测输入** (col G-K, FY1-FY5)。
- **Selected Case Consolidation Block** (行 47-56) 用 `=CHOOSE($B$4, Bear, Base, Bull)` 引用所选情景。所有下游财务/FCF/折现公式都引用**聚合列**, 避免散落的 IF 嵌套。
- **Base WACC** 跨 Sheet 引用 `=WACC!B18`, 与 CAPM 计算联动。
- **列布局**: A=标签, B-F=5 期历史 (右对齐, 最新历史在 F), G-K=FY1-FY5 预测。

### 3. Mid-year Convention 折现
`Discount Period = 0.5, 1.5, 2.5, 3.5, 4.5`, `DF = 1/(1+WACC)^period`。

### 4. Gordon Growth 终值
`TV = FCF(FY5) × (1+TGR) / (WACC - TGR)`, `PV of TV = TV × DF(FY5)`。
**约束**: TGR 必须 < WACC (脚本使用输入值; 用户可在假设块修改, Excel 会立即反映)。

### 5. TV / EV Sanity Check
估值汇总区自动计算 `TV/EV%`, 建议区间 50-70%。低于 40% 或高于 75% 是红旗。

### 6. 3 张 5×5 敏感性表 (75 个完整重算公式)
**位于 DCF sheet 底部** (schema.md 要求, 非独立 sheet):

| 表 | 行轴 | 列轴 | 输出 |
|---|---|---|---|
| 1 | WACC (±0.5%) | Terminal Growth (±0.5%) | Implied Price per Share |
| 2 | Revenue Growth FY1 (±2%) | EBIT Margin FY1 (±2%) | Implied Price per Share |
| 3 | Beta (±0.15) | Risk-Free Rate (±0.5%) | Implied Price per Share |

**中心格 (E101/E109/E117) 精确等于 Valuation Summary 的 Implied Price** — 已用 `formulas` 库验证。中心格用 `#BDD7EE` 中蓝填充 + 粗体。轴值围绕 base 对称展开 `[base-2Δ, base-Δ, base, base+Δ, base+2Δ]`。

### 7. 字体颜色 (schema.md 4 色)
| 颜色 | 含义 | 用途 |
|---|---|---|
| 蓝 `0000FF` | 硬编码输入 | Stock Price, Growth%, Beta, TGR |
| 黑 `000000` | 计算公式 | `=E29*(1+$E$10)`, `=SUM()` |
| 紫 `800080` | 同 Sheet 直接引用 | `=B9`, `=D45` |
| 绿 `008000` | 跨 Sheet 引用 | `=WACC!B18` |

### 8. 填充色 (schema.md)
| 颜色 | 用途 |
|---|---|
| `#1F4E79` 深蓝 | Section headers (合并 A:G) |
| `#D9E1F2` 浅蓝 | Column / sub-headers |
| `#F2F2F2` 浅灰 | Input cells |
| `#BDD7EE` 中蓝 | Key outputs (Enterprise Value, Equity Value, Implied Price, Upside) + 敏感性中心格 |
| `#E2F0D9` 浅绿 | 预测列辨识 (Selected Assumptions & Projections) |
| `#FCE4D6` 浅橙 | Valuation Summary 区块底纹 |
| `#F4B183` 深橙 | Valuation Summary 关键结果 |

### 9. 数字格式 (schema.md 标准)
- Currency: `$#,##0;($#,##0);"-"` (百万单位: `#,##0;(#,##0);"-"`)
- 每股价格: `$#,##0.00`
- 百分比: `0.0%`
- 倍数: `0.0"x"`
- 贝塔: `0.00`
- 贴现因子: `0.0000`

### 10. Cell Comment 规范
- **硬编码输入**: `Source: [System/Document], [Date], [Reference]` (由 `add_source_comment()` 自动生成)
- **计算公式**: 显示计算逻辑 + 单元格引用

## 数据抽取规则

从 `futu-financial-report` 生成的 Excel 中提取:

| 字段 | 来源 | 备注 |
|---|---|---|
| Revenue | `总收入` / `营业总收入` | 最近 5 期 |
| EBIT | `营业利润` | |
| D&A | `折旧摊销及损耗` (Cash Flow 优先, Income 备选) | |
| CapEx | 现金流量表加工行 `资本开支(CapEx明细)` (仅明细字段, 无投资活动净额兜底) | 美股 8046 / 港股 5071+5073 / A 股 3043; 缺失时回退到明细字段名, 再回退到 income 的兜底口径 (会告警) |
| Tax Rate | `所得税 / 税前利润` | 亏损时用 25% |
| Debt | 短期借款 (`短期借款与融资租赁负债` / `银行贷款及透支`) + 长期借款 (`长期借款` / `长期银行贷款` / `长期融资租赁负债`) | 港股/美股/A 股字段命名差异, 每类取首项命中 |
| Cash | 现金及等价物 (`-现金和现金等价物` / `现金及等价物` / `货币资金`) + 短期投资 (`-短期投资` / `短期投资`) + 定期存款 (`定期存款-流动资产` + `定期存款-非流动资产` + `长期定期存款` + `短期存款` + `定期存款`, 全部累加) | 港股腾讯类公司常同时持有流动/非流动定期存款; 受限制现金 (港股"已抵押存款"/美股"受限制现金") **不**计入 |
| Stock Price / Shares | `Futu get_market_snapshot` | 实时行情 |
| Reporting Currency | 财报 Excel `col B` 单位字符串 (如"百万人民币"→CNY) | 反向映射 `_UNIT_NAME_TO_CURRENCY` |
| Trading Currency | 从 stock_code 前缀推断 (`US.*→USD, HK.*→HKD, SH./SZ.*→CNY`) | Futu snapshot 无此字段 |
| FX Rate | Futu FX snapshot (`HK.USDCNH` / `HK.USDHKD` / …) | 失败回退到 `_FX_FALLBACKS` 常量, 用户可在 `DCF!B10` 覆盖 |
| Beta (5Y Monthly) | Futu `request_history_kline` 拉 60 个月月线, `cov(个股, 基准) / var(基准)` | 基准按交易场所选: US.SPY / HK.800000 恒生 / SH.000300 沪深 300; 失败回退 `_BETA_FALLBACK=1.20` |
| Risk-Free Rate | 按报表币种查 `_RF_ERP_BY_CURRENCY` 常量表 | USD 4.3% / HKD 4.0% / CNY 2.5% / …; 用户可在 `WACC!B2` 覆盖 |
| Equity Risk Premium | 按报表币种查 `_RF_ERP_BY_CURRENCY` (Damodaran country ERP) | USD 5.5% / HKD 6.0% / CNY 6.5% / …; 用户可在 `WACC!B4` 覆盖 |

## Market Data 布局 (DCF Sheet 前 14 行)

| Row | 内容 |
|---|---|
| 1-2 | Header (含 Reporting/Trading Currency 标注) |
| 4-5 | Case Selector |
| 6 | Data Source 标注 (含 FX 来源) |
| 8 | MARKET DATA section header |
| 9 | Current Stock Price ({Trading}) — Futu 原始价 |
| 10 | **FX Rate: 1 {Trading} = X {Reporting}** — 蓝色输入, 用户可覆盖 |
| 11 | Current Stock Price ({Reporting}) = B9 × B10 (下游 Upside 比对基准) |
| 12 | Diluted Shares Outstanding (M) |
| 13 | Market Cap ({Reporting} M) = B11 × B12 (WACC Sheet 引用此格) |
| 14 | Net Debt / (Net Cash) ({Reporting} M) — 已在报表币种下 |

## 场景假设默认值

以最近 FY 的实际比率作为 Base, Bear/Bull 围绕 Base 上下浮动:

| 假设 | Bear | Base | Bull |
|---|---|---|---|
| Revenue Growth (FY1) | base - 3%, 后续递减 | base, 后续递减 | base + 3%, 后续递减 |
| EBIT Margin | base - 3%, 后续递减 | base 保持 | base + 3%, 后续递增 |
| Terminal Growth | 2.0% | 2.5% | 3.0% |
| WACC | 10.0% | =WACC!B18 (CAPM) | 8.0% |
| CapEx % | +1% vs base | base | -0.5% vs base |
| NWC % | 2% | 1% | 0.5% |

用户可在 Excel 中直接修改任意假设值 (蓝色单元格), 所有下游公式立即重算。

## 验证清单 (schema.md 定义)

生成后自动应符合:
- ✔ 无 `#REF!` / `#DIV/0!` / `#VALUE!` / `#NAME?` 错误
- ✔ Case Selector (B4=1/2/3) 切换后所有下游数值随之变化
- ✔ 敏感性表 3 张中心格 = Valuation Summary 的 Implied Price (精确匹配)
- ✔ TV / EV 比例 (显示在 B88) 落在 50-70% 建议区间
- ✔ TGR < WACC (脚本层面已确保默认值满足)
- ✔ OpEx 通过 EBIT Margin 直接驱动 (等价于基于 Revenue, 而非 Gross Profit)
- ✔ 所有蓝色输入均带 `Source: ...` 备注
- ✔ Net Debt 正确进入 EV→Equity bridge (Net Cash 时为负数, 会加回)

## 常见问题

**Q: BABA 生成的 Base 情景 Implied Price 为负?**
A: 因为 BABA 最近一年 (2026FY) 的 EBIT Margin 骤降至 5.83% (2025FY 为 14.76%), CapEx 占营收 12.3%, 导致 FCF 深度为负。这是真实数据信号, 用户可切换 Bull 情景 (调整增长/毛利假设) 得到正的估值。

**Q: BABA / PDD / JD 等 ADR 或港股 (HK.00700) 的 Upside% 数值离谱?**
A: 检查 `DCF!B10` 的 FX Rate 是否合理。ADR (US.BABA) 报表币种是 CNY 而股价是 USD, FX Rate 应约 7.2 (1 USD = 7.2 CNY); 港股 (HK.00700) 报表也常是 CNY 而股价是 HKD, FX 应约 0.92。若脚本运行时 Futu FX 快照失败, 会使用 `_FX_FALLBACKS` 常量, 请手动覆盖为实时汇率。

**Q: WACC Sheet 的 Beta / Rf / ERP 从哪里来?**
A: 三个都是个股/地区差异化:
- **Beta**: 用 Futu `request_history_kline` 拉 60 个月月线, `cov(个股月度收益, 大盘月度收益) / var(大盘月度收益)`。基准指数按交易场所选择: US → S&P 500 ETF (SPY), HK → 恒生指数 (HK.800000), A 股 → 沪深 300 (SH.000300)。若样本 < 24 个月或 Futu 不可用, 回退到默认 1.20。
- **Rf**: 按**报表币种**查 `_RF_ERP_BY_CURRENCY` 常量表 (10Y 主权债券收益率); 例如 CNY→2.5%, USD→4.3%。用户可在 `WACC!B2` 手工覆盖为实时数值。
- **ERP**: 同样按报表币种查 Damodaran country equity risk premium; CNY→6.5%, USD→5.5% 等。用户可在 `WACC!B4` 覆盖。

**Q: WACC Sheet 的 We > 100%, Wd < 0?**
A: 当公司持有 Net Cash (Net Debt 为负) 时, 债务权重为负是数学上正确的。此时 WACC = Cost of Equity × (1 + |Net Cash|/EV Cap) - Kd × Net Cash Portion, 略高于 Cost of Equity — 反映净现金公司的机会成本。

**Q: 敏感性表 Table 2 (Growth×Margin) 中心格与主模型完全一致吗?**
A: 是的 — 用 `formulas` 库验证 (E109 = B92 = $-77.62)。Table 2 仅让 FY1 的 Growth/Margin 变化, FY2-FY5 保持 base FCF, 所以中心格 (Growth=base, Margin=base) 精确等于主模型。

**Q: 如何修改情景假设?**
A: 编辑 `DCFBuilder.__init__` 中 `self.scenarios` 字典的对应 key。
