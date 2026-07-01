#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 Futu 网页公告接口下载公司财报 PDF 并按 fil_<market>_<stock>_<year>_<form> 目录格式落地。

数据流：
  1. 调用 /quote-api/quote-v2/get-news-list 拉公告列表（复用 fetch_announcements 的 cookies）
  2. 对每条公告：
     a. 根据标题分类（年报 / 中期 / 季报 / 其他），跳过非财报类
     b. 请求公告详情页 news.futunn.com/notice/<newsId>/...，解析 HTML 找 PDF 链接
     c. 下载 PDF → workspace/portfolio/<STOCK>/filings/fil_<market>_<stock>_<year>_<form>/<hash>.pdf
     d. 生成 meta.json（documentId/formType/fiscalYear/reportDate/sourceUrl/fingerprint）

标题 → 报告分类规则（严格，避免误抓）：
  - "年报" / "年度业绩公布" / "annual report" / "annual results announcement" → FY
  - "中期报告" / "中期业绩" / "interim report" / "interim results" → H1
  - "截至YYYY年3月31日止三个月" / "Q1" → Q1
  - "截至YYYY年6月30日止...三个月及六个月" / "Q2" → 归到 Q2（同时也是 H1 数据源）
  - "截至YYYY年9月30日止...三个月及九个月" / "Q3" → Q3
  - "截至YYYY年12月31日止...三个月及十二个月" / "Q4" → Q4
  - 美股 10-K → FY，10-Q → 按标题里的季度或推断
  - 其他（会议召开日期、投票结果、股东大会通告、通函、翌日披露表等）→ 跳过

fiscalYear 推断：优先从标题里的 "YYYY 年" 数字提取；否则用发布月份倒推：
  - annual 报告发布月 1-6 → 前一自然年 (年报为上一 FY 年)
  - 其他 → 当年

用法（bash / git-bash / WSL）：
  python download_announcement_pdf.py \
      --stock-id 54047868453564 --market-type 1 --ticker 00700 \
      --workspace ./workspace \
      [--overwrite] \
      [--filing-types FY,H1,Q1,Q3] \
      [--fiscal-years 2024,2025]

用法（PowerShell）：
  python workspace/skills/futu-announcements/scripts/download_announcement_pdf.py `
      --stock-id 54047868453564 --market-type 1 --ticker 00700 `
      --workspace ./workspace `
      --output-summary ./tencent_dl.json
  # 或者不换行，一整行写完

依赖：cookies.json（由 login.py 生成）。
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

try:
    import requests
except ImportError:
    print("ERROR: requests not installed. Run:\n"
          "  pip install -r workspace/skills/futu-announcements/scripts/requirements.txt",
          file=sys.stderr)
    sys.exit(2)


# ---------------------------------------------------------------------------
# 常量与路径
# ---------------------------------------------------------------------------

DEFAULT_COOKIES = Path(__file__).parent.parent / "cookies.json"
API_URL = "https://www.futunn.com/quote-api/quote-v2/get-news-list"
NOTICE_URL_BASE = "https://news.futunn.com/notice/"
DEFAULT_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
              "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

# market_type → 目录前缀。1=港股, 2=美股, （A 股待定）
MARKET_DIR_PREFIX = {
    1: "hk",
    2: "us",
    3: "cn",   # 猜测；用户跑 A 股时若不对再调
    4: "sg",
}

# 允许的 form type（生成目录名用）
VALID_FORM_TYPES = {"FY", "H1", "Q1", "Q2", "Q3", "Q4"}


@dataclass
class Announcement:
    news_id: str
    title: str
    time_ts: int       # 发布时间秒
    detail_url: str    # 详情页 URL

    @classmethod
    def from_api(cls, item: dict) -> "Announcement":
        return cls(
            news_id=str(item.get("newsId") or item.get("id") or ""),
            title=item.get("title", "") or "",
            time_ts=int(item.get("time", 0) or 0),
            detail_url=item.get("url", "") or "",
        )

    @property
    def release_date(self) -> str:
        if not self.time_ts:
            return ""
        return dt.datetime.fromtimestamp(self.time_ts).strftime("%Y-%m-%d")

    @property
    def release_month(self) -> int:
        if not self.time_ts:
            return 0
        return dt.datetime.fromtimestamp(self.time_ts).month

    @property
    def release_year(self) -> int:
        if not self.time_ts:
            return 0
        return dt.datetime.fromtimestamp(self.time_ts).year


@dataclass
class ClassifiedFiling:
    """公告经过分类后的产物 —— 决定要不要下载、下载到哪个目录。"""
    announcement: Announcement
    form_type: str      # FY / H1 / Q1..Q4
    fiscal_year: int


# ---------------------------------------------------------------------------
# 标题分类
# ---------------------------------------------------------------------------

# 排除词：明确非财报的公告类型（董事会日期 / 股东大会 / 投票结果 / 翌日披露 / 通函）
_EXCLUDE_KEYWORDS = (
    "董事会", "股东周年大会", "股东大会", "投票结果", "投票",
    "通函", "章程", "翌日披露", "翌日报表",
    "回购", "购回", "变动月报表", "证券变动",
    "邀请", "会议召开", "meeting", "notice of", "poll result",
)

# 明确的中文数字→阿拉伯映射
_CN_DIGIT = {
    "零": 0, "一": 1, "二": 2, "三": 3, "四": 4, "五": 5,
    "六": 6, "七": 7, "八": 8, "九": 9,
    "十": 10, "廿": 20, "卅": 30,
}


def _cn_num_to_int(s: str) -> Optional[int]:
    """把中文数字（例："二零二五" / "二零二六年三月三十一"）转成年份整数。
    只处理 20XX 这种 4 位年份格式：'二零二X'/'二○二X'。"""
    if not s:
        return None
    # 只匹配开头 "二零二X" / "二〇二X" / "二○二X" 的 4 位
    cleaned = s.replace("○", "零").replace("〇", "零")
    if len(cleaned) < 4:
        return None
    head = cleaned[:4]
    try:
        d = [_CN_DIGIT[c] for c in head]
    except KeyError:
        return None
    return d[0] * 1000 + d[1] * 100 + d[2] * 10 + d[3]


def _extract_year_from_title(title: str) -> Optional[int]:
    """从公告标题里提取"报告涵盖年份"（不是发布年份）。

    优先级：
      1. 阿拉伯数字 20XX
      2. 中文数字"二零二X"或"二〇二X"
    """
    # 阿拉伯数字
    m = re.search(r"\b(20\d{2})\b", title)
    if m:
        return int(m.group(1))
    # 中文数字：找"二零二X" / "二〇二X" / "二○二X"
    m = re.search(r"([二〇○零]{2,}[〇○零一二三四五六七八九]{2})", title)
    if m:
        return _cn_num_to_int(m.group(1))
    return None


def _extract_quarter_end_month(title: str) -> Optional[int]:
    """从"截至YYYY年M月DD日止X个月" / "截至二零YY年X月YY日止X个月"里提取结束月。"""
    # 阿拉伯数字：截至2024年3月31日
    m = re.search(r"截至(?:20\d{2}|[二〇○零一二三四五六七八九]{2,})年(\d{1,2}|[一二三四五六七八九十]{1,3})月", title)
    if m:
        mo_str = m.group(1)
        if mo_str.isdigit():
            return int(mo_str)
        # 中文月份 —— 常见的 3/6/9/12 月
        cn_month_map = {
            "三": 3, "六": 6, "九": 9, "十二": 12,
            "三月": 3, "六月": 6, "九月": 9, "十二月": 12,
        }
        if mo_str in cn_month_map:
            return cn_month_map[mo_str]
        # 尝试解析"十二" / "十"
        if mo_str.startswith("十"):
            if mo_str == "十":
                return 10
            rest = mo_str[1:]
            return 10 + _CN_DIGIT.get(rest, 0)
        if mo_str in _CN_DIGIT:
            return _CN_DIGIT[mo_str]
    return None


def classify(ann: Announcement) -> Optional[ClassifiedFiling]:
    """把一条公告归类为 FY / H1 / Q1..Q4，或返回 None 表示跳过（非财报）。"""
    title = ann.title or ""
    lower = title.lower()

    # 1. 明确排除
    for kw in _EXCLUDE_KEYWORDS:
        if kw in title or kw.lower() in lower:
            return None

    form_type: Optional[str] = None

    # 2. 判断季度报告（"截至YYYY年X月YY日止...三个月/六个月/九个月/十二个月"）
    end_month = _extract_quarter_end_month(title)
    if end_month in (3, 6, 9, 12) and ("三个月" in title or "三個月" in title
                                       or "六个月" in title or "六個月" in title
                                       or "九个月" in title or "九個月" in title
                                       or "十二个月" in title or "十二個月" in title
                                       or "年度业绩" in title or "年度業績" in title
                                       or "annual results" in lower):
        form_type = {3: "Q1", 6: "Q2", 9: "Q3", 12: "Q4"}[end_month]
        # 特殊：12 月结束的三/十二个月 = 年度业绩公告，等同于 FY
        if end_month == 12 and ("年度业绩" in title or "年度業績" in title
                                or "annual results" in lower):
            form_type = "FY"

    # 3. 判断年报（覆盖整个财年 —— "YYYY 年报" / "annual report YYYY" / "年度报告"）
    if form_type is None:
        if (("年报" in title or "年報" in title)
                and not ("季报" in title or "季報" in title)):
            form_type = "FY"
        elif "annual report" in lower:
            form_type = "FY"
        elif "年度报告" in title or "年度報告" in title:
            form_type = "FY"

    # 4. 判断中期报告
    if form_type is None:
        if "中期报告" in title or "中期報告" in title:
            form_type = "H1"
        elif "中期业绩" in title or "中期業績" in title:
            form_type = "H1"
        elif "interim report" in lower or "interim results" in lower:
            form_type = "H1"
        elif "半年报" in title or "半年報" in title:
            form_type = "H1"

    # 5. 美股 10-K / 10-Q / 20-F / 8-K 财务发布类
    if form_type is None:
        if "10-k" in lower or "10-K" in title:
            form_type = "FY"
        elif "20-f" in lower or "20-F" in title:
            form_type = "FY"
        elif "10-q" in lower or "10-Q" in title:
            # 从标题里的"第X季度"或"QX"或"YYYY年Q1"等推
            m = re.search(r"[Qq]\s*([1-4])", title)
            if m:
                form_type = f"Q{m.group(1)}"
            else:
                m = re.search(r"([一二三四]|1|2|3|4)季[度报報]", title)
                if m:
                    q_char = m.group(1)
                    qmap = {"一": "Q1", "二": "Q2", "三": "Q3", "四": "Q4",
                            "1": "Q1", "2": "Q2", "3": "Q3", "4": "Q4"}
                    form_type = qmap.get(q_char)
            # 兜底：release month 反推
            if form_type is None:
                m = ann.release_month
                # 美股 10-Q 通常在季度结束后 ~30-45 天发布
                if m in (1, 2, 12):
                    form_type = "Q1"  # 覆盖 Sep/Oct/Nov 财季
                elif m in (3, 4, 5):
                    form_type = "Q2"
                elif m in (6, 7, 8):
                    form_type = "Q3"
                elif m in (9, 10, 11):
                    form_type = "Q4"
        elif "8-k" in lower or "8-K" in title:
            # 8-K 是重大事件公告，若涉及 quarterly results → 归到相应季度；否则跳过
            m = re.search(r"第\s*([一二三四1-4])\s*(季度|季)", title)
            if m:
                qmap = {"一": "Q1", "二": "Q2", "三": "Q3", "四": "Q4",
                        "1": "Q1", "2": "Q2", "3": "Q3", "4": "Q4"}
                form_type = qmap.get(m.group(1))
            elif "季度" in title and "业绩" in title:
                # 例："美光科技 | 8-K：美光科技公司公布2026财年第三季度创纪录的业绩"
                m2 = re.search(r"第\s*([一二三四1-4]|三)\s*季度", title)
                if m2:
                    qmap = {"一": "Q1", "二": "Q2", "三": "Q3", "四": "Q4",
                            "1": "Q1", "2": "Q2", "3": "Q3", "4": "Q4"}
                    form_type = qmap.get(m2.group(1))
            # 其他 8-K 跳过
            if form_type is None:
                return None

    if form_type not in VALID_FORM_TYPES:
        return None

    # ---- 财年推断 ----
    fiscal_year = _extract_year_from_title(title) or 0

    # 若标题里没有年份，用发布日期反推
    if fiscal_year <= 0:
        ry = ann.release_year
        if ry:
            # 年报：发布月 1-6 → fiscalYear = ry - 1（发布晚 3-6 个月）
            if form_type == "FY" and ann.release_month <= 6:
                fiscal_year = ry - 1
            # 季报 Q4：与年报同批发布，也可能 fiscal 是上一年
            elif form_type == "Q4" and ann.release_month <= 4:
                fiscal_year = ry - 1
            else:
                fiscal_year = ry

    if fiscal_year <= 0:
        return None

    return ClassifiedFiling(announcement=ann, form_type=form_type, fiscal_year=fiscal_year)


# ---------------------------------------------------------------------------
# HTTP 层 —— 拉列表 & 拉详情页 & 找 PDF
# ---------------------------------------------------------------------------

def load_cookies(path: Path) -> dict[str, str]:
    if not path.exists():
        print(f"ERROR: cookies file not found: {path}\n"
              f"       run scripts/login.py first.", file=sys.stderr)
        sys.exit(3)
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except Exception as e:
        print(f"ERROR: cannot parse {path}: {e}", file=sys.stderr)
        sys.exit(3)
    now = int(time.time())
    cookies_raw = payload.get("cookies", [])
    expired = [c["name"] for c in cookies_raw if 0 < c.get("expires", -1) < now]
    if expired:
        print(f"[downloader] warning: expired cookies: {expired}", file=sys.stderr)
    return {c["name"]: c["value"] for c in cookies_raw if c.get("value")}


def build_headers(referer: str) -> dict[str, str]:
    return {
        "User-Agent": DEFAULT_UA,
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        "Referer": referer,
        "Origin": "https://www.futunn.com",
        "X-Requested-With": "XMLHttpRequest",
    }


def fetch_news_list(stock_id: str, market_type: int, cookies: dict,
                    referer: str, page_size: int = 50) -> list[dict]:
    """调用 get-news-list API 拉公告列表。"""
    params = {
        "stock_id": stock_id,
        "market_type": market_type,
        "type": 1,
        "subType": 1,
        "page_size": page_size,
        "_": int(time.time() * 1000),
    }
    resp = requests.get(API_URL, params=params, headers=build_headers(referer),
                        cookies=cookies, timeout=20)
    if resp.status_code != 200:
        raise RuntimeError(f"list API HTTP {resp.status_code}: {resp.text[:300]}")
    payload = resp.json()
    if payload.get("code") not in (0, "0", None):
        raise RuntimeError(f"list API code={payload.get('code')} message={payload.get('message')}")
    return payload.get("data", {}).get("list", []) or []


# 详情页 HTML 里可能出现的 PDF URL 模式（Futu 汇集了 HKEX / SEC EDGAR / 巨潮 等外部 PDF）
_PDF_URL_PATTERNS = (
    # HKEX 披露易
    re.compile(r'https?://www1\.hkexnews\.hk/listedco/[^"\']+\.pdf', re.IGNORECASE),
    # SEC EDGAR
    re.compile(r'https?://www\.sec\.gov/Archives/edgar/[^"\']+\.(?:pdf|htm|html)', re.IGNORECASE),
    # 巨潮资讯
    re.compile(r'https?://static\.cninfo\.com\.cn/[^"\']+\.pdf', re.IGNORECASE),
    # 通用 —— static.futunn / 附件 CDN
    re.compile(r'https?://[a-z0-9.-]*futunn\.com/[^"\']+\.pdf', re.IGNORECASE),
    # 兜底：任意 https .pdf（放最后，可能误抓 —— 加 length 约束）
    re.compile(r'https?://[a-z0-9.-]+\.[a-z]{2,6}/[^"\']{5,200}\.pdf', re.IGNORECASE),
)


def find_pdf_in_html(html: str) -> Optional[str]:
    """在详情页 HTML 里找 PDF 链接。返回第一个匹配的 URL。"""
    for pat in _PDF_URL_PATTERNS:
        m = pat.search(html)
        if m:
            return m.group(0)
    return None


def fetch_notice_pdf_url(detail_url: str, cookies: dict) -> Optional[str]:
    """拉公告详情页 HTML，找 PDF 链接。"""
    headers = build_headers(detail_url)
    headers["Accept"] = "text/html,application/xhtml+xml,*/*"
    try:
        resp = requests.get(detail_url, headers=headers, cookies=cookies, timeout=20)
    except Exception as e:
        print(f"[downloader] notice fetch failed: {e}", file=sys.stderr)
        return None
    if resp.status_code != 200:
        print(f"[downloader] notice HTTP {resp.status_code} for {detail_url}", file=sys.stderr)
        return None
    return find_pdf_in_html(resp.text)


def download_pdf(pdf_url: str, target: Path, cookies: dict) -> Optional[Path]:
    """下载 PDF，写到 target。返回落地路径；失败返回 None。"""
    headers = {
        "User-Agent": DEFAULT_UA,
        "Accept": "application/pdf,*/*",
        "Referer": "https://news.futunn.com/",
    }
    try:
        resp = requests.get(pdf_url, headers=headers, cookies=cookies, timeout=60, stream=True)
    except Exception as e:
        print(f"[downloader] pdf fetch failed: {e}", file=sys.stderr)
        return None
    if resp.status_code != 200:
        print(f"[downloader] pdf HTTP {resp.status_code} for {pdf_url}", file=sys.stderr)
        return None
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("wb") as f:
        for chunk in resp.iter_content(64 * 1024):
            f.write(chunk)
    return target


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def sha256_of_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(64 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def sanitize_ticker(t: str) -> str:
    return re.sub(r"[^A-Za-z0-9_]", "", t)


def build_target_dir(workspace: Path, market_type: int, ticker: str,
                     fiscal_year: int, form_type: str) -> tuple[Path, str]:
    prefix = MARKET_DIR_PREFIX.get(market_type, "xx")
    doc_id = f"fil_{prefix}_{ticker}_{fiscal_year}_{form_type}"
    return workspace / "portfolio" / ticker / "filings" / doc_id, doc_id


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Download Futu announcement PDFs")
    parser.add_argument("--stock-id", required=True)
    parser.add_argument("--market-type", type=int, required=True,
                        help="1=HK, 2=US, 3=CN(guess)")
    parser.add_argument("--ticker", required=True,
                        help="用于目录名，如 00700 / MU / 600519")
    parser.add_argument("--workspace", required=True,
                        help="workspace 根目录（下载会落到 workspace/portfolio/...）")
    parser.add_argument("--cookies", default=str(DEFAULT_COOKIES))
    parser.add_argument("--page-size", type=int, default=50)
    parser.add_argument("--filing-types", default="",
                        help="逗号分隔的类型白名单：FY,H1,Q1,Q2,Q3,Q4（默认全部）")
    parser.add_argument("--fiscal-years", default="",
                        help="逗号分隔的财年白名单：2024,2025（默认全部）")
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--output-summary", default=None,
                        help="额外输出 JSON 摘要供 Java 上游解析")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    workspace = Path(args.workspace).resolve()
    ticker = sanitize_ticker(args.ticker)

    types_filter = {t.strip().upper() for t in args.filing_types.split(",") if t.strip()}
    years_filter = set()
    for y in args.fiscal_years.split(","):
        y = y.strip()
        if y.isdigit():
            years_filter.add(int(y))

    cookies = load_cookies(Path(args.cookies))
    # Referer 保持简单（根域）——测试发现 futunn WAF 对 stock 详情页 Referer 敏感，
    # 用简单的根域反而能过；这与 fetch_announcements.py 的做法一致。
    referer = "https://www.futunn.com/"

    print(f"[downloader] fetching news list for stock_id={args.stock_id} market={args.market_type}",
          file=sys.stderr)
    items = fetch_news_list(args.stock_id, args.market_type, cookies, referer, args.page_size)
    print(f"[downloader] got {len(items)} announcements", file=sys.stderr)

    downloaded: list[dict] = []
    skipped: list[dict] = []
    errors: list[dict] = []

    for item in items:
        ann = Announcement.from_api(item)
        classified = classify(ann)
        if classified is None:
            skipped.append({"newsId": ann.news_id, "title": ann.title,
                            "reason": "not a financial filing"})
            continue

        if types_filter and classified.form_type not in types_filter:
            skipped.append({"newsId": ann.news_id, "title": ann.title,
                            "reason": f"type {classified.form_type} not in filter"})
            continue
        if years_filter and classified.fiscal_year not in years_filter:
            skipped.append({"newsId": ann.news_id, "title": ann.title,
                            "reason": f"year {classified.fiscal_year} not in filter"})
            continue

        target_dir, doc_id = build_target_dir(workspace, args.market_type, ticker,
                                              classified.fiscal_year, classified.form_type)
        meta_path = target_dir / "meta.json"
        if meta_path.exists() and not args.overwrite:
            skipped.append({"newsId": ann.news_id, "title": ann.title,
                            "reason": "already exists"})
            continue

        print(f"[downloader] {classified.form_type} FY{classified.fiscal_year} — {ann.title}",
              file=sys.stderr)

        pdf_url = fetch_notice_pdf_url(ann.detail_url, cookies)
        if not pdf_url:
            errors.append({"newsId": ann.news_id, "title": ann.title,
                           "reason": "no PDF url in notice page"})
            continue

        # 用 URL 尾部 basename 作为文件名，避免同 filing 目录里冲突
        pdf_name = pdf_url.rsplit("/", 1)[-1]
        pdf_name = re.sub(r"[?#].*$", "", pdf_name)
        if not pdf_name.lower().endswith(".pdf"):
            pdf_name = pdf_name + ".pdf"
        target_pdf = target_dir / pdf_name

        result = download_pdf(pdf_url, target_pdf, cookies)
        if not result:
            errors.append({"newsId": ann.news_id, "title": ann.title,
                           "reason": "PDF download failed", "url": pdf_url})
            continue

        # meta.json
        try:
            sha = sha256_of_file(target_pdf)
        except Exception:
            sha = ""
        meta = {
            "documentId": doc_id,
            "announcementId": ann.news_id,
            "ticker": ticker,
            "formType": classified.form_type,
            "fiscalYear": classified.fiscal_year,
            "reportDate": ann.release_date,
            "filingDate": ann.release_date,
            "source": "futunn",
            "sourceNoticeUrl": ann.detail_url,
            "fingerprint": sha,
            "downloadTimestamp": dt.datetime.utcnow().isoformat() + "Z",
            "primaryFile": {
                "name": pdf_name,
                "sha256": sha,
                "size": target_pdf.stat().st_size,
                "contentType": "application/pdf",
                "sourceUrl": pdf_url,
            },
        }
        meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
        downloaded.append({
            "documentId": doc_id,
            "formType": classified.form_type,
            "fiscalYear": classified.fiscal_year,
            "path": str(target_pdf),
            "url": pdf_url,
            "title": ann.title,
        })
        # 别刷太快
        time.sleep(0.5)

    summary = {
        "stockId": args.stock_id,
        "marketType": args.market_type,
        "ticker": ticker,
        "downloaded": downloaded,
        "skipped": skipped,
        "errors": errors,
        "counts": {
            "downloaded": len(downloaded),
            "skipped": len(skipped),
            "errors": len(errors),
        },
    }

    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if args.output_summary:
        Path(args.output_summary).write_text(
            json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

    return 0 if not errors else 1


if __name__ == "__main__":
    sys.exit(main())
