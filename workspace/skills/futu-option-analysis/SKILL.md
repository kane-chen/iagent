---
name: futu-option-analysis
description: 从富途OpenD获取港美正股/ETF的期权链快照（到期日、行权价、CALL/PUT最新价/买卖价/隐含波动率/未平仓量/Greeks/合约乘数），供期权策略（如买入宽跨式 strangle）收益分布计算与推荐。触发词：期权链、期权报价、宽跨式、strangle、期权套利、期权策略、未平仓量、隐含波动率
---

# 期权链数据获取 Skill

为期权策略分析提供原始行情数据。调用链路：`fetch_option_chain_json.py → futuapi skill（common/OpenQuoteContext）→ Futu OpenD（本地网关）→ 富途服务器`。

## 脚本

### `scripts/fetch_option_chain_json.py`

获取正股最近 N 个到期日的完整期权链 + 快照，输出结构化 JSON 文件。

```bash
python scripts/fetch_option_chain_json.py US.AAPL --expiries 4 --output /path/out.json
```

- 参数：`code`（带市场前缀，US./HK.，仅港美正股/ETF）、`--expiries`（最近到期日数，默认 4）、`--output`（JSON 输出路径，默认 workspace/temp/）
- 输出：`{code, market, currency, spot, expiries:[{strike_time, days_to_expiry, options:[{code, option_type, strike, last, bid, ask, iv, oi, volume, delta, contract_size}]}]}`
- 快照字段来自 `get_market_snapshot`（option_implied_volatility / option_open_interest 等）；港股 BMP 权限自动按 20 个代码/批降速分批
- 依赖：富途 OpenD 已启动并登录（见 futuapi skill）

> Java 财务服务的「期权宽跨式推荐」（`/api/financial/option-strangle`）通过本脚本取数，收益/胜率/手续费计算在 Java 侧完成。
