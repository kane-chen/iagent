---
name: futu-financial-report
description: 从富途OpenD获取上市公司财务数据，生成利润表/资产负债表/现金流量表Excel（自动计算毛利率/营业利润率/净利润率等比率，适配A/H/美股，YoY同比高亮）。触发词：XX财报、XX利润表、XX资产负债表、XX现金流量表、XX财务数据、XX三表
---

# 财务报表生成 Skill

**一句话**：告诉我公司名或股票代码 + 想要的报表，我直接生成 Excel 给你。不需要你自己敲命令。

## 快速开始（99% 的用户用这个就够了）

### 怎么触发？

**直接用自然语言说话就行**，不需要写代码、不需要敲命令。比如：

- "帮我拉一下腾讯最近 16 个季度的利润表"
- "BABA 的资产负债表"
- "给我看看茅台的现金流量表"
- "阿里巴巴的财报"（默认利润表）
- "美团最近 20 期年报资产负债表"

我会自动完成：查股票代码 → 连富途拉数据 → 生成 Excel → 告诉你文件路径。

### 前提（一次性准备，运维同学负责）

本 Skill 依赖富途 OpenAPI 生态，调用链路：`generate_financial_excel.py → futuapi skill（子进程） → Futu OpenD（本地网关） → 富途服务器`。

> 📖 **官方文档**：完整的 FutuOpenD + futu-api AI 接入说明见富途官方文档：
> https://openapi.futunn.com/futu-api-doc/intro/ai.html

使用前需安装两个核心组件：**FutuOpenD（本地网关）** 和 **futu-api（Python SDK）**，以及项目内的 **futuapi skill**。

---

#### 1. FutuOpenD（本地网关）

FutuOpenD（简称 OpenD）是富途官方提供的**本地 API 网关程序**，所有行情/财务/交易请求都通过它转发到富途服务器。**必须先启动并登录 OpenD** 才能使用本 skill，没有它 futu-api SDK 无法工作。

**架构原理**：
```
Python 脚本 (futu-api SDK)
    ↕ TCP (默认 127.0.0.1:11111)
FutuOpenD (本地网关，常驻运行)
    ↕ 加密 WebSocket
富途服务器 (行情/财务数据源)
```

**安装步骤**：

1. **下载**：访问 https://www.futunn.com/download/OpenAPI ，选择对应操作系统的 **OpenD-GUI** 版本（有图形界面，便于登录和查看状态）
   - Windows: `FutuOpenD-GUI-Windows`
   - macOS: `FutuOpenD-GUI-Mac`
   - Linux: 可使用无界面版 `FutuOpenD-CMD-Linux`，需额外配置 XML 或命令行参数
2. **安装**：按常规方式安装，启动程序
3. **登录**：使用你的**富途牛牛/富途期货账号**扫码或密码登录（必须是有行情权限的实盘或模拟盘账号）
4. **保持运行**：OpenD 是常驻程序，启动登录后最小化即可，**不需要每次重启 skill 都重启 OpenD**

**版本要求**：
- **最低版本**：>= **10.4.6408**（本 skill 的财务报表接口依赖此版本以上）
- 加密货币功能需要 >= 10.5.6508（本 skill 不涉及）
- 建议保持 OpenD 为最新版本，老版本可能缺少新字段或存在已知 bug

**关键配置（OpenD 界面中）**：

| 配置项 | 默认值 | 说明 |
|-------|-------|------|
| API 监听地址 | `127.0.0.1` | **建议保持 127.0.0.1**，不要监听 `0.0.0.0`（会暴露到公网，安全风险）|
| API 端口 | `11111` | 本 skill 默认连接此端口；如需修改需同步设置环境变量 `FUTU_OPEND_PORT` |
| API 解锁密码 | 空（未设置） | 交易接口需要解锁，行情/财务接口不需要 |
| 登录账号 | 你的富途账号 | 未登录时 API 返回空数据或连接被拒绝 |

**⚠️ 安全提醒**：
- **不要**将 OpenD 监听地址设为 `0.0.0.0` 或暴露到公网，任何人连上你的 OpenD 都可以操作你的账户
- 如果需要在局域网/远程服务器部署，务必通过 SSH 隧道或 VPN 访问，**不要直接暴露 11111 端口**
- OpenD 登录后保持会话即可，不需要重复登录；但账号异地登录会挤掉当前会话

