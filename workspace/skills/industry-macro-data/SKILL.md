---
name: industry-macro-data
description: 提供指定时间段内指定行业/宏观维度的统计数据（数据源：东方财富 datacenter，datacenter-web.eastmoney.com/api/data/v1/get）。支持"行业名 → 指标组"的语义查询（如"电商行业"自动返回社零总额时间序列）。触发词：行业数据、社零、宏观数据、CPI、PPI、GDP、工业增加值、房价指数。
---

# Industry Macro Data Skill

**行业名 + 时间段 → 行业宏观统计时间序列**

## 应用场景

给定「行业名」+ 「时间段」返回结构化时间序列数据，供 `industry-analysis` 子代理做行业体量、渗透率、增速分析。

## 数据源

东方财富 datacenter 公开接口：`https://datacenter-web.eastmoney.com/api/data/v1/get`

- 无需登录、无 cookie
- 通过 `reportName` 定位报表（每个宏观维度一个报表）
- 通过 `columns=ALL` 返回全量列（脚本按配置读取需要的 `valueColumn` / `yoyColumn`）
- 分页参数 `pageSize` / `pageNumber`，排序参数 `sortColumns=REPORT_DATE&sortTypes=-1`
- 响应体：`result.data[]`（每条为一期数据）+ `result.pages` / `result.count`

## 目录结构

```
workspace/skills/industry-macro-data/
├── SKILL.md
├── config/
│   └── industry-mapping.json       # 行业名 → EM reportName + valueColumn 映射；含派生指标公式
├── cache/
│   └── em_cache.json               # 首次调用后自动生成
└── scripts/
    └── query_industry.py           # 主脚本，只依赖 Python 标准库
```

## 本地缓存

- 缓存 Key：`"em|{reportName}|{valueColumn},{yoyColumn}|ps{pageSize}"`
- 默认 TTL：**24 小时**（宏观月度数据 T+15 日左右发布，24h 缓存足以复用）
- `--force-refresh`：跳过读缓存，仍回写；`--no-cache`：完全禁用；`--cache-ttl-hours N`：覆盖 TTL
- 原子写入（临时文件 + `os.replace`）

## CLI

```bash
# 最简：拉最近 12 期电商行业月度数据（社零总额）
python workspace/skills/industry-macro-data/scripts/query_industry.py \
    --industry 电商 --last 12

# 强制季度频次（会从月度序列中挑 3/6/9/12 月对齐季度末）
python workspace/skills/industry-macro-data/scripts/query_industry.py \
    --industry 电商 --last 12 --freq quarter

# GDP：本身是季度报表
python workspace/skills/industry-macro-data/scripts/query_industry.py \
    --industry gdp --last 8 --freq quarter --pretty
```

## 参数

| 参数                    | 说明                                                                     | 默认                            |
|-----------------------|------------------------------------------------------------------------|-------------------------------|
| `--industry`          | 行业名，与 `config/industry-mapping.json` 中的 key 或 alias 匹配（未命中报错并列候选） | 必填                            |
| `--freq`              | `year` / `quarter` / `month`                                           | 走 config 里 `freq_default`     |
| `--last`              | 最近 N 期                                                                 | `12`                          |
| `--config`            | 配置文件路径                                                                 | `config/industry-mapping.json`|
| `--pretty`            | 缩进 JSON                                                                | 关闭                            |
| `--cache-file`        | 本地缓存文件路径                                                               | `cache/em_cache.json`         |
| `--cache-ttl-hours`   | 缓存有效期（小时），`<=0` 表示永不过期                                                 | `24`                          |
| `--no-cache`          | 禁用缓存                                                                   | 关闭                            |
| `--force-refresh`     | 强制回源                                                                   | 关闭                            |
| `--timeout`           | 单次 HTTP 超时（秒）                                                          | `30`                          |

## 输出格式

```json
{
  "success": true,
  "industry": "电商",
  "freq": "quarter",
  "period_range": "2023Q3 – 2026Q2",
  "cacheHit": false,
  "indicators": [
    {
      "key": "TOTAL_RETAIL",
      "reportName": "RPT_ECONOMY_TOTAL_RETAIL",
      "valueColumn": "RETAIL_TOTAL",
      "name": "社会消费品零售总额",
      "unit": "亿元",
      "series": [
        {"period": "2023Q3", "value": 39826.0, "yoyPct": 5.5, "reportDate": "2023-09-01"},
        {"period": "2023Q4", "value": 43550.2, "yoyPct": 7.4, "reportDate": "2023-12-01"}
      ]
    }
  ],
  "derived": [],
  "sourceUrls": [
    "https://datacenter-web.eastmoney.com/api/data/v1/get?...&reportName=RPT_ECONOMY_TOTAL_RETAIL&columns=ALL"
  ],
  "notes": "东方财富公开数据未单独发布'实物商品网上零售额'月度序列；电商渗透率暂不可得，需在报告中标注为待补。",
  "queriedAt": "2026-07-19T21:15:21+08:00"
}
```

