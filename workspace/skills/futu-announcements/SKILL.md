---
name: futu-announcements
description: 通过 futunn.com 的公告 API 抓取上市公司业务公告（含年报/中期/季度业绩公告）PDF 并下载到 workspace。支持美股 / 港股 / A股 三个市场，走 Futu 网页登录态绕过反爬 WAF。触发词：Futu 公告下载、跨市场财报抓取、futunn 抓取
---

# Futu 公告下载 Skill

**跨市场统一入口 + 浏览器登录态复用 + 直连 Futu 官方公告 API**

## 应用场景

`futunn.com` 汇总了美股、港股、A股上市公司的**业务公告**（业绩公告、通函、股东大会等），
接口 `https://www.futunn.com/quote-api/quote-v2/get-news-list` 返回带 PDF 链接的公告列表。
但该接口带 WAF 挑战，直接从 Java HttpClient 调用会拿到 `Params Error`。

本 skill 用 Playwright 弹出浏览器让用户在自己的账号里手动登录一次（cookies 落地到本地），
之后由 Python 脚本携带 cookies 直接调 API，不再需要人机验证。

## 相对于 HkexDownloader / SecFilingDownloader 的定位

它是 `FinancialFilingDownloadService.downloadFiling` 下的一个**并列策略** —— 由 Java 侧
`FutuAnnouncementsDownloader` 调起。相比现有下载器：

- **优点**：三个市场（US / HK / CN_A）用同一套解析逻辑；Futu 已经清洗过公告分类；
  长期看比自建 HKEX / SEC / 巨潮爬虫更省事。
- **成本**：需要浏览器登录态；WAF 挑战偶尔升级需要维护。

## 目录结构

```
workspace/skills/futu-announcements/
├── SKILL.md                              # 本文档
├── scripts/
│   ├── login.py                          # Playwright 弹窗登录，落地 cookies.json
│   ├── fetch_announcements.py            # 用 cookies 拉某只股票的公告列表
│   ├── download_announcement_pdf.py      # 按公告拉 PDF 落地到 filings 目录
│   └── requirements.txt                  # playwright, requests
├── samples/                              # 首次跑 fetch 时写入的原始 JSON 样本
└── cookies.json                          # 登录后落地的 cookies（gitignore）
```

## 首次准备

```bash
pip install -r workspace/skills/futu-announcements/scripts/requirements.txt
python -m playwright install chromium

# 第一次登录 —— 弹出浏览器；登录完成后自动写 cookies.json
python workspace/skills/futu-announcements/scripts/login.py
```

## 用法

```bash
# 拉腾讯全部公告，保存原始 JSON 到 samples/ 供解析层参考
python workspace/skills/futu-announcements/scripts/fetch_announcements.py \
    --stock-id 54047868453564 --market-type 1 --output workspace/skills/futu-announcements/samples/tencent.json

# 由 Java FutuAnnouncementsDownloader 调用（推荐）
```

## Java 侧集成

`FinancialFilingDownloadService` 现在按 (market, provider) 两个维度选择下载器：

```java
// 默认走 HkexDownloader / SecFilingDownloader / CnInfoDownloader
service.downloadFiling(ticker, fy, filingType)

// 显式指定 provider=futu 时，走 FutuAnnouncementsDownloader（新增）
service.downloadFiling(ticker, fy, filingType, /*overwrite*/ false)
// —— 具体 provider 由 FinancialFilingDownloadService 内部配置或参数决定
```

## Cookie 过期处理

`cookies.json` 里有 `expires` 时间戳；脚本调用前会检查未过期；过期则输出
`{"error": "cookies expired, run login.py again"}`。