**登录状态检查**：
- OpenD 界面标题栏或状态栏会显示当前登录账号名
- 如果显示"未登录"或"已断开"，所有 API 调用都会失败
- 行情权限：港股/美股/A股行情权限取决于你的富途账号是否开通了对应市场

**远程/无界面部署（可选）**：

如果在无 GUI 的 Linux 服务器上部署，可使用 CMD 版本通过配置文件启动：
```bash
# Linux CMD 版本示例
./FutuOpenD-CMD -login_account=你的账号 -login_pwd_md5=MD5后的密码 \
    -api_port=11111 -ip=127.0.0.1
```
密码需先 MD5 哈希（32位小写），具体配置项参考官方文档。

---

#### 2. futu-api（Python SDK）

futu-api 是富途官方提供的 Python SDK，封装了与 OpenD 的通信协议（基于 Protobuf + TCP）。本 skill 通过子进程调用 `workspace/skills/futuapi/` 下的脚本，这些脚本 import 并使用 futu-api。

**Python 版本要求**：Python **3.8 ~ 3.12**（3.13+ 可能存在兼容问题，建议用 3.9~3.11）

**安装**：

```bash
# 安装指定版本（推荐，与 OpenD 版本配套）
pip install "futu-api>=10.4.6408"

# 或者安装最新版
pip install futu-api --upgrade
```

**验证安装**：
```bash
python -c "from futu import *; print(f'futu-api {futu.__version__} OK')"
```

输出示例：`futu-api 10.4.6408 OK`

**pip 镜像加速（国内用户）**：如果安装慢可使用国内镜像：
```bash
pip install "futu-api>=10.4.6408" -i https://pypi.tuna.tsinghua.edu.cn/simple
```

**SDK 核心模块（供开发参考）**：

| 模块 | 用途 | 本 skill 使用的接口 |
|------|------|-------------------|
| `OpenQuoteContext` | 行情/财务数据上下文 | `get_financial_report()`（三表数据）|
| `OpenSecTradingContext` | 证券交易上下文 | 不使用 |
| `OpenFutureTradingContext` | 期货交易上下文 | 不使用 |

> 💡 本 skill 只使用**行情/财务**接口（只读），不涉及任何交易操作，不需要 API 解锁密码。

---

#### 3. futuapi skill 目录

本 skill 复用项目内的 `workspace/skills/futuapi/`（富途行情/财务 API 脚本集合），作为子进程调用。该目录必须完整存在，核心文件：

- `scripts/common.py` — 公共连接/工具函数（创建 OpenQuoteContext 等）
- `scripts/quote/get_financials_statements.py` — 财务报表数据拉取脚本
- `scripts/check_env.py` — 环境检查脚本
- `docs/` — API 参考文档和排错指南

如果该目录不存在或文件缺失，需要从项目仓库中获取。

---

#### 4. Excel 生成依赖

```bash
pip install -r workspace/skills/futu-financial-report/scripts/requirements.txt
```

`requirements.txt` 内容：
```
openpyxl>=3.1.0      # Excel 读写
futu-api>=10.4.6408   # 富途 Python SDK（与上面手动安装的是同一个）
```

如果已经通过步骤 2 安装了 futu-api，pip 会跳过已满足的依赖。

---

#### 5. 一键环境检查

所有依赖安装完成、OpenD 启动并登录后，跑一次环境检查脚本确认一切正常：

```bash
python workspace/skills/futuapi/scripts/check_env.py
```

输出示例（全部通过）：
```
  ✓ SDK: futu-api 10.4.6408
  ✓ OpenD: OpenD 可连接 (127.0.0.1:11111)

环境检查通过
```

输出示例（有问题）：
```
  ✓ SDK: futu-api 10.4.6408
  ✗ OpenD: 无法连接 OpenD (127.0.0.1:11111): [WinError 10061] ...请先启动 OpenD

环境检查未通过，部分功能可能不可用
```

