---
name: futu-financial-report
description: 从富途API获取上市公司财务数据，支持利润表/资产负债表/现金流量表，自动计算财务比率，适配A/H/美股，流式生成本地Excel。触发词：XX财报、XX利润表、XX资产负债表、XX现金流量表、XX财务数据
---

# 财务报表生成Skill

**全市场支持 + 三表合一 + 自动财务比率计算

## 核心特性

### 📊 报表类型
- **利润表**（含自动衍生财务比率）
- **资产负债表**
- **现金流量表**

### 📈 自动财务比率计算（仅利润表）
| 比率 | 公式 |
|-----|------|
| **毛利率** | 毛利 / 总收入 × 100% |
| **营业利润率** | 营业利润 / 总收入 × 100% |
| **净利润率** | 归属母公司净利润 / 总收入 × 100% |
| **营业费用率** | 营业费用 / 总收入 × 100% |
| 费用率（分项） | 各项费用 / 总收入 × 100% |

### 🔴 YoY同比智能高亮
- 同比下降 **超过5%** 的数据自动红色高亮

### 🎨 智能样式着色
| 类别 | 背景色 |
|-----|-------|
| 表头 | 浅蓝 |
| 收入/资产 | 浅蓝 |
| 成本/费用/负债/现金流出 | 浅红 |
| 利润/权益/现金流入 | 浅绿 |
| 核心利润率 | 橄榄绿 |
| 费用率 | 豆绿色 |
| 其他财务比率 | 高亮黄 |

### ⚡ 技术优化
- **全市场自动适配**：A股（SH/SZ）/ 港股（HK）/ 美股（US）
- **流式处理架构**：全程内存流式处理，无中间文件
- **stdout 极简输出**：仅进度标记 + JSON 摘要
- **详细日志系统**：排查日志独立写入 workspace/excels/logs/

## 参数说明

| 参数 | 说明 | 默认值 |
|-----|------|-------|
| `stock_code` | 股票代码 | 必填 |
| `--type` / `-t` | 报表类型：income / balance / cashflow | income |
| `--num` / `-n` | 季度/年度数量 | 16 |
| `--output` / `-o` | 输出Excel路径 | 自动生成 |

## 输出路径

- Excel文件：`workspace/excels/{代码}_{报表类型}_{时间}.xlsx`
- 日志文件：`workspace/excels/logs/financial_{代码}_{类型}_{时间}.log`

## 使用示例

```bash
# 阿里巴巴 - 利润表（默认16季度）
python ${workspace}/skills/futu-financial-report/scripts/generate_financial_excel.py US.BABA

# 腾讯 - 资产负债表
python ${workspace}/skills/futu-financial-report/scripts/generate_financial_excel.py HK.00700 --type balance --num 20

# 茅台 - 现金流量表
python ${workspace}/skills/futu-financial-report/scripts/generate_financial_excel.py SH.600519 --type cashflow
```

## stdout 输出规范

```
>>> Processing {stock_code} ({statement_name})
OK: Got N quarters data, M fields
OK: Currency: XXX, Unit: XXX
============================================================
{ JSON格式的结果摘要 }
============================================================
```

JSON 摘要示例：

```json
{
  "status": "ok",
  "stock_code": "US.BABA",
  "statement_type": "income",
  "statement_name": "利润表",
  "quarters": 16,
  "rows": 35,
  "yoy_highlight_threshold": "-5.0%",
  "excel_path": "绝对路径到Excel",
  "log_path": "绝对路径到日志",
  "period_range": "2026Q4 - 2026Q1"
}
```

## 文件结构

```
futu-financial-report/
├── SKILL.md                    # 本文档
└── scripts/
    ├── generate_financial_excel.py  # 主脚本
    └── requirements.txt            # 依赖说明
```