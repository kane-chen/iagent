---
name: futu-announcements
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
workspace/skills/futu-announcements/
├── SKILL.md
├── config/
│   └── companies.json          # 公司名单 + 市场差异 + 过滤规则
├── scripts/
│   ├── login.py                # Playwright 弹窗登录 → cookies.json
│   ├── download_announcement.py# 主脚本：拉列表 → 分类 → 下载 → 生成 meta.json
│   └── requirements.txt
├── samples/                    # 调试样本落地（.gitignore）
├── cookies.json                # login 后落地（.gitignore）
└── .gitignore
```

## 首次准备

```bash
pip install -r workspace/skills/futu-announcements/scripts/requirements.txt
python -m playwright install chromium

# 首次登录 —— 弹浏览器让你手动扫码/账密登录；成功后 cookies.json 落地
python workspace/skills/futu-announcements/scripts/login.py --interactive
```

## Skill 编排里如何安全地调用 login.py（LLM 别踩坑）

**核心规则**：`login.py` 在 LLM harness 里**只能加 `--skip-if-valid` 调用**。

原因：Futu 的 WAF 要求真实浏览器指纹里出现登录 cookie（`uid` + `web_sig`）才承认会话。
无头/自动化环境拿不到这两个 cookie。手动登录必须在**有 TTY + 有图形界面**的机器上做一次。
`--skip-if-valid` 就是给这种反复触发的场景准备的幂等入口 —— cookies 齐了就 no-op。

推荐的编排（Java 侧 `execute_shell_command` 里）：

```bash
# 第 1 步：幂等地检查登录态。已登录 → 立刻 exit 0；未登录 → exit 2
python workspace/skills/futu-announcements/scripts/login.py --skip-if-valid

# 如果上面 exit 非 0，说明维护者需要在本机手动跑：
#   python workspace/skills/futu-announcements/scripts/login.py --interactive
# 此时不要在 LLM 里继续调用 —— 会卡浏览器窗口/等回车。改抛错让人处理。

# 第 2 步：拿到 cookies 后执行下载
python workspace/skills/futu-announcements/scripts/download_announcement.py --ticker 00700 --workspace ./workspace
```

**退出码约定**：
- `0` = cookies 可用（`--skip-if-valid` 命中，或 `--interactive` 成功后拿到 session）
- `1` = 未拿到任何 cookie（网络/浏览器故障）
- `2` = 等待超时未拿到 session cookie —— 需要人工介入

## 用法

```bash
# 一键下载（用 config/companies.json 里预置的 ticker）
python workspace/skills/futu-announcements/scripts/download_announcement.py \
    --ticker 00700 --workspace ./workspace

# 批量：多个 ticker 逗号分隔
python workspace/skills/futu-announcements/scripts/download_announcement.py \
    --ticker 00700,BABA,PDD --workspace ./workspace

# 起止财年（闭区间）
python workspace/skills/futu-announcements/scripts/download_announcement.py \
    --ticker 00700 --workspace ./workspace \
    --fiscal-year-start 2023 --fiscal-year-end 2025

# 或者用离散集合
python workspace/skills/futu-announcements/scripts/download_announcement.py \
    --ticker 00700 --workspace ./workspace --fiscal-years 2024,2025

# 只下年报和一季报
python workspace/skills/futu-announcements/scripts/download_announcement.py \
    --ticker 00700 --workspace ./workspace --filing-types FY,Q1

# 覆盖已存在的目录（默认跳过 meta.json 已存在的）
python workspace/skills/futu-announcements/scripts/download_announcement.py \
    --ticker BABA --workspace ./workspace --overwrite
```

## `config/companies.json` 说明

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

`supportFileTypes` 和 `titleKeyWords` 之间是 AND 关系（都命中才通过）。都为空 → 不过滤。

### SEC 文件过滤规则（`downloadStyle=sec_folder`）

按优先级：
- **b1** `filingSuffixNames` 非空 → 文件名以任一后缀（不区分大小写）结尾即接受
- **b2** `filingSuffixNames` 为空 + 公告标题含 `10-K` / `10-Q` → 只接受 `<ticker>_<yyyymmdd>.htm`
- **b3** `filingSuffixNames` 为空 + 公告标题含 `6-K` / `20-F` → 只接受 `*20f.htm` 或文件名含 `ex99-1`

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

## 落地路径

- HK / CN：`workspace/portfolio/<TICKER>/filings/fil_<prefix>_<ticker>_<year>_<form>/<pdf>`
- US：`workspace/portfolio/<TICKER>/filings/fil_<accession-hyphenated>/<all-htm-files>`

每个目录含 `meta.json`：`documentId`、`announcementId`、`formType`、`fiscalYear`、`reportDate`、`primaryFile`、`files[]` 等。

## Cookie 过期处理

`cookies.json` 里有 `expires` 时间戳；脚本调用前会检查未过期；过期只警告，接口继续调（部分 cookie 是 WAF 挑战性，未过期即可用）。如果拿到 `code=500 Params Error` 且反签算法未变，重跑 `login.py` 刷新 cookies。

## 请求节流

严格控制在 30 秒 30 次以内：
- 公告详情页每篇后 `time.sleep(sleepBetweenDocs=0.5)`
- SEC accession 内文件间 `time.sleep(sleepBetweenFilesInAccession=0.3)`
- 多 ticker 之间 `time.sleep(1.0)`