**自定义 OpenD 地址**（非默认端口或远程部署场景）：
```bash
# 通过环境变量指定 OpenD 地址
FUTU_OPEND_HOST=192.168.1.100 FUTU_OPEND_PORT=11111 python workspace/skills/futuapi/scripts/check_env.py
```

JSON 输出（便于程序解析）：
```bash
python workspace/skills/futuapi/scripts/check_env.py --json
```

---

#### 依赖关系速查表

| 组件 | 类型 | 是否必须 | 安装方式 | 用途 |
|------|------|---------|---------|------|
| **FutuOpenD** | 本地常驻程序 | ✅ 必须 | 官网下载安装 | API 网关，连接富途服务器 |
| **futu-api** | Python pip 包 | ✅ 必须 | `pip install futu-api` | Python SDK，与 OpenD 通信 |
| **openpyxl** | Python pip 包 | ✅ 必须 | `pip install openpyxl` | Excel 文件生成 |
| **futuapi skill** | 项目内脚本目录 | ✅ 必须 | 项目仓库自带 | 封装好的财务数据拉取脚本 |
| **富途账号** | 券商账号 | ✅ 必须 | 富途开户 | 登录 OpenD，需有对应市场行情权限 |

> 💡 **普通用户不用管这些准备工作**，直接告诉我"帮我拉 XX 财报"就行。如果环境有问题，我会告诉你具体缺什么、怎么装、哪一步出了错。

---

### 股票代码怎么给？

给公司中文名、英文名、股票代码都行：

| 你说的 | 我会识别为 |
|-------|----------|
| 腾讯、腾讯控股 | HK.00700 |
| 阿里、阿里巴巴、BABA | US.BABA（美股）/ HK.09988（港股）|
| 茅台、贵州茅台 | SH.600519 |
| 苹果、AAPL、Apple | US.AAPL |
| US.BABA / HK.00700 / SH.600519 | 直接用（跳过查代码步骤）|

> 代码格式：`市场前缀.代码`。美股 `US.TICKER`，港股 `HK.5位数字`，A 股 `SH./SZ. + 6位数字`。不确定就说公司名，我帮你查。

### 要哪种报表？

| 说法 | 报表类型 |
|-----|---------|
| "利润表" / "income" / "业绩" / "财报"（默认） | 利润表（含毛利率、利润率、费用率） |
| "资产负债表" / "balance" / "资产负债" | 资产负债表 |
| "现金流量表" / "cashflow" / "现金流" | 现金流量表 |

### 想要多少期？

- 默认 **16 季度**（三张表统一按季度维度）
- 说 "最近 20 期" / "近 5 年" / "--num 8" 来覆盖

---

## 输出

生成的 Excel 在 `workspace/excels/{代码}_{报表类型}_{时间戳}.xlsx`，我会把绝对路径告诉你。

### Excel 长什么样？（不用跑就能脑补）

表格结构是「指标在左、时间在右」的经典财务报表横排布局：

```
┌──────────────────────┬──────────┬─────────┬─────────┬─────────┬─────────┐
│ 财务指标              │ 单位      │ 2025Q4  │ 2025Q3  │ 2025Q2  │ 2025Q1  │ ...  ← 表头（浅蓝底）
│ 财报日期              │          │2025-12-31│2025-09-30│2025-06-30│2025-03-31│
├──────────────────────┼──────────┼─────────┼─────────┼─────────┼─────────┤
│ 营业总收入            │ 百万美元  │ 248,012 │ 227,049 │ 239,019 │ 205,421 │ ...  ← 收入（浅蓝底）
│ 营业总收入 YoY        │ %        │   5.2   │   8.1   │  12.3   │   3.7   │ ...  ← YoY 行（异常值红/绿）
│ 营业总成本            │ 百万美元  │ 189,203 │ ...                                          ← 成本（浅红底）
│ 营业总成本 YoY        │ %        │  ...
│   ─ 毛利率            │ %        │  23.7   │  ...                                          ← 自动计算的比率（橄榄绿/豆绿）
│ 销售费用              │ 百万美元  │  ...
│ 销售费用率            │ %        │  ...
│ ...                                                                          │
│ 营业利润              │ 百万美元  │  ...                                                         ← 利润（浅绿底）
│ 营业利润率            │ %        │  ...
│ ...                                                                          │
└──────────────────────┴──────────┴─────────┴─────────┴─────────┴─────────┘
```

