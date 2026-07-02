---
name: stock-ticker
description: 按公司名查股票代码（东方财富搜索接口）。覆盖 A 股（沪深京）/港股/美股，补齐市场归属、公司类型、财报类型、监管机构；支持按交易所优先级排序。触发词：股票代码、ticker、上市市场
---

# Stock Ticker Skill

**公司名 → 股票代码 + 市场归属 + 财报类型信息**

## 应用场景

给定"腾讯"/"BABA"/"格力电器"这样的公司名，返回：

- `symbol`（"00700"、"BABA"、"000651"）
- `exchangeName`（HKG / NASDAQ / SZSE ...）
- `marketRegion`（CN / HK / US / OTHER）
- `companyType`（CN_LISTED / HK_LISTED / US_DOMESTIC / FOREIGN_PRIVATE_ISSUER / OTHER）
- 年度报告 / 季度报告 / 半年度报告 三种财报的**报表类型名**（如美股本土 10-K/10-Q，中概股 20-F/6-K）
- `filingAuthority`（中国证监会 / 香港联交所 / 美国SEC / ...）


## 目录结构

```
workspace/skills/stock-ticker/
├── SKILL.md
├── config/
│   └── stock-search.json     # 市场码映射 / 过滤词 / ADR 名单 / profile 表
└── scripts/
    └── search_ticker.py      # 主脚本；只依赖 Python 标准库
```

## 数据源

东方财富搜索接口 `https://searchapi.eastmoney.com/api/suggest/get`，公开接口无需登录。
`config/stock-search.json` 里的 `token` 是东方财富前端固定值，接口只做参数校验，不构成鉴权。

## CLI

```bash
# 最简：默认 limit=1，返回优先级最高的一条
python workspace/skills/stock-ticker/scripts/search_ticker.py --company 阿里巴巴

# 多结果 + 缩进输出
python workspace/skills/stock-ticker/scripts/search_ticker.py --company 阿里巴巴 --limit 5 --pretty

# 指定优先交易所（覆盖 config 默认值）
python workspace/skills/stock-ticker/scripts/search_ticker.py --company 阿里巴巴 \
    --preferred-exchanges HKG,NASDAQ,NYSE

# 使用自定义配置（例如你有内部私有的 profile 表）
python workspace/skills/stock-ticker/scripts/search_ticker.py --company 阿里巴巴 \
    --config /path/to/private-stock-search.json
```

参数：

| 参数 | 说明 | 默认                         |
|---|---|----------------------------|
| `--company` | 必填。公司名或代码，例如 `阿里巴巴` / `BABA` / `腾讯` | -                          |
| `--limit` | 返回记录条数上限 | 默认为`1`                     |
| `--preferred-exchanges` | 优先交易所，逗号分隔，例如 `NASDAQ,HKG`。留空用 config 默认值 | 空                          |
| `--config` | 配置文件路径 | `config/stock-search.json` |
| `--pretty` | 缩进输出 JSON | 关闭                         |

## 输出格式

```json
{
  "success": true,
  "company": "阿里巴巴",
  "count": 2,
  "results": [
    {
      "symbol": "BABA",
      "name": "阿里巴巴",
      "exchange": 105,
      "exchangeName": "NASDAQ",
      "marketRegion": "US",
      "securityType": "美股",
      "companyType": "FOREIGN_PRIVATE_ISSUER",
      "annualReportType": "20-F",
      "quarterlyReportType": "6-K",
      "semiAnnualReportType": "N/A",
      "filingAuthority": "美国SEC",
      "matchScore": 1.0
    },
    {
      "symbol": "09988",
      "name": "阿里巴巴-W",
      "exchange": 116,
      "exchangeName": "HKG",
      "marketRegion": "HK",
      "securityType": "港股",
      "companyType": "HK_LISTED",
      "annualReportType": "年报",
      "quarterlyReportType": "季度业绩公告（自愿）",
      "semiAnnualReportType": "中期报告",
      "filingAuthority": "香港联交所",
      "matchScore": 0.95
    }
  ]
}
```

出错时 `success=false, error=<class: msg>`，退出码 `2`。

## 配置文件说明（stock-search.json）

所有可调参数集中在 `config/stock-search.json`，脚本只做流程编排：

| 节点 | 用途 |
|---|---|
| `search` | 东方财富接口 URL、UA、超时、单次拉取上限 |
| `marketMap` | MktNum → 交易所简称。新增交易所（比如 LSE、TSX）在这里加一条即可 |
| `filters.securityTypeTokens` | 只保留 SecurityTypeName 中含以下 token 的记录（过滤债券、指数） |
| `filters.derivativeKeywords` | 股票名含以下 token 一律过滤（ETF、杠杆、反向） |
| `filters.chineseAdrTokens` | 用于判定"美股中概股"，命中即走 20-F/6-K 分支 |
| `preferredExchanges.default` | CLI 不传 `--preferred-exchanges` 时的默认排序偏好 |
| `exchangeGroups` | 交易所 → 大区（CN/HK/US），决定 profile 走哪条分支 |
| `profiles.*` | 各大区（含 US_DOMESTIC / US_ADR）的公司类型、财报类型、监管机构 |
| `scoring` | 位次打分：baseScore - rank * decayPerRank |

**配置驱动的好处**：新增一家中概股（例如新的 `-ADR` 命名）、支持新交易所（比如 LSE 变常客）、
调整过滤规则，都只改 JSON 即可，脚本无需改动。

## 从 Java Tool 迁移的对应关系

原 `StockInfoTool.searchTicker` 的三个签名：

| Java 调用 | Skill 对应 |
|---|---|
| `searchTicker(companyName, preferredExchanges)` | `--company X --preferred-exchanges A,B --limit 1` |
| `searchTicker(companyName, limit, preferredExchanges)` | `--company X --limit N --preferred-exchanges A,B` |
| `searchTicker(companyName, preferredExchanges, limit)` | 同上 |

Java 的 `StockInfo` 数据类字段与本 skill 输出的 result 对象逐字段一一对应。

## 备注

- 该 skill 无第三方依赖，只用 Python 标准库
- 网络不通时脚本会以 `success=false` + 明确 error message 结束，不会挂起
