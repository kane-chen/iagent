├───────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────┤
│ 财报文件下载  │ futu-filing skill：公告发现→分类（FY/Q1-Q4/H1）→下载 PDF（HK/CN）/SEC HTML（US），落盘 workspace/portfolio/<TICKER>/filings/fil_*/ + meta.json                                                              │ 直接复用，不改动                                                             │
├───────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────┤
│ RAG           │ filing_kb_qa：chunk 按 ticker/period 打标签 → ParadeDB BM25 + pgvector 混合检索 → rerank → LLM 读片段回答。输出是自然语言，不是结构化数值                                                                   │ 检索链路可复用；需新增"LLM 结构化提取"环节（输出 JSON：值/单位/置信度/来源   │
│ 从财报提取    │                                                                                                                                                                                                             │ chunk）                                                                      │
├───────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────┤
│ 分部数据      │ segment-financial-report skill：确定性表格解析（BeautifulSoup/camelot，无 LLM），按公司配置别名映射到 10 个标准指标，已能输出 flat JSON（含 period/value/yoy/confidence）                                   │ 直接复用其 JSON 输出                                                         │
├───────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────┤
│ 存储          │ PostgreSQL（iagent 库，pgvector + ParadeDB），ParadeDbChunkRepository 用 JdbcTemplate + ON CONFLICT 幂等 upsert + EAV 标签表                                                                                │ 同一 DataSource，新增普通业务表（btree 索引即可，不需要向量/BM25）           │
└───────────────┴─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┴──────────────────────────────────────────────────────────────────────────────┘

关键判断：三大表数据 futu API 已结构化提供（含 CapEx、OCF、FCF 等），应作为主数据源；RAG 提取定位为补充手段——用于 API 没有的指标（财报附注、非 GAAP 指标、MD&A 披露项）；分部数据走确定性解析。这样准确度最高、LLM 成本最低，符合 KISS。

二、总体架构

用户 ──► BossAgent
│
├─ 采集：financial_data_build(ticker, periods, sources?)
│     └─ FinancialIngestService（Java 编排）
│           ├─ [Python] futu 三大表 JSON ──┐
│           ├─ [Python] segment 分部 JSON ──┤
│           ├─ [Java]   RAG 补充指标提取   ──┴─► 标准模型映射 ──► PostgreSQL upsert
│           └─ 前置检查：filings 已下载 & filing KB 已 build
│
└─ 查询：financial_data_query / financial_segment_query
└─ FinancialQueryService ──► PostgreSQL ──► 分层指标树 × 期间表格

- 采集与查询分离：采集一次入库，查询走 SQL（快、可复现、可审计），不再每次调 LLM。
- Python 侧只负责"取数/解析出 JSON"，映射、差分、入库全部在 Java 侧，与现有 ShellCommandTool（仅允许 python）的架构一致。

三、标准财务模型（核心）

3.1 指标目录树（跨公司统一、分层、可展开）

指标是全公司统一的标准目录；每家公司只是"有/无该指标"的差异，通过映射层适配。示例结构：

利润表 (income, 期间量)
├─ 营业总收入 REVENUE
├─ 营业总成本 TOTAL_OPERATING_COST
│  ├─ 营业成本 COST_OF_REVENUE
│  └─ 营业费用 OPERATING_EXPENSES
│     ├─ 销售费用 SELLING_EXPENSE
│     ├─ 管理费用 GA_EXPENSE
│     └─ 研发费用 RD_EXPENSE
├─ 毛利 GROSS_PROFIT (派生)
├─ 营业利润 OPERATING_INCOME
├─ 财务费用净额 NET_FINANCE_COST
├─ 税前利润 PRETAX_INCOME
├─ 所得税 INCOME_TAX
└─ 净利润 NET_INCOME
└─ 归母净利润 NET_INCOME_PARENT

资产负债表 (balance, 时点数)
├─ 资产总计 TOTAL_ASSETS
│  ├─ 流动资产合计 TOTAL_CURRENT_ASSETS
│  │  ├─ 货币资金 CASH_AND_EQUIVALENTS
│  │  ├─ 短期投资 SHORT_TERM_INVESTMENTS
│  │  ├─ 应收账款 ACCOUNTS_RECEIVABLE
│  │  └─ 存货 INVENTORY
│  └─ 非流动资产合计 …（固定资产 PP&E / 无形资产 INTANGIBLES / 商誉 GOODWILL）
├─ 负债总计 TOTAL_LIABILITIES（短期借款 / 应付账款 / 长期借款 / 应付债券…）
└─ 股东权益合计 TOTAL_EQUITY（归母权益 / 股本 / 资本公积 / 留存收益…）

现金流量表 (cashflow, 期间量)
├─ 经营活动现金流净额 OPERATING_CF
│  ├─ 折旧摊销 DEPRECIATION_AMORTIZATION
│  └─ 营运资本变动（应收/存货/应付变动）
├─ 投资活动现金流净额 INVESTING_CF
│  └─ 资本开支 CAPEX
├─ 筹资活动现金流净额 FINANCING_CF
├─ 期末现金余额 CASH_END
└─ 自由现金流 FREE_CASH_FLOW (派生 = OCF − 资本开支；资本开支优先取 RAG 提取的 Non-GAAP 经调整口径 NON_GAAP_CAPEX，缺失回退 GAAP CAPEX)

派生比率（查询时计算或入库时计算）
└─ 毛利率 / 营业利润率 / 净利率 / ROE / ROA / 各费用率 / YoY / QoQ

每个指标属性：metric_code（稳定英文编码）、parent_code、statement(income/balance/cashflow/derived)、level、sort_order、中英文名、value_type（FLOW 期间量 / STOCK 时点数 / RATIO 比率）、is_derived、单位。

3.2 映射层（适配各公司）

- futu 字段映射：按市场（US/HK/CN，futu field_id 市场内通用）维护映射表，从现有 US_FIELDS/HK_FIELDS/A_SHARE_FIELDS 一次性迁移为配置。例：8046/5071+5073/3043 → CAPEX。
- 财报 RAG 提取映射：指标别名表（复用 FinancialTermDictionary 同义词组思路，如 资本开支/资本支出/capex/Capital expenditures）。
- 分部指标：复用 segment skill 的 metric_dict.json 10 个标准 code，与主指标目录对齐。
- 某公司无对应字段 → 值为 null（显示 -），不反推、不硬凑。

3.3 期间口径

- 期间标签统一 YYYYQn / FYyyyy（复用现有 FiscalPeriod/PeriodParser）。
- HK/A 股累计数（Q1/H1/9M/FY）：同时保留原始累计值，并差分出单季度值（差分逻辑复用 segment skill derive_ytd_quarters 思路）；美股直接是单季。
- 资产负债表为时点数，不做差分。
- 每条数据标注 period_type（SINGLE_Q / CUMULATIVE / FY）。

3.4 分部数据模型

分部是公司特定维度（腾讯的 VAS/广告 vs 阿里的云智能/淘天），不强行统一：
- fin_segment：ticker + segment_code + parent_code + level + 名称（来自 segment skill 的公司配置）。
- fin_segment_value：ticker + period + segment_code + metric_code（REVENUE/OPERATING_INCOME/ADJUSTED_EBITA…）+ value/yoy。

四、存储设计（PostgreSQL，复用 iagent 库）

新增 src/main/resources/db/financial-schema.sql，普通 btree 表（无需向量索引）：

-- 公司
CREATE TABLE fin_company (
ticker       VARCHAR(16) PRIMARY KEY,   -- 00700 / BABA
market       VARCHAR(8)  NOT NULL,      -- HK/US/CN
name         VARCHAR(128),
currency     VARCHAR(8),                -- HKD/RMB/USD
fy_end_month INT DEFAULT 12,
updated_at   TIMESTAMP DEFAULT now()
);

-- 指标值（三大表 + 派生 + RAG补充）
CREATE TABLE fin_metric_value (
id            BIGSERIAL PRIMARY KEY,
ticker        VARCHAR(16) NOT NULL,
fiscal_period VARCHAR(16) NOT NULL,     -- 2026Q2 / FY2025
period_type   VARCHAR(16) NOT NULL,     -- SINGLE_Q/CUMULATIVE/FY
metric_code   VARCHAR(64) NOT NULL,
value         NUMERIC(24,6),            -- 统一百万单位
yoy           NUMERIC(12,4),
qoq           NUMERIC(12,4),
currency      VARCHAR(8),
source        VARCHAR(16) NOT NULL,     -- FUTU_API / RAG / DERIVED
confidence    INT,                      -- RAG 提取置信度
document_id   VARCHAR(128),             -- 溯源：财报目录
chunk_id      VARCHAR(64),              -- 溯源：RAG chunk
updated_at    TIMESTAMP DEFAULT now(),
UNIQUE(ticker, fiscal_period, metric_code, source)
);
CREATE INDEX fin_mv_query_idx ON fin_metric_value (ticker, metric_code, fiscal_period);

-- 分部目录 / 分部值（结构同上，略）
CREATE TABLE fin_segment (...);
CREATE TABLE fin_segment_value (... UNIQUE(ticker, fiscal_period, segment_code, metric_code));

-- 采集批次（可选，便于排查）
CREATE TABLE fin_ingest_batch (id, ticker, source, periods, status, report, created_at);

Repository 仿 ParadeDbChunkRepository：JdbcTemplate、ON CONFLICT DO UPDATE 幂等、参数绑定、批量 UNNEST。指标目录树本身建议放版本化配置文件（见澄清问题 3），不建表。

五、采集流水线（FinancialIngestService）

1. ticker 解析：复用 stock-ticker skill。
2. 三大表：新增 Python 入口 futu-financial-report/scripts/fetch_statements_json.py（从现有 1900 行脚本中抽离取数逻辑，去掉 Excel 生成），输出到 workspace/temp/：{ticker, market, currency, unit:million, reports:[{period, periodType, fiscalYear, items:[{fieldId, value, yoy, qoq}]}]}。
3. 分部数据：调用现有 extract_segments.py --output <json>（已有 flat JSON）。
4. RAG 补充指标：新增 Java 组件 RagMetricExtractor——对每个待补指标，用现有 retrieve 链路（tag 过滤 ticker+period、同义词扩展、混合检索、rerank 取 top-K chunk），再调 Chatter 要求按 JSON schema 输出 {metric_code, value, unit, period, confidence, chunk_id,
   evidence}；解析失败/低置信度则不入库并记录。前提：filings 已下载（futu-filing）且 filing_kb_build 已建库；采集前自动检查并提示/触发。
5. 映射入库：futu fieldId → 标准 code（市场映射表）；HK/A 累计差分单季；派生指标计算（FCF、比率）；upsert。

六、查询服务（agent 工具）

在 tools/financial/ 新增 FinancialDataTool，注册到 BossAgent：

- financial_data_query(ticker, metrics?, periods?, statement?, expand?)：指标名支持中/英/别名（别名解析复用术语词典）；期间支持"最新/近N季/去年同期"（复用 FilingPeriodNormalize 同类逻辑）；返回分层缩进的 Markdown 表格（指标树行 × 期间列，可按 expand 控制层级深度），缺失显示 -，RAG 来源标注 * 与置信度。
- financial_segment_query(ticker, periods?)：分部 × 指标 × 期间表格。
- financial_data_build(ticker, periods?, sources?)：触发采集。

查询走纯 SQL，毫秒级；LLM 只在采集时用于补充指标提取。

七、代码落点

┌───────────────────────────────────────────────────────────────────────┬──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                 位置                                  │                                                                                                                 内容                                                                                                                 │
├───────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ src/main/java/io/invest/iagent/financial/                             │ 新包：config/FinancialProperties、model/（MetricDef/MetricValue/Segment…）、repository/（JdbcTemplate 实现）、ingest/（IngestService、Futu/Segment JSON                                                                              │
│                                                                       │ 解析、RagMetricExtractor、MetricMapper、累计差分）、query/（QueryService、别名解析）                                                                                                                                                 │
├───────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ src/main/java/io/invest/iagent/tools/financial/FinancialDataTool.java │ 3 个 @Tool                                                                                                                                                                                                                           │
├───────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ src/main/resources/                                                   │ db/financial-schema.sql、financial/metric-catalog.yml、financial/futu-field-mapping.yml                                                                                                                                              │
├───────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ workspace/skills/futu-financial-report/scripts/                       │ 新增 fetch_statements_json.py（复用现有取数代码）                                                                                                                                                                                    │
├───────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ 配置                                                                  │ app.financial.*（默认期数、启用开关 @ConditionalOnProperty）                                                                                                                                                                         │
└───────────────────────────────────────────────────────────────────────┴──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘

  ---

在开始详细设计/实现前，有 4 个关键点需要您确认：

● User answered Claude's questions:
⎿  · RAG 从财报提取指标的范围如何定位？（futu API 三大表已含 CapEx、OCF、FCF、折旧摊销等） → 仅补 API 缺失指标（推荐）
· 港股/A股的累计口径数据（Q1/H1/9M/FY）如何存储？ → 累计值+单季值都存（推荐）
· 标准指标目录树（分层指标定义、futu 字段映射）如何维护？ → 代码库 YAML 配置文件（推荐）
· 财务数据采集如何触发？ → Agent 按需采集+查询自动补采（推荐）

Thought for 15s (ctrl+o to expand)

● 4 个决策点均已确认（全部采纳推荐方案）。据此将设计方案定稿如下：

设计定稿（基于澄清结果）

1. RAG 补充提取：仅补 API 缺失指标

- 三大表全部字段以 futu API 为准；RAG 结构化提取只针对配置文件中声明的"补充指标清单"financial/rag-extra-metrics.yml，初始建议包含：
    - 股份支付费用（SBC，美股常见、API 无）
    - 非 GAAP 指标：Adjusted EBITDA、Non-GAAP 净利润（财报披露但不在三大表）
    - 现金流附注细项：股利支付、股票回购金额（融资活动细项）
    - 其余指标后续按需在 YAML 中增补（每项配中/英文别名、所属报表、单位）
- 冲突处理：fin_metric_value 的唯一键含 source，API 值与 RAG 值可共存；查询默认取 FUTU_API，RAG 值标注来源与置信度，可交叉核对。
- 提取前提：采集前检查 filings 是否已下载（futu-filing）、filing KB 是否已 build（`filing_kb_build``），缺失则 agent 先补齐再提取。

2. 期间口径：累计值 + 单季值都存

- fin_metric_value.period_type 取值：CUMULATIVE（HK/A股财报原始累计：Q1/H1/9M/FY）、SINGLE_Q（差分或美股原生单季）、FY。
- 差分规则复用 segment skill 的 derive_ytd_quarters 思路（Q2 = H1 − Q1，Q3 = 9M − H1，Q4 = FY − 9M），在 Java 侧入库时计算；资产负债表（时点数）不做差分。
- 查询默认返回 单季 + FY（美股原生单季；HK/A 差分单季），累计值保留可溯源。

3. 模型维护：YAML 配置文件（随 git 版本管理）

src/main/resources/financial/
├── metric-catalog.yml        # 标准指标目录树：code/parent/statement/level/中文名/value_type/单位/是否派生
├── futu-field-mapping.yml    # 按市场 us/hk/cn 的 futu fieldId → metric_code（从现有脚本迁移）
└── rag-extra-metrics.yml     # RAG 补充指标清单：metric_code + 中英文别名 + 提取提示
启动时加载为 Spring Bean；新增指标 = 改配置 + 发版，有评审轨迹。分部指标沿用 segment skill 的 metric_dict.json，入库时对齐到同一套 metric_code。

4. 采集触发：Agent 按需 + 查询自动补采

- 工具 financial_data_build(ticker, periods?, sources?)：显式采集（futu 三大表 → 分部 → RAG 补充）。
- 工具 financial_data_query(...) / financial_segment_query(...)：查询前先查覆盖率（该 ticker × 期间是否有数据），缺失时在返回中提示 missing_periods，由 agent 自动调 build 补采后重查；用户明确要"最新数据"时带 force_refresh。

实施顺序（建议）

1. DB + 模型层：financial-schema.sql、3 个 YAML、model/、repository（JdbcTemplate upsert）
2. 三大表采集：Python fetch_statements_json.py（剥离 Excel 逻辑）→ Java 映射/差分/入库
3. 查询工具：financial_data_query（指标树 × 期间表格）+ 自动补采闭环
4. 分部数据：接 segment skill JSON 入 fin_segment* 表 + financial_segment_query
5. RAG 补充提取：RagMetricExtractor（复用 retrieve 链路 + Chatter JSON 输出）+ 补充指标清单

方案已完整。如果您确认无误，我可以按此顺序开始实现（建议从第 1 步 DB 与模型层做起）；如需调整任何部分（如初始补充指标清单、指标树细节），请指出。

✻ Cooked for 12m 52s · done 9:18

❯ 开始实现

Thought for 13s (ctrl+o to expand)

● 开始实现。先读取需要参照的关键文件，确保代码风格与现有模式一致。