**关键视觉约定：**
- 列A=指标名（固定），列B=单位，列C起=各季度/年度数据（按时间倒序或正序由返回数据决定）
- 每个数值指标后面紧跟一行同名 "YoY" 行（资产负债表除外，因为是时点数据不计算同比）
- 利润表的比率行（毛利率/营业利润率/净利润率/各项费用率）自动计算并插在对应父项下面
- 冻结首两行+前两列（C3 单元格冻结），滚动时能一直看到指标名和表头
- 带自动筛选，可以按指标名筛选

**颜色含义（别背，看到 Excel 一眼就明白）：**
| 背景色 | 含义 | 典型字段 |
|-------|------|---------|
| 浅蓝 `#D9E1F2` | 收入/资产/表头 | 营业总收入、资产合计、表头 |
| 浅红 `#FCE4D6` | 成本/费用/负债/流出/税 | 营业总成本、销售费用、负债合计、所得税 |
| 浅绿 `#E2EFDA` | 利润/权益/流入 | 营业利润、净利润、股东权益合计、经营现金流 |
| 中蓝 `#BDD7EE` | 加工指标 | FCFF、CapEx、ROA、ROE |
| 浅橙 `#FFD9B3` | 现金流质量比率 | 自由现金流净利润比 FCF/NI |
| 橄榄绿 `#9BBB59` | 核心利润率 | 毛利率、营业利润率、净利润率 |
| 豆绿 `#D8E4BC` | 费用率 | 各项费用率 |
| 高亮黄 `#FFF2CC` | 其他比率 | — |
| **红字加粗** | YoY 同比下降 >5% | 预警 |
| **绿字加粗** | YoY 同比增长 >30% | 高增长 |

**文件大小预期**：16 季度利润表 Excel 约 10~30 KB；打开秒开，不会卡顿。

### Excel 特性

- 利润表自动算毛利率、营业利润率、净利润率、各项费用率
- YoY 同比：下降 >5% 红字、增长 >30% 绿字高亮
- 按类别配色（收入蓝、成本红、利润绿等）
- 冻结窗格 + 自动筛选
- 数值自动换算到百万单位（原始数据是元）

---

## 参数边界（Agent 注意）

| 参数 | 允许范围 | 实际建议 | 超界行为 |
|-----|---------|---------|---------|
| `stock_code` | 长度 2~32，必须含 `.`，前缀 US/HK/SH/SZ | 正常 ticker 如 `US.BABA`（2~8字符）| 格式错误立即报错，提示正确格式 |
| `--type` / `-t` | 只能是 `income` / `balance` / `cashflow` 三个值之一 | 按用户需求选 | argparse 直接报错并打印可用值 |
| `--num` / `-n` | 底层 API 限制 **1~50**（单页最多 50 条） | 利润表默认 16 季度（约4年），最多 40；资产负债表/现金流量表默认 16 年（大多数公司上市不到 50 年）| 超过 50 时底层 API 按 50 处理；太大不会报错但 Excel 会变宽，打开变慢 |
| `--output` / `-o` | 任意可写路径 | 省略，用默认路径 | 父目录不存在会自动创建；文件被占用报 PermissionError |

**关于 `--num` 的实际含义（三表统一）：**
- **利润表（income）**：`--num` = **季度数**。港股/A股走累计季报（Q1/H1/Q9/FY），返回的条目会是「一季报+半年报+三季报+年报」，不是独立 Q1/Q2/Q3/Q4，所以 16 个条目对应的是约 4 年的季报+年报。
- **资产负债表（balance）**：`--num` = **季度数**（v0.7.8+）。资产负债表按季度末时点快照拉取（HK/A股 累计季报口径，美股单季+年报），让 2026Q1 利润表能对齐 2026Q1（2026-03-31）资产负债表快照，供 ROA/ROE 精准计算。
- **现金流量表（cashflow）**：`--num` = **季度数**（v0.7.8+）。现金流量表也与利润表同频（HK/A股 累计季报，美股单季+年报），2026Q1 利润表 ↔ 2026Q1 现金流量表（1–3 月累计现金流），供 FCFF/OCF 精准对齐。
- 别传 `--num 100`：API 最多返回 50 条，多了也拿不到，而且 Excel 太宽（50 期 ≈ 100+ 列含 YoY）不适合阅读。推荐区间：利润表/资产负债表/现金流 8~24。

