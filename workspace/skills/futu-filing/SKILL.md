---
name: futu-filing
description: 通过 futunn.com 的公告 API 抓取上市公司业务公告（含年报/中期/季度业绩公告）并下载到 workspace/portfolio/<TICKER>/filings/。支持美股 / 港股 / A 股三个市场，走 Futu 网页登录态绕过反爬 WAF。触发词：Futu 公告下载、跨市场财报抓取、futunn 抓取
---

# Futu 公告下载 Skill

**跨市场统一入口 + 浏览器登录态复用 + 配置驱动的分市场下载策略**

## 应用场景

`futunn.com` 汇总了美股、港股、A 股上市公司的**业务公告**（业绩公告、通函、股东大会等），
接口 `https://www.futunn.com/quote-api/quote-v2/get-news-list` 返回带附件链接的公告列表。
但接口带 WAF + 请求签名（`quote-token` 头由客户端 axios 拦截器算出），直接调会拿到
`{code:500,message:"Params Error"}`。本 skill：

1. 用 Playwright 弹浏览器让用户手动登录一次 → cookies 落地本地
2. Python 脚本复刻 `quote-token` 签名（HmacSHA512 + SHA256 双层截断）
3. 按 `config/companies.json` 里配置的市场差异做分市场下载：HK/CN 直取 PDF；US 走 SEC accession folder

## 目录结构

```
workspace/skills/futu-filing/
├── SKILL.md
├── config/
│   └── companies.json          # 公司名单 + 市场差异 + 过滤规则
├── scripts/
│   ├── login.py                # Playwright 弹窗登录 → cookies.json
│   ├── download_announcement.py# 主脚本：拉列表 → 分类 → 下载 → 生成 meta.json
│   ├── companies_registry.py   # 公司注册表：命中 companies.json 直接返回；未命中时调 predict 补齐并写回
│   └── requirements.txt
├── samples/                    # 调试样本落地（.gitignore）
├── cookies.json                # login 后落地（.gitignore）
└── .gitignore
```

## 首次准备

```bash
# 注意：harness 的 ShellCommandTool 只放行 `python`/`python3`，不允许直接调 `pip`。
# 一律用 `python -m pip ...` 走 python 入口，避免 SecurityError: Command 'pip' is not in the allowed whitelist。
python -m pip install -r workspace/skills/futu-filing/scripts/requirements.txt
python -m playwright install chromium

# 首次登录 —— 弹浏览器让你手动扫码/账密登录；成功后 cookies.json 落地
python workspace/skills/futu-filing/scripts/login.py --interactive
```

## Skill 编排里如何安全地调用 login.py（LLM 别踩坑）

**核心规则**：`login.py` 在 LLM harness 里**只能加 `--skip-if-valid` 调用**。

原因：Futu 的 WAF 要求真实浏览器指纹里出现登录 cookie（`uid` + `web_sig`）才承认会话。
无头/自动化环境拿不到这两个 cookie。手动登录必须在**有 TTY + 有图形界面**的机器上做一次。
`--skip-if-valid` 就是给这种反复触发的场景准备的幂等入口 —— cookies 齐了就 no-op。

推荐的编排（Java 侧 `execute_shell_command` 里）：

```bash
# 第 1 步：幂等地检查登录态。已登录 → 立刻 exit 0；未登录 → exit 2
python workspace/skills/futu-filing/scripts/login.py --skip-if-valid

# 如果上面 exit 非 0，说明维护者需要在本机手动跑：
#   python workspace/skills/futu-filing/scripts/login.py --interactive
# 此时不要在 LLM 里继续调用 —— 会卡浏览器窗口/等回车。改抛错让人处理。

# 第 2 步：拿到 cookies 后执行下载
python workspace/skills/futu-filing/scripts/download_announcement.py --ticker 00700 --workspace ./workspace
```

**退出码约定**（`login.py`）：
- `0` = cookies 可用（`--skip-if-valid` 命中，或 `--interactive` 成功后拿到 session）
- `1` = 未拿到任何 cookie（网络/浏览器故障）
- `2` = 等待超时未拿到 session cookie —— 需要人工介入

