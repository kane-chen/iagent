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
| Debt Schedule | 债务偿还计划 | 多档 roll-forward, Interest 用期初余额 (断循环), Cash Sweep 按 Revolver→TLA→TLB→Notes 瀑布 |
| Returns Analysis | 回报 + 敏感性 | Exit EV/Equity, MOIC, IRR (基于现金流系列) + 3 张 5×5 敏感性表 |

## 运行方式

```bash
python scripts/build_lbo_model.py --ticker BABA --workspace /path/to/workspace
# 可选参数:
python scripts/build_lbo_model.py --ticker BABA --workspace /path/to/workspace --entry-multiple 10.0 --exit-multiple 11.0
```

**前置条件**: `workspace/excels/{ticker}_income_*.xlsx`、`{ticker}_balance_*.xlsx`、`{ticker}_cashflow_*.xlsx` 已由 `futu-financial-report` skill 生成。

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

从富途生成的 Excel 中提取 LTM 数据:
- **Revenue**: `总收入` 或 `营业总收入`
- **EBIT**: `营业利润`
- **D&A**: `折旧摊销及损耗` (Cash Flow 优先,Income Statement 备选)
- **EBITDA**: `EBIT + D&A` (若 D&A 缺失,以 CapEx × 70% 估算)
- **CapEx**: `资本开支(CapEx)` 或 `资本开支`
- **Tax Rate**: `所得税 / 税前利润` (若为负则用默认 25%)
- **Debt**: `短期借款与融资租赁负债 + 长期借款`
- **Revenue Growth**: 最近两年营收增速 (超过 [-20%, +50%] 时收敛至 5%)

## 验证清单

生成后自动应符合:
- ✔ Sources = Uses (勾稽应为 0)
- ✔ 债务余额不为负 (`MAX(0, ...)` 保护)
- ✔ Interest 用期初余额 (循环引用断开)
- ✔ Cash Sweep 遵循 Revolver → TLA → TLB → Notes 优先级
- ✔ IRR/MOIC 符号正确 (Y0 投入负, Y5 退出正)
- ✔ 敏感性表中心格 ≈ 模型实际 IRR/MOIC (Table 3 精确一致,Table 1/2 因闭式近似略有偏差)
- ✔ 无 `#REF!` / `#DIV/0!` / `#VALUE!` / `#NAME?`

## 常见问题

**Q: 为什么 Table 1 中心格与主 IRR 不完全一致?**
A: 敏感性表用闭式近似 (holds all other assumptions constant, Debt/FCF scale with axis vars),主 IRR 由完整现金流迭代得出。Table 3 (Growth × Margin) 保持 Net Debt at Exit 为 base,所以中心格精确等于主 IRR。

**Q: Levered FCF 为负会怎样?**
A: 说明该杠杆/倍数组合不可行 (公司现金不足以覆盖利息+CapEx)。Cash Sweep 会自动降为 0,债务余额不会异常。这是有效的商业信号。

**Q: 如何修改债务档结构?**
A: 编辑 `LBOBuilder.tranches` 列表:`(name, share_of_total_debt, interest_rate, mandatory_amort_pct/year)`。所有下游行数会自动重算。