---

## 我（Agent）执行时的调用约定

> 这一节是给编排 Agent 看的 bash 命令，普通用户不需要关心。

```bash
# 利润表（默认 16 季度）
python workspace/skills/futu-financial-report/scripts/generate_financial_excel.py US.BABA

# 资产负债表，20 年
python workspace/skills/futu-financial-report/scripts/generate_financial_excel.py HK.00700 --type balance --num 20

# 现金流量表
python workspace/skills/futu-financial-report/scripts/generate_financial_excel.py SH.600519 --type cashflow

# 指定输出路径
python workspace/skills/futu-financial-report/scripts/generate_financial_excel.py US.BABA -o ./baba_income.xlsx
```

| 参数 | 说明 | 默认 |
|-----|------|-----|
| `stock_code` | 带市场前缀的代码：`US.BABA` / `HK.00700` / `SH.600519` / `SZ.000001` | 必填 |
| `--type` / `-t` | `income` / `balance` / `cashflow` | `income` |
| `--num` / `-n` | 期数（利润表=季度，其他=年度） | `16` |
| `--output` / `-o` | 输出路径 | 自动生成到 `workspace/excels/` |
| `--force` | 强制重新生成，忽略 7 天内的已有产物 | `False`（默认命中缓存直接复用）|

### 缓存复用规则（重要）

为避免重复调用富途 API 拉取相同数据，默认启用**7天缓存复用**：

- 触发条件：**未指定 `--output`** 且 **未加 `--force`**
- 命中逻辑：`workspace/excels/` 下若存在 `{代码}_{报表类型}_*.xlsx` 且**修改时间距今 ≤ 7 天**，直接返回该文件路径并退出（`cache_hit: true`）
- 强制刷新：用户明确说"重新拉一下"、"最新"、"强制更新"、"更新数据"等意图时，加 `--force`
- 命中时的 stdout JSON 会带 `"cache_hit": true, "cache_age_days": <days>`，无 `quarters`/`rows`/`period_range` 字段


**stdout 最终输出**（脚本最后一行 JSON）：

```json
{
  "status": "ok",
  "stock_code": "US.BABA",
  "statement_type": "income",
  "statement_name": "利润表",
  "quarters": 16,
  "excel_path": "/abs/path/to/BABA_income_20260101_120000.xlsx",
  "log_path": "/abs/path/to/.../financial_US_BABA_income_20260101_120000.log",
  "period_range": "2022Q1 - 2025Q4"
}
```

脚本退出码：`0` = 成功，`1` = 失败（错误信息直接打印在 stdout，含修复提示）。

### 我（Agent）必须遵循的规则

1. **用户给中文名/英文名**：先用 `stock-ticker` 查到代码，选主上市地那条，再调脚本。注意 BABA/拼多多等在美港双重上市，默认走用户语境或美股。
2. **只在用户明确需要下载时调用本 skill**。本 skill 拉的是全量财务报表，回答"收入多少"这类单点问题可以先用其他更轻量的工具（如 financial-metrics-query）。
3. **`--num` 的选择要合理**：
   - 三张表现在都按季度维度拉取。用户说"最近N年"，按一年 4 季换算（如 5 年 → `--num 20`），但不要超过 40。
   - 用户没说期数就用默认 16。
4. **拿到 `excel_path` 后**，告诉用户文件路径即可，不要把表格内容全部贴回对话（太长）。一句话总结期数和时间范围，例如"已生成 BABA 利润表，覆盖 2022Q1~2025Q4 共 16 季度"。
5. **遇到 ERROR**：直接把错误信息中的「提示」部分转述给用户，不要让用户去翻日志。
6. **区分本 skill 和 segment-financial-report**：本 skill 走富途 API 拉**整体三表**（利润表/资产负债表/现金流量表的收入、利润、费用等总计指标）；分部业务数据用 segment-financial-report skill（需要先下载财报文件）。

---

## 附录 A：常见问题（遇到问题再看）