**退出码约定**（`download_announcement.py`）：
- `0` = 全部成功（errors 计数为 0）
- `1` = 有至少一个 error（但其他 ticker/filing 正常处理了，看 stdout 的 summary JSON 详情）
- `2` = 缺少 `requests` 依赖
- `3` = 配置文件或 cookies.json 缺失
- `4` = cookies 刷新失败（WAF 会话过期且自动 relogin 也失败，需手动 `--interactive`）

## 用法

```bash
# 一键下载（用 config/companies.json 里预置的 ticker）
python workspace/skills/futu-filing/scripts/download_announcement.py \
    --ticker 00700 --workspace ./workspace

# 批量：多个 ticker 逗号分隔
python workspace/skills/futu-filing/scripts/download_announcement.py \
    --ticker 00700,BABA,PDD --workspace ./workspace

# 起止财年（闭区间）
python workspace/skills/futu-filing/scripts/download_announcement.py \
    --ticker 00700 --workspace ./workspace \
    --fiscal-year-start 2023 --fiscal-year-end 2025

# 或者用离散集合
python workspace/skills/futu-filing/scripts/download_announcement.py \
    --ticker 00700 --workspace ./workspace --fiscal-years 2024,2025

# 只下年报和一季报
python workspace/skills/futu-filing/scripts/download_announcement.py \
    --ticker 00700 --workspace ./workspace --filing-types FY,Q1

# 覆盖已存在的目录（默认跳过 meta.json 已存在的）
python workspace/skills/futu-filing/scripts/download_announcement.py \
    --ticker BABA --workspace ./workspace --overwrite
```

## `config/companies.json` 说明

`companies.json` 里的 `companies[*]` 是**权威数据源**，但**不再是必需前置** ——
如果传入的 ticker 不在里面，`companies_registry.py` 会自动调 Futu 的
`https://www.futunn.com/search-stock/predict` 拉候选、按 "美股 > 港股 > A 股 / 剔除
ADR·ETF·杠杆" 选一只、组装成 entry 并 **写回** `companies.json`，下次直接命中。

新 entry 的字段规则（由 predict 接口的 `marketTypeName` 决定）：

| 字段 | 规则 |
|---|---|
| `ticker` | `stockSymbol` |
| `stockId` | `stockId` |
| `displayName` | `stockName` |
| `market` | `US` / `HK` / `CN`（其它市场如 AU/UK 会被跳过） |
| `supportFileTypes` | US 且是中概股（symbol 含 `BABA/PDD/JD/BIDU/NIO/LI/XPEV/-ADR/-ADS/.US`）→ `["20-F","6-K"]`；US 非中概股 → `["10-K","10-Q"]`；HK/CN → `[]` |
| `titleKeyWords` | HK → `["季度报告","年度报告","业绩公告","财报公告"]`；CN → `["季度报告","年度报告"]`；US → `[]` |
| `filingSuffixNames` / `supportPeriodTypes` | 默认 `[]`（用户按需手工加） |

想手动查一下 registry 会怎么解析某个 ticker（或 debug predict 接口）：

```bash
# 命中配置 → 直接返回；未命中 → 联网查 & 写回 & 返回
python workspace/skills/futu-filing/scripts/companies_registry.py PDD

# 仅查配置不联网
python workspace/skills/futu-filing/scripts/companies_registry.py PDD --no-network

# 打印 predict 接口全部候选（不写回）
python workspace/skills/futu-filing/scripts/companies_registry.py PDD --print-quotes
```

### `companies[*]`

每条股票配置：

