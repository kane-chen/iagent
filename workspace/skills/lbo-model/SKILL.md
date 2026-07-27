---
name: lbo-model
description: 生成投行标准的 LBO (Leveraged Buyout) 4-Tab Excel 模型 (Sources & Uses / Operating Model / Debt Schedule / Returns Analysis + 3 张 5×5 敏感性表)。所有计算都是活公式,输入改变时自动重算。
---

## 概述

本 skill 用 `openpyxl` 生成标准 LBO 模型。**每个计算都是 Excel 公式**,从不把 Python 计算结果硬写入单元格,保证模型可交互。

**输出结构 (对齐 `references/schema.md`)**

| Tab | 内容 | 核心逻辑 |
|---|---|---|
| Sources & Uses | 资金来源与用途 | 4 档债务 (Revolver / TLA / TLB / Senior Notes) + Sponsor Equity Plug; Sources = Uses |
| Operating Model | 5 年经营预测 | Revenue Growth × EBITDA Margin 驱动,EBIT → Interest → Tax → Net Income → FCF |
| Debt Schedule | 债务偿还计划 + Credit Metrics | 多档 roll-forward, Interest 用期初余额 (断循环), Cash Sweep 按 Revolver→TLA→TLB→Notes 瀑布; 附 Leverage / Net Leverage / Interest Cov / DSCR + Cumulative Paydown |
| Returns Analysis | 回报 + Value Bridge + 敏感性 | Exit EV/Equity, MOIC, IRR (基于现金流系列) + Value Creation Bridge (EBITDA Growth / Multiple Expansion / Debt Paydown / Fees Wedge, 精确勾稽 Exit Equity - Initial Equity) + 3 张 5×5 敏感性表 |

## 运行方式

```bash
python scripts/build_lbo_model.py --ticker BABA --workspace /path/to/workspace
# 可选参数:
python scripts/build_lbo_model.py --ticker BABA --workspace /path/to/workspace --entry-multiple 10.0 --exit-multiple 11.0
```

**前置条件**: Futu OpenD 已启动并登录 (脚本会通过 `workspace/skills/3-statement-model` 复用富途 API 抽取三表历史数据; 与 3-Statement / DCF skill 共享同一份口径, 无需依赖本地 `workspace/excels/{ticker}_*_*.xlsx` 文件)。

## 关键实现原则

### 1. 每个计算都必须是 Excel 公式
写 `cell.value = "=B5*B6"` 而不是 `cell.value = 1250`。

### 2. Sources = Uses (勾稽平衡)
- `Sponsor Equity = Total Uses - Σ Debt Tranches` 作为 Plug
- 单独设置 Check 行 `=Total Sources - Total Uses` 应恒为 0

### 3. 多档债务 + Cash Sweep 优先级瀑布
- 债务分档 (默认): Revolver 0% / TLA 25% @ 5.5% (10%/年强制摊销) / TLB 45% @ 6.5% / Notes 30% @ 8.0%
- **Interest 用期初余额** 计算,避免与还款额形成循环引用
- Cash Sweep 按优先级顺序偿还,每档 `=-MIN(该档剩余余额, 剩余可用现金)`
- Ending Balance 用 `MAX(0, ...)` 约束不为负

### 4. IRR 用现金流系列
Year 0 = -Initial Equity (负), Year 1-4 = 0 (假设无分红), Year 5 = +Exit Equity (正)
`IRR(B13:G13)` 而不是简单的 `MOIC^(1/5)-1`

### 5. 3 张敏感性表 (5×5, 奇数维度)
每格是闭式重算公式 (75 个公式总计),非 Excel Data Table:
- **Table 1**: Entry × Exit Multiple → IRR (每格重算债务规模)
- **Table 2**: Entry × Leverage → MOIC
- **Table 3**: Revenue Growth × EBITDA Margin → IRR
- 中心格 = Base Case, 用 `#BDD7EE` 中蓝填充 + 粗体
- 轴值围绕 base 对称展开: `[base-2Δ, base-Δ, base, base+Δ, base+2Δ]`

## 字体颜色约定

| 颜色 | 含义 | 示例 |
|---|---|---|
| 蓝色 `0000FF` | 硬编码输入 | Entry Multiple, Growth %, Debt Rate |
| 黑色 `000000` | 计算公式 | `=B5*B6`, `=SUM()` |
| 紫色 `800080` | 同 Sheet 引用 | `=B9`, `=D45` |
| 绿色 `008000` | 跨 Sheet 引用 | `=Sources & Uses!B5`, `='Operating Model'!C10` |

## 填充色

| 填充色 | 用途 |
|---|---|
| `#1F4E79` 深蓝 | Section header |
| `#D9E1F2` 浅蓝 | Column header |
| `#F2F2F2` 浅灰 | Input cell |
| `#BDD7EE` 中蓝 | Key output (IRR, MOIC, Exit Equity) + 敏感性表中心格 |

## 气泡备注

每个计算单元格都附带 `Comment`,内容为「计算公式 + 单元格引用」,便于用户理解模型逻辑。

## 数据抽取规则

**数据源**: 通过 `workspace/skills/3-statement-model/scripts/build_3_statement_model.py` 的 `extract_financial_data` 从富途 API (`get_financials_statements`) 拉取三表历史 (年报口径), 与 3-Statement / DCF sheet 共享同一口径. 主要字段:

- **Revenue**: 富途 `总收入` / `营业总收入` (income), 取最新一期 (LTM)
- **EBIT**: 富途 `营业利润` (income)
- **D&A**: 优先 CF-side 广口径 `折旧摊销及损耗` / `折旧与摊销` / `折旧及摊销` (跨市场兼容 US fid 5059 / A股 fid 3002 / HK fid 5059), 缺失时回退 IS-side; 三方都缺则以 CapEx × 70% 估算 (行业经验值, 会 log warning)
- **EBITDA**: `EBIT + D&A` (与 3-Statement CF 加回口径一致)
- **CapEx**: 3-statement 跨市场 fallback (港股 5071+5073 / 美股 8046+8047 / A股 3043), 缺失时以 5% 营收兜底
- **Tax Rate**: `所得税 / 税前利润` (若 EBT 为负则用默认 25%)
- **Debt**: BS L3 `短期借款与融资租赁 + 长期借款` (与 DCF Gross Debt 口径一致)
- **NWC%**: 3-statement 的 ΔWC / ΔRevenue 最近 3 年均值 (与 DCF 一致, LBO 场景取绝对值不为负)
- **Revenue Growth**: 3-statement 最近一年 hist_rev_growth (超过 [-20%, +50%] 时收敛至 5%)
- **币种**: 3-statement 已完成识别 + FX 换算 (新浪财经优先, Futu FX 兜底)

## 中英文对照展示

所有指标名采用 `{English} -- {中文}` 格式便于中英文用户交叉阅读:
- Section 标题: `SOURCES & USES -- 资金来源与用途`
- 债务分档: `Term Loan A (25%) -- 定期贷款A`, `Senior Notes (30%) -- 优先债券`
- 财务指标: `EBITDA -- 息税折旧摊销前利润`, `EBIT -- 息税前利润`, `Levered FCF -- 有杠杆自由现金流`
- 期间: `Closing (LTM) -- 过去12个月`, `Year 1 -- 第1年` … `Year 5 -- 第5年`
- 敏感性表:
  - 表标题: `TABLE 1: Entry × Exit Multiple → IRR -- 入场倍数 × 退出倍数 → 内部收益率`
  - 轴说明行 (row+1): `Row axis (↓) / 行轴: ... | Col axis (→) / 列轴: ...`
  - 列轴 header 行 (row+2): 首列写 `{col_axis_label} →` (例如 `Exit Multiple / 退出倍数 →`), 其后单元格填列轴取值
  - 每个数据行 (row+3..row+7): 首列写 `{row_axis_label} ↓` (例如 `Entry Multiple ↓ / 入场倍数`), col B 填行轴取值, C-G 填闭式重算公式

## 验证清单

生成后自动应符合:
- ✔ Sources = Uses (S&U 勾稽应为 0)
- ✔ 债务余额不为负 (`MAX(0, ...)` 保护)
- ✔ Interest 用期初余额 (循环引用断开)
- ✔ Cash Sweep 遵循 Revolver → TLA → TLB → Notes 优先级
- ✔ IRR/MOIC 符号正确 (Y0 投入负, Y5 退出正)
- ✔ Value Creation Bridge 精确勾稽: `Check = (Exit Eq - Init Eq) - (EBITDA Growth + Multiple Expansion + Debt Paydown + Fees Wedge) = 0`
- ✔ Debt Schedule Credit Metrics 每年有值 (Closing 期 Int Cov / DSCR = "N/A" 因债务未起息)
- ✔ 敏感性表中心格 ≈ 模型实际 IRR/MOIC (Table 3 精确一致,Table 1/2 因闭式近似略有偏差)
- ✔ 无 `#REF!` / `#DIV/0!` / `#VALUE!` / `#NAME?`

## 常见问题

**Q: 为什么 Table 1 中心格与主 IRR 不完全一致?**
A: 敏感性表用闭式近似 (holds all other assumptions constant, Debt/FCF scale with axis vars),主 IRR 由完整现金流迭代得出。Table 3 (Growth × Margin) 保持 Net Debt at Exit 为 base,所以中心格精确等于主 IRR。

**Q: Levered FCF 为负会怎样?**
A: 说明该杠杆/倍数组合不可行 (公司现金不足以覆盖利息+CapEx)。Cash Sweep 会自动降为 0,债务余额不会异常。这是有效的商业信号。例如 BABA 2026FY CapEx 12.3% 显著高于 EBITDA Margin 10.4%, LBO 场景下 Levered FCF 深度为负。

**Q: 如何修改债务档结构?**
A: 编辑 `LBOBuilder.tranches` 列表:`(name, share_of_total_debt, interest_rate, mandatory_amort_pct/year)`。所有下游行数会自动重算。

**Q: 为什么会出现 `D&A 缺失, 以 CapEx × 70% 估算` 的 warning?**
A: 某些公司 (如 00700 腾讯 / 83690 美团 / PDD 拼多多) 在富途现金流表与利润表都未单列 `折旧摊销及损耗` 字段, 只能用 CapEx × 70% 作为经验估算 (行业经验值)。此估算会推高 EBITDA (EBIT + D&A), 用户应查 10-K/年报手动覆盖 `Operating Model!B7` (LTM EBITDA) 或 `B8` (D&A%) 得到准确值。

**Q: 为什么表格里所有指标都是中英对照 `{英} -- {中}` 格式?**
A: 便于中英文用户交叉阅读; Section header / 债务分档名 / 财务指标 / 期间列头 / 敏感性表轴说明 均已配对。参考"中英文对照展示"章节的完整对照表。