### 代码格式错误

| 错误关键词 | 怎么办 |
|-----------|-------|
| "必须包含市场前缀" | 代码前面加 `US.` / `HK.` / `SH.` / `SZ.`；或者直接告诉我公司名我来查 |
| "不支持的市场前缀" | 没有 `CN.` 前缀；沪深两市分别用 `SH.`（上海）/`SZ.`（深圳） |
| "港股/A股代码必须是纯数字" | HK 是 5 位数字（`HK.00700`），SH/SZ 是 6 位数字 |

### 连不上 / 超时

| 错误关键词 | 怎么办 |
|-----------|-------|
| "无法连接 Futu OpenD" | OpenD 没开或没登录。启动 OpenD、登录富途账号后重试；先跑 `python workspace/skills/futuapi/scripts/check_env.py` 诊断 |
| "超时（120秒）" | OpenD 卡了，重启一下 OpenD |
| "无权限" | 富途账号缺该市场行情权限（需要相应权限包） |

### 数据问题

| 错误关键词 | 怎么办 |
|-----------|-------|
| "无财报数据返回" | 可能代码错了 / 前缀和市场不匹配（如把 BABA 写成 HK.BABA）/ 新股无数据；用 stock-ticker 重新确认代码 |
| "无有效数据行" | 该公司报表字段和默认映射不匹配，反馈给开发同学 |
| "文件被占用" | 关掉已经打开的同名 Excel（WPS/Excel 加了文件锁），再跑一次 |

### 依赖问题

| 错误关键词 | 怎么办 |
|-----------|-------|
| "缺少 openpyxl" | `pip install openpyxl>=3.1.0`，或一键装全：`pip install -r workspace/skills/futu-financial-report/scripts/requirements.txt` |
| "No module named 'futu'" / "futu-api 未安装" | 安装 SDK：`pip install "futu-api>=10.4.6408"`；国内用户加 `-i https://pypi.tuna.tsinghua.edu.cn/simple` 加速 |
| "无法连接 OpenD" / "ConnectionRefusedError" | ① 确认 FutuOpenD 已启动（任务栏/菜单栏有图标）② 确认已登录富途账号（OpenD 界面显示账号名，非"未登录"）③ 确认监听端口是 11111（默认），如自定义过需设置 `FUTU_OPEND_HOST/PORT` |
| "futuapi 脚本不存在" | 确认 `workspace/skills/futuapi/` 目录完整，特别是 `scripts/common.py` 和 `scripts/quote/get_financials_statements.py` 存在 |
| SDK 报错 "version mismatch" | OpenD 版本和 futu-api 版本不配套，升级两者到最新版；本 skill 要求两者均 >= 10.4.6408 |
| Python 版本不兼容 | futu-api 支持 Python 3.8~3.12；3.13+ 可能不兼容，建议使用 Python 3.9~3.11 |

> 所有错误信息末尾都会打印日志文件路径（`详细日志: ...log`），里面有完整堆栈和 API 原始返回，开发同学排查问题时看那个。

## 附录 B：不要做这些（反模式）

1. **不要**省略市场前缀（`BABA` ❌ → `US.BABA` ✅）。
2. **不要**把市场前缀搞反（`HK.BABA` ❌，BABA 主上市美股）。
3. **不要**在 Excel 被 WPS/Excel 打开时重跑（Windows 会锁文件）。
4. **不要**把公司中文名直接当股票代码传给脚本（脚本不做名称解析，先调 stock-ticker）。
5. **不要**把 `--num 16` 当承诺——新股/IPO 可能没那么多期，返回少于 16 是正常的。
6. **不要**在 OpenD 没开时反复重试（每次超时 120 秒，先跑 `check_env.py` 确认环境）。
7. **三张表的 `--num` 都是季度数**（v0.7.8+ 起统一）；美股为单季+年报，HK/A 股为累计季报（Q1/H1/Q9/FY）。

## 附录 C：文件结构

```
futu-financial-report/
├── SKILL.md
├── references/
│   └── indicators.md
└── scripts/
    ├── generate_financial_excel.py   # 主脚本（拉数据 + 算比率 + 生成 Excel）
    └── requirements.txt              # openpyxl + futu-api
```