| 字段 | 说明 |
|---|---|
| `ticker` | 股票代码（大写；HK 用 5 位如 `00700`） |
| `market` | `HK` / `US` / `CN` |
| `stockId` | Futu 内部数字 id（可从 `https://www.futunn.com/stock/<TICKER>-<MARKET>` 的 HTML 里 grep `stockId`） |
| `cik` | 美股 SEC CIK（暂未主动用到，留给下游 SEC 补充查询） |
| `displayName` | 展示名（日志用） |
| `supportFileTypes` | **公告标题**必须包含的关键字集合（OR）。空数组=不过滤。美股常用 `["10-K","10-Q"]` 或 `["6-K","20-F"]` |
| `titleKeyWords` | **公告标题**必须包含的关键字集合（OR）。空数组=不过滤。港股/A 股常用 `["年报","中期","业绩","季"]` |
| `filingSuffixNames` | 美股 SEC 目录内**文件名**后缀白名单。空数组时按下面 b2/b3 默认规则筛 |
| `supportPeriodTypes` | 只保留 `classify()` 归类到这些周期的公告。可选值 `Q1`/`Q2`/`Q3`/`Q4`/`H1`/`H2`/`FY`。空数组=不过滤。**注意**：H1 中期报同时归 `H1` 和 `Q2` —— 想只留 H1 一份，配 `["H1", "FY"]` 就行；配 `["H1", "Q2", "FY"]` 则两份都留 |

`supportFileTypes` 和 `titleKeyWords` 是**标题级**过滤（在 `classify()` 之前跑），两者之间是 AND 关系（都命中才通过）。都为空 → 不过滤。

`supportPeriodTypes` 是**周期级**过滤（在 `classify()` 之后跑，看归类结果），与前面两个是 AND 关系。同样为空不检查。

### SEC 文件过滤规则（`downloadStyle=sec_folder`）

按优先级：
- **b1** `filingSuffixNames` 非空 → 文件名以任一后缀（不区分大小写）结尾即接受
- **b2** `filingSuffixNames` 为空 + 公告标题含 `10-K` / `10-Q` → 只接受 `<ticker>_<yyyymmdd>.htm`
- **b3** `filingSuffixNames` 为空 + 公告标题含 `6-K` / `20-F` → 只接受 `*20f.htm` 或文件名匹配 `ex99[-_]?1`（覆盖 `ex99-1.htm` 和 `d<accession>dex991.htm` 两种命名）

保留 `-index.htm[l]` / `-index-headers.html` 作为溯源。

### `marketProfiles[*]`

每个市场：

| 字段 | 说明 |
|---|---|
| `dirPrefix` | 目录前缀，如 `hk` / `us` / `cn`（用于 HK/CN 的 `fil_<prefix>_<ticker>_<year>_<form>` 命名） |
| `marketType` | Futu 内部市场号：1=HK, 2=US, 3=CN |
| `downloadStyle` | `single`（HK/CN 单 PDF）或 `sec_folder`（US 多文件 accession） |
| `urlPrefix` | US 用；下载 URL 必须以此前缀开头（`https://www.sec.gov/Archives/edgar/data`） |
| `primaryPdfPatterns` / `fallbackPdfPatterns` | 详情页 SSR HTML 里匹配附件 URL 的正则；每条模式不跨行、不跨 CSS |

## 落地路径与文件结构

所有文件下载到 `workspace/portfolio/<TICKER>/filings/` 下，每个公告一个独立子目录。

### 港股 / A 股（单 PDF 模式）

目录命名：`fil_<dirPrefix>_<ticker>_<fiscalYear>_<formType>/`

```
workspace/portfolio/
└── 00700/
    └── filings/
        ├── fil_hk_00700_2024_FY/
        │   ├── 11106352-0.PDF          # 公告原始 PDF
        │   └── meta.json               # 元数据
        ├── fil_hk_00700_2024_H1/
        │   ├── 10892341-0.PDF
        │   └── meta.json
        └── ...
```

### 美股（SEC accession 文件夹模式）

目录命名：`fil_<accession-number-hyphenated>/`（从 SEC EDGAR URL 解析，如 `0001104659-25-049400`）

```
workspace/portfolio/
└── AAPL/
    └── filings/
        └── fil_0000320193-23-000064/
            ├── 0000320193-23-000064-index-headers.html  # SEC accession 索引页（溯源用）
            ├── 0000320193-23-000064-index.html          # SEC accession 索引页
            ├── aapl-20230401.htm                        # 主报告文件（10-Q 正文）
            └── meta.json                                # 元数据
└── BABA/
    └── filings/
        └── fil_0001570723-24-000023/
            ├── 0001570723-24-000023-index-headers.html
            ├── 0001570723-24-000023-index.html
            ├── baba-20240331-ex99-1.htm                 # 6-K 附件：业绩发布稿
            └── meta.json
```

**注意**：美股 H1 中期报通过 6-K 的 ex99-1 附件落地；10-K/20-F 对应 FY，10-Q 对应 Q1/Q2/Q3。

## meta.json 格式

每个公告目录下都有一个 `meta.json`，记录该份文件的元数据供下游解析。

### 字段说明

| 字段 | 类型 | 说明 |
|---|---|---|
| `documentId` | string | 文档唯一 ID，与目录名一致（`fil_hk_00700_2024_FY` 或 `fil_0000320193-23-000064`） |
| `announcementId` | string | Futu newsId（公告列表接口里的 ID） |
| `ticker` | string | 股票代码（大写） |
| `market` | string | 市场：`HK` / `US` / `CN` |
| `formType` | string | 报告类型：`FY`（年报）/ `H1`（中报）/ `Q1`~`Q4`（季报）。注意 H1 公告会生成两条 meta（H1 + Q2），指向同一目录 |
| `fiscalYear` | number | 财年（整数，如 2024） |
| `reportDate` | string | 公告发布日期（`YYYY-MM-DD`） |
| `filingDate` | string | 同 `reportDate`（保留字段，兼容旧版本） |
| `source` | string | 数据来源，固定为 `"futunn"` |
| `sourceNoticeUrl` | string | Futu 公告详情页 URL |
| `downloadTimestamp` | string | 下载时间（UTC ISO8601，带 Z 后缀，如 `"2026-07-06T03:30:42.480226Z"`） |
| `primaryFile` | object | 主文件信息（见下） |
| `files` | string[] | 该目录下所有已下载文件名（basename，不含路径） |

`primaryFile` 对象：

| 字段 | 类型 | 说明 |
|---|---|---|
| `name` | string | 主文件名（如 `11106352-0.PDF` 或 `aapl-20230401.htm`） |
| `sha256` | string | 主文件 SHA-256 哈希（hex 小写），用于去重和校验 |
| `size` | number | 文件大小（字节） |
| `contentType` | string | MIME 类型：`"application/pdf"`（港股/A股 PDF）或 `"text/html"`（美股 SEC htm） |
| `sourceUrl` | string | 文件的原始下载 URL |

### meta.json 示例：港股年报（PDF）

```json
{
  "documentId": "fil_hk_00700_2023_FY",
  "announcementId": "151789515",
  "ticker": "00700",
  "market": "HK",
  "formType": "FY",
  "fiscalYear": 2023,
  "reportDate": "2024-03-20",
  "filingDate": "2024-03-20",
  "source": "futunn",
  "sourceNoticeUrl": "https://news.futunn.com/notice/151789515",
  "downloadTimestamp": "2026-07-06T03:30:42.480226Z",
  "primaryFile": {
    "name": "11106352-0.PDF",
    "sha256": "885fe7dc29a2...（64位hex）",
    "size": 1054172,
    "contentType": "application/pdf",
    "sourceUrl": "https://newsfile.futunn.com/announcement/..."
  },
  "files": ["11106352-0.PDF"]
}
```

### meta.json 示例：美股 10-Q（SEC HTML）

```json
{
  "documentId": "fil_0000320193-23-000064",
  "announcementId": "300987123",
  "ticker": "AAPL",
  "market": "US",
  "formType": "Q1",
  "fiscalYear": 2023,
  "reportDate": "2023-05-05",
  "filingDate": "2023-05-05",
  "source": "futunn",
  "sourceNoticeUrl": "https://news.futunn.com/notice/300987123",
  "downloadTimestamp": "2026-07-06T04:12:18.123456Z",
  "primaryFile": {
    "name": "aapl-20230401.htm",
    "sha256": "ab12cd34ef56...",
    "size": 2457600,
    "contentType": "text/html",
    "sourceUrl": "https://www.sec.gov/Archives/edgar/data/320193/000032019323000064/aapl-20230401.htm"
  },
  "files": [
    "0000320193-23-000064-index-headers.html",
    "0000320193-23-000064-index.html",
    "aapl-20230401.htm"
  ]
}
```

## 运行时输出（stdout）

脚本运行结束后会在 stdout 最后打印一个 JSON 摘要（方便 Java 上游用 `--output-summary` 落盘解析）：

```json
{
  "tickers": [
    {
      "ticker": "00700",
      "market": "HK",
      "stockId": "600015",
      "downloaded": [
        {
          "documentId": "fil_hk_00700_2024_FY",
          "formType": "FY",
          "fiscalYear": 2024,
          "primaryFile": "/abs/path/11106352-0.PDF",
          "files": ["/abs/path/11106352-0.PDF"],
          "url": "https://newsfile.futunn.com/...",
          "title": "腾讯控股截至2024年12月31日止年度业绩公告"
        }
      ],
      "skipped": [
        {"newsId": "123", "title": "董事会召开日期", "reason": "not a financial filing"},
        {"newsId": "456", "title": "...", "reason": "filter did not match"}
      ],
      "errors": [
        {"newsId": "789", "title": "...", "reason": "no PDF url in notice page"}
      ],
      "counts": {"downloaded": 4, "skipped": 56, "errors": 1}
    }
  ],
  "totals": {"downloaded": 4, "skipped": 56, "errors": 1}
}
```

- `downloaded`：成功落地的文件列表（含绝对路径）
- `skipped`：被过滤掉的公告（非财报、标题过滤不匹配、类型/年份不在白名单等）及其原因
- `errors`：处理过程中出错的公告（网络问题、解析失败、内容非财报等）及其原因
- `counts`/`totals`：汇总计数；`totals.errors > 0` 时脚本退出码为 1

## 运行稳定性说明

- **单条公告失败不会中断整体任务**：解析某个公告出错、某个 PDF 下载失败时，会记入 `errors[]` 并继续处理下一条
- **多 ticker 之间互不影响**：一个 ticker 的所有公告都失败也不影响其他 ticker
- **网络瞬时错误自动重试**：对 ConnectionError/Timeout/ChunkedEncodingError/429/5xx 等瞬时错误，使用指数退避重试最多 3 次（1.5s → 2.25s → 3.4s），覆盖：
  - 公告列表 API（`get-news-list`）
  - 公告详情页
  - PDF/HTM 文件下载（含流式下载中断重试，先写 `.part` 临时文件再原子替换）
  - SEC index.json 拉取
  - SEC 单文件下载
  - companies_registry 的 predict 查询
- **WAF 会话过期自动恢复**：遇到 HTTP 439 或 `code=-12009` 时，自动调用 `login.py` 无头刷新 cookies，刷新成功后重试一次
- **文件原子写入**：文件先下载到 `.part` 临时文件，下载完整后才替换为目标文件名，避免半成品文件被下游误读
- **已存在文件跳过**：默认跳过已有 `meta.json` 的目录（加 `--overwrite` 可强制重下）

## Cookie 过期处理

`cookies.json` 里有 `expires` 时间戳；脚本调用前会检查未过期；过期只警告，接口继续调（部分 cookie 是 WAF 挑战性，未过期即可用）。如果拿到 `code=500 Params Error`、`HTTP 439` 或 `code=-12009` 且自动刷新失败，重跑 `login.py` 刷新 cookies：

```bash
python workspace/skills/futu-filing/scripts/login.py --interactive
```

## 请求节流

严格控制在 30 秒 30 次以内：
- 公告详情页每篇后 `time.sleep(sleepBetweenDocs=0.5)`
- SEC accession 内文件间 `time.sleep(sleepBetweenFilesInAccession=0.3)`
- 多 ticker 之间 `time.sleep(1.0)`
- SEC EDGAR 有 fair access policy（10 req/s），脚本的节流速度远低于此，不会被限流

---

## 常见问题（FAQ / Troubleshooting）

### 登录 / Cookie 相关

**Q: 报错 `{code:500, message:"Params Error"}`**
A: WAF 签名或 cookies 失效。先跑 `python workspace/skills/futu-filing/scripts/login.py --skip-if-valid` 检查 cookies 状态；如果 exit 2，手动跑 `--interactive` 重新扫码登录。
常见原因：太久没用、cookie 过期、异地登录被挤下线。

**Q: 报错 `HTTP 439` 或 `code=-12009`**
A: Futu WAF 判断会话过期（与 500 Params Error 本质同类问题）。脚本通常会自动调 login.py 尝试无头刷新；如果自动刷新失败（exit 4），需要手动 `--interactive` 登录。

**Q: `login.py --interactive` 弹不出浏览器窗口**
A: 确认已安装 Chromium：`python -m playwright install chromium`。
在无 GUI 的 Linux 服务器上无法 interactive 登录 —— 需要在有图形界面的机器上登录后，把 cookies.json 拷贝过去。

**Q: cookies.json 有 uid/web_sig 字段但请求还是被拒**
A: 核心字段是 `csrfToken`（用来生成 `futu-x-csrf-token` 和 `quote-token`）。login.py 在无头模式下拿到 csrfToken 就能工作；uid/web_sig 是增强字段，缺失不致命。如果三者都缺，重跑 `--interactive`。

**Q: Windows 下出现 `UnicodeDecodeError` 从 login.py subprocess 报出**
A: 代码已强制 UTF-8 解码 + replace 容错；如果仍看到乱码，可以设置环境变量 `PYTHONIOENCODING=utf-8` 后再跑。

### 下载 / 网络相关

**Q: 报错"无 PDF url in notice page"**
A: 详情页里没匹配到附件链接。可能原因：
- 这篇公告本身没有附件（纯文字公告）
- Futu 改了详情页的 HTML 结构，正则匹配失效 —— 反馈给开发同学更新 `primaryPdfPatterns`/`fallbackPdfPatterns`
- WAF 拦截了详情页，返回了空壳 HTML（没有正确 warmup session）—— 重新 login 再试

**Q: 报错"URL prefix mismatch"（US 市场）**
A: 美股公告的附件 URL 不是 SEC EDGAR 域名，可能是直接指向公司 IR 网站或其他 CDN，被 `urlPrefix` 白名单拒绝。如果这是新的合法数据源，可以在 companies.json 对应的 marketProfile 里更新 `urlPrefix`。

**Q: SEC 文件下载出现 HTTP 429 Too Many Requests**
A: SEC EDGAR 限流（10 req/s）。脚本自带 0.3s 文件间节流，一般不会触发；如果是短时间多次重跑同一批，可以等 1~2 分钟后重试。自动重试逻辑会在 429 时退避重试。

**Q: 网络超时 / ConnectionError 导致某个文件失败**
A: 脚本对网络瞬时错误自动重试 3 次；如果重试后仍失败，该文件记入 `errors[]`，其他文件继续下载。再跑一次脚本即可（默认跳过已下载的，不会重复劳动）。

**Q: 下载了 0 个文件，但 counts 里有很多 skipped**
A: 过滤规则太严或 ticker 配错了。排查步骤：
1. 检查 ticker 是否正确（HK 是 5 位数字如 `00700`，不是 `700`；US 是 ticker 如 `BABA`）
2. 检查 `supportFileTypes` / `titleKeyWords` 是否过严 —— 可以临时设空数组 `[]` 测试
3. 检查 `--fiscal-year-start/end` 或 `--filing-types` 是否把所有公告都过滤掉了
4. 加 `--overwrite` 排除"已存在被跳过"的情况

### 文件 / 内容相关