出错时：`{"success": false, "error": "<msg>", "hint": "..."}`，退出码 `2`。

## 配置文件说明（industry-mapping.json）

**两段式设计**：普通用户只动 `industries` 段；技术性字段全部收拢在 `indicators` 目录里。

```jsonc
{
  // === Tier 1：指标目录（开发者维护，一次性）===
  // 把 friendly 中文指标名映射到东方财富底层 reportName + valueColumn。
  "indicators": {
    "社零总额": {
      "description": "社会消费品零售总额（当月值）",
      "unit": "亿元",
      "freq": "month",
      "reportName": "RPT_ECONOMY_TOTAL_RETAIL",
      "valueColumn": "RETAIL_TOTAL",
      "yoyColumn": "RETAIL_TOTAL_SAME"
    },
    "CPI同比": { ... },
    "GDP":     { ... }
  },

  // === Tier 2：行业清单（用户友好，新增行业只需写 3 行）===
  "industries": {
    "电商": {
      "aliases": ["电子商务", "互联网电商"],
      "indicators": ["社零总额"]           // ← 只需引用指标名
    },
    "工业": {
      "aliases": ["规上工业"],
      "indicators": ["工业增加值同比", "工业增加值累计同比"]
    }
  }
}
```

### 用户扩展流程（新增行业）

1. 打开 `config/industry-mapping.json`
2. 在 `industries` 段下加一条：
   ```json
   "餐饮": {
     "aliases": ["餐饮业", "餐饮消费"],
     "indicators": ["社零总额"]
   }
   ```
3. 保存 → 立刻可用 `--industry 餐饮` 查询

> 引用不存在的指标名时脚本会立即报错并列出目录里所有可用指标名，不会静默失败。

### 开发者扩展流程（新增指标）

只有当**需要一个 `indicators` 目录里还没有的指标**时才需要动指标目录：

1. 定位东方财富对应页面（`data.eastmoney.com/cjsj/*.html`）
2. 查看其静态 JS（`/newstatic/js/cjsj/cn/*.js`）里的 `reportName:` 与 `columns:`
3. 在 `indicators` 段加一条：
   ```json
   "新指标名": {
     "description": "...",
     "unit": "%",
     "freq": "month",
     "reportName": "RPT_ECONOMY_XXX",
     "valueColumn": "XXX_COL",
     "yoyColumn": "XXX_COL_SAME"   // 可选，如接口已直接返回同比列
   }
   ```

**要点**：

- `yoyColumn` 可选：接口已直接返回同比列时填上（例如 `RETAIL_TOTAL_SAME`），脚本直接透传；未配置时 `yoyPct` 输出为 `null`（不再本地计算，避免对已经是"同比%"的字段做二次同比）。
- **向后兼容**：`industries.<行业>.indicators` 允许字符串引用 (`"社零总额"`)，也允许内联完整对象 `{reportName, valueColumn, ...}`（旧写法），两种可以混写。

## 已知覆盖度（Known Coverage）

| 行业 / 维度 | reportName | 主要列 | 频次 |
|-------------|------------|--------|------|
| 电商 / 汽车 / 餐饮（复用社零） | `RPT_ECONOMY_TOTAL_RETAIL` | RETAIL_TOTAL, RETAIL_TOTAL_SAME | month |
| GDP | `RPT_ECONOMY_GDP` | DOMESTICL_PRODUCT_BASE, SUM_SAME | quarter |
| CPI | `RPT_ECONOMY_CPI` | NATIONAL_SAME | month |
| PPI | `RPT_ECONOMY_PPI` | BASE_SAME | month |
| 工业增加值 | `RPT_ECONOMY_INDUS_GROW` | BASE_SAME, BASE_ACCUMULATE | month |
| 房地产（房价指数） | `RPT_ECONOMY_HOUSE_PRICE` | NEW_HOUSE_SAME | month |

**已知缺口**：东方财富公开数据中心未单独发布「实物商品网上零售额」月度序列 → 电商渗透率派生指标暂缺，需在报告中标注为待补（可回退到 `web_search` 或 Wind / Choice 数据源）。

## 备注

- 该 skill 无第三方依赖，只用 Python 标准库（urllib / json / ssl / ast / argparse / logging）
- 东方财富接口偶尔波动，脚本自带 3 次重试 + 指数退避（1s / 2s / 4s）
- YoY 数据优先使用东方财富返回的同比列；缺失时本地按同频次上溯（年 → 1、季 → 4、月 → 12 期）计算
- 使用 `industry-analysis-subagent` 时优先按行业名调用；参数从子代理澄清结果透传