**Q: "primary content is not a financial report" 是什么？**
A: 美股 6-K 标题是通用的"外国私营发行人报告"，里面可能是业绩公告也可能是董事变更/章程修订/股东大会通知。脚本下载 ex99-1 后会读文件内容用关键词二次判定，非业绩的就清理掉文件、记为 skipped。这是**正常的过滤行为**，不是错误。

**Q: "SEC folder had no primary file matching filters" 是什么？**
A: SEC accession 目录里只有 index 文件、没有匹配后缀规则的主文件。通常说明这份 6-K 没有 ex99-1 附件（非业绩类），属于正常过滤。

**Q: 文件下载了但打不开 / 文件大小 0 字节**
A: 极少数情况下网络中断可能留下不完整文件。加 `--overwrite` 重新下载该 ticker，脚本会覆盖坏文件。正常情况下脚本用 `.part` 临时文件+原子替换来避免半成品。

**Q: Excel / WPS 打开报错说文件被占用**
A: （这是 futu-financial-report 的问题，不是本 skill）关掉打开的同名 Excel 再跑。本 skill 下载的 PDF/HTM 不存在文件锁问题。

### 配置 / Registry 相关

**Q: 报错"ticker XXX not in config/companies.json, and no fallback --stock-id"**
A: ticker 不在预置配置里，且 companies_registry 联网查 predict 接口也失败（可能网络问题或 ticker 确实不存在）。可以：
- 确认 ticker 拼写正确（区分大小写：HK 00700 不是 700）
- 手动跑 `python workspace/skills/futu-filing/scripts/companies_registry.py <TICKER>` 看能否解析到
- 手动在 companies.json 里添加该 ticker 的配置

**Q: 自动补的公司配置不对（选错了市场/公司）**
A: companies_registry 按"精确匹配 ticker > 美股 > 港股 > A股"选，重名情况下可能选错。手动编辑 companies.json 修正即可；已存在的条目不会被覆盖。

**Q: "no marketProfile for market=XX"**
A: companies.json 的 `marketProfiles` 里缺少该市场的配置。预置的 `HK/US/CN` 三个市场应该都有；如果新加了市场（如 UK/SG），需要补全 profile。

### 重跑 / 幂等性

**Q: 跑了一半断网/中断了，重跑会重复下载吗？**
A: 不会。默认情况下，已有 `meta.json` 的目录会被跳过（`already exists`）。重跑只会下载上次失败/未完成的部分。

**Q: 想强制重新下载所有文件怎么办？**
A: 加 `--overwrite` 参数：`python download_announcement.py --ticker XXX --workspace ./workspace --overwrite`。

**Q: 如何删除某个 ticker 的所有下载，从头开始？**
A: `rm -rf workspace/portfolio/<TICKER>/filings/fil_*`（或在 Windows 资源管理器里删除对应目录）。

## 反模式（不要做这些）

1. **不要**在无 GUI 环境直接跑 `login.py`（不加 `--skip-if-valid`）—— 会卡在等浏览器输入。编排逻辑里统一用 `--skip-if-valid`，exit 非 0 时通知人工处理。
2. **不要**在 cookies 失效时反复重跑 download_announcement.py —— 第一次失败会自动刷新 cookies，第二次再失败就说明需要手动 `--interactive` 了，盲目重跑只会浪费时间。
3. **不要**把 `--ticker` 和 `--stock-id/--market-type` 同时传混用 —— `--stock-id/--market-type` 只是旧版本兼容参数，优先用 `--ticker`。
4. **不要**直接编辑 `cookies.json` —— 格式由 Playwright 写入，手动改容易破坏结构。需要刷新就重跑 login.py。
5. **不要**设置太短的节流间隔（改 `defaults.sleepBetweenDocs` 为 0）—— 容易触发 Futu WAF 限流，导致 IP 被临时封禁。
6. **不要**把 SEC urlPrefix 改成空字符串或过于宽松 —— 可能误下载非 SEC 域名的恶意链接。
7. **HK 代码必须是 5 位数字**：`00700` ✅，`700` ❌（港股 ticker 前面要补零）。CN 代码是 6 位：`600519` ✅。
