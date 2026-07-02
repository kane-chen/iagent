#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 Futu 网页公告接口下载公司财报文件（HK/CN: PDF、US: SEC accession 全套 htm/pdf）。

数据流：
  1. 用 --ticker 反查 config/companies.json 得到 (stockId, market, 过滤规则)
  2. 调 /quote-api/quote-v2/get-news-list（携带 quote-token 签名头）拿公告列表
  3. title_passes_filters() 用 companies.json 的 supportFileTypes+titleKeyWords 过滤标题
  4. classify() 根据标题决定归入 FY / H1 / Q1..Q4，或跳过非财报公告
  5. 拉详情页 SSR HTML，正则匹配出真实附件 URL（HK: newsfile.futunn.com PDF；
     US: SEC EDGAR .htm 的 accession folder）
  6. HK/CN 单 PDF 直接下载；US 走 accession folder：反解 index.json 后按 filingSuffixNames
     或表格默认规则筛文件，逐个落到同一个 fil_<accession> 目录
  7. 生成 meta.json（documentId/formType/fiscalYear/reportDate/source/fingerprint...）

不同市场/公司差异（PDF 正则、目录前缀、下载风格、文件过滤）全在 config/companies.json 里。

用法：
  python download_announcement.py --ticker 00700 --workspace ./workspace
  # 批量：--ticker 逗号分隔
  python download_announcement.py --ticker 00700,BABA,PDD --workspace ./workspace
  # 起止财年（闭区间）：只下 2023-2025 三年
  python download_announcement.py --ticker 00700 --workspace ./workspace \\
      --fiscal-year-start 2023 --fiscal-year-end 2025
  # 只下年报和一季报
  python download_announcement.py --ticker 00700 --workspace ./workspace \\
      --filing-types FY,Q1

前置：先运行 login.py 生成 cookies.json。
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import hmac
import json
import re
import sys
import time
from dataclasses import dataclass, field
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

SCRIPT_DIR = Path(__file__).parent
SKILL_DIR = SCRIPT_DIR.parent
DEFAULT_COOKIES = SKILL_DIR / "cookies.json"
DEFAULT_CONFIG = SKILL_DIR / "config" / "companies.json"

NEWS_LIST_API = "https://www.futunn.com/quote-api/quote-v2/get-news-list"
DEFAULT_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
              "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36")

VALID_FORM_TYPES = {"FY", "H1", "H2", "Q1", "Q2", "Q3", "Q4"}


# ---------------------------------------------------------------------------
# 数据类
# ---------------------------------------------------------------------------

@dataclass
class Announcement:
    news_id: str
    title: str
    time_ts: int
    detail_url: str

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
        return dt.datetime.fromtimestamp(self.time_ts).strftime("%Y-%m-%d") if self.time_ts else ""

    @property
    def release_month(self) -> int:
        return dt.datetime.fromtimestamp(self.time_ts).month if self.time_ts else 0

    @property
    def release_year(self) -> int:
        return dt.datetime.fromtimestamp(self.time_ts).year if self.time_ts else 0


@dataclass
class ClassifiedFiling:
    announcement: Announcement
    form_type: str
    fiscal_year: int


@dataclass
class MarketProfile:
    market_id: str            # HK / US / CN
    dir_prefix: str
    market_type: int          # 1 / 2 / 3
    download_style: str       # single / sec_folder
    url_prefix: str = ""      # 下载链接前缀白名单（US 走 sec.gov Archives 时用）
    primary_pdf_patterns: list[re.Pattern] = field(default_factory=list)
    fallback_pdf_patterns: list[re.Pattern] = field(default_factory=list)


@dataclass
class CompanyEntry:
    ticker: str
    market: str
    stock_id: str
    cik: Optional[str] = None
    display_name: Optional[str] = None
    # 每公司自定义过滤规则（都可空）：
    #   supportFileTypes:   只保留标题含这些关键字的公告（美股常用：10-K/10-Q/20-F/6-K）
    #   titleKeyWords:      只保留标题含这些关键字的公告（港股/A 股常用：年报/中期报告/业绩/季度）
    #   filingSuffixNames:  美股 SEC 目录里只下载以这些后缀结尾的文件（如 ["ex99-1.htm","_10q.htm"]）
    #   supportPeriodTypes: 只保留 classify() 归类到这些周期的公告；可选值 Q1/Q2/Q3/Q4/H1/H2/FY
    support_file_types: list[str] = field(default_factory=list)
    title_keywords: list[str] = field(default_factory=list)
    filing_suffix_names: list[str] = field(default_factory=list)
    support_period_types: list[str] = field(default_factory=list)


@dataclass
class Config:
    companies: dict[str, CompanyEntry]        # ticker → entry
    profiles: dict[str, MarketProfile]        # market → profile
    sec_ua: str
    page_size: int
    sleep_between_docs: float
    sleep_between_files: float


# ---------------------------------------------------------------------------
# 配置加载
# ---------------------------------------------------------------------------

def load_config(path: Path) -> Config:
    if not path.exists():
        print(f"ERROR: config not found: {path}", file=sys.stderr)
        sys.exit(3)
    raw = json.loads(path.read_text(encoding="utf-8"))

    companies: dict[str, CompanyEntry] = {}
    for c in raw.get("companies", []):
        t = c.get("ticker")
        if not t:
            continue
        companies[t.upper()] = CompanyEntry(
            ticker=t.upper(), market=c.get("market", "").upper(),
            stock_id=str(c.get("stockId", "")),
            cik=c.get("cik"), display_name=c.get("displayName"),
            support_file_types=[s.strip() for s in c.get("supportFileTypes", []) if s and s.strip()],
            title_keywords=[s for s in c.get("titleKeyWords", []) if s],
            filing_suffix_names=[s.strip() for s in c.get("filingSuffixNames", []) if s and s.strip()],
            support_period_types=[s.strip().upper() for s in c.get("supportPeriodTypes", []) if s and s.strip()],
        )

    profiles: dict[str, MarketProfile] = {}
    for mkt, p in raw.get("marketProfiles", {}).items():
        profiles[mkt.upper()] = MarketProfile(
            market_id=mkt.upper(),
            dir_prefix=p.get("dirPrefix", mkt.lower()),
            market_type=int(p.get("marketType", 0)),
            download_style=p.get("downloadStyle", "single"),
            url_prefix=p.get("urlPrefix", ""),
            primary_pdf_patterns=[re.compile(pat, re.IGNORECASE) for pat in p.get("primaryPdfPatterns", [])],
            fallback_pdf_patterns=[re.compile(pat, re.IGNORECASE) for pat in p.get("fallbackPdfPatterns", [])],
        )

    defaults = raw.get("defaults", {}) or {}
    return Config(
        companies=companies,
        profiles=profiles,
        sec_ua=raw.get("secEdgarUserAgent") or DEFAULT_UA,
        page_size=int(defaults.get("pageSize", 50)),
        sleep_between_docs=float(defaults.get("sleepBetweenDocs", 0.5)),
        sleep_between_files=float(defaults.get("sleepBetweenFilesInAccession", 0.3)),
    )


# ---------------------------------------------------------------------------
# 标题分类
# ---------------------------------------------------------------------------

# 排除词：明确非财报的公告类型（董事会日期 / 股东大会 / 投票结果 / 翌日披露 / 通函）
_EXCLUDE_KEYWORDS = (
    "董事会", "股东周年大会", "股东大会", "投票结果", "投票",
    "通函", "章程", "翌日披露", "翌日报表",
    "回购", "购回", "变动月报表", "证券变动",
    "邀请", "会议召开", "meeting", "notice of", "poll result",
    # 补充：美股非业绩类 8-K
    "任命", "辞任", "股权激励", "分红", "股息", "股票期权",
)

_CN_DIGIT = {"零": 0, "一": 1, "二": 2, "三": 3, "四": 4, "五": 5,
             "六": 6, "七": 7, "八": 8, "九": 9, "十": 10, "廿": 20, "卅": 30}


def _cn_num_to_int(s: str) -> Optional[int]:
    """把 "二零二X" / "二〇二X" / "二○二X" 之类的 4 位中文年份转成整数。"""
    if not s:
        return None
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
    # Chinese-aware year extraction: standard \b fails because Chinese chars are
    # unicode word chars, so `\b(20\d{2})\b` can't find `2024` inside `截至2024年`.
    # Use negative look-around against ASCII digit only (allow leading Chinese/space/punct).
    m = re.search(r"(?<!\d)(20\d{2})(?!\d)", title)
    if m:
        return int(m.group(1))
    m = re.search(r"([二〇○零]{2,}[〇○零一二三四五六七八九]{2})", title)
    if m:
        return _cn_num_to_int(m.group(1))
    return None


def _extract_quarter_end_month(title: str) -> Optional[int]:
    """从"截至YYYY年M月DD日止..."或中文"截至二〇二X年X月X日止..."里提取结束月。"""
    m = re.search(r"截至(?:20\d{2}|[二〇○零一二三四五六七八九]{2,})年(\d{1,2}|[一二三四五六七八九十]{1,3})月", title)
    if not m:
        return None
    mo_str = m.group(1)
    if mo_str.isdigit():
        return int(mo_str)
    cn_month_map = {"三": 3, "六": 6, "九": 9, "十二": 12}
    if mo_str in cn_month_map:
        return cn_month_map[mo_str]
    if mo_str.startswith("十"):
        if mo_str == "十":
            return 10
        rest = mo_str[1:]
        return 10 + _CN_DIGIT.get(rest, 0)
    if mo_str in _CN_DIGIT:
        return _CN_DIGIT[mo_str]
    return None


def classify(ann: Announcement) -> list[ClassifiedFiling]:
    """把一条公告归类为 0-N 个 (form_type, fiscal_year) —— H1 报同时归 H1 和 Q2；
    非财报公告返回空 list。"""
    title = ann.title or ""
    lower = title.lower()

    for kw in _EXCLUDE_KEYWORDS:
        if kw in title or kw.lower() in lower:
            return []
    form_types: list[str] = []

    # 1) 港股/美股常见"截至YYYY年M月DD日止X个月"格式
    end_month = _extract_quarter_end_month(title)
    if end_month in (3, 6, 9, 12):
        # 港股年度业绩公告标题非常多样，最常见的关键词组合：
        #   "全年業績" / "全年业绩" / "年度業績" / "年度业绩" / "末期業績" / "末期业绩" / "annual results"
        #   还有 "年度财报" / "年度財報"（如美团 2024FY 用 "截至2024年12月31日止年度财报公告"）
        has_annual_words = any(kw in title for kw in
                               ("全年業績", "全年业绩", "年度業績", "年度业绩",
                                "末期業績", "末期业绩", "年度業績報告", "年度业绩报告",
                                "年度财报", "年度財報", "全年财报", "全年財報")) \
                           or "annual results" in lower
        is_interim = ("六个月" in title or "六個月" in title
                      or "interim" in lower or "中期" in title)
        is_quarter = ("三个月" in title or "三個月" in title
                      or "九个月" in title or "九個月" in title
                      or "十二个月" in title or "十二個月" in title
                      or has_annual_words)
        if end_month == 12 and has_annual_words:
            form_types = ["FY"]
        elif end_month == 6 and is_interim:
            # H1 中期业绩 —— 同时归 H1 和 Q2（数据上等价）
            form_types = ["H1", "Q2"]
        elif is_quarter:
            form_types = [{3: "Q1", 6: "Q2", 9: "Q3", 12: "Q4"}[end_month]]

    # 2) 年报 / annual report / 年度报告 / 年度业绩公告 / 年度财报公告
    if not form_types:
        if (("年报" in title or "年報" in title) and not ("季报" in title or "季報" in title)):
            form_types = ["FY"]
        elif ("annual report" in lower or "年度报告" in title or "年度報告" in title
              or "年度业绩公告" in title or "年度業績公告" in title
              or "全年业绩公告" in title or "全年業績公告" in title
              or "年度财报" in title or "年度財報" in title
              or "全年财报" in title or "全年財報" in title):
            form_types = ["FY"]

    # 3) 中期报告 —— H1 + Q2
    if not form_types:
        if ("中期报告" in title or "中期報告" in title or "中期业绩" in title or "中期業績" in title
                or "interim report" in lower or "interim results" in lower
                or "半年报" in title or "半年報" in title):
            form_types = ["H1", "Q2"]

    # 4) 美股 SEC 表格
    if not form_types:
        if "10-k" in lower or "20-f" in lower:
            form_types = ["FY"]
        elif "10-q" in lower:
            q = _infer_us_quarter(title, ann.release_month)
            if q:
                form_types = [q]
        elif "8-k" in lower or "6-k" in lower:
            # 6-K 常见"披露截至XX的业绩公告"；8-K 通常是 earnings press release
            q = _infer_us_quarter(title, ann.release_month)
            if q:
                form_types = [q]

    if not form_types:
        return []

    for ft in form_types:
        if ft not in VALID_FORM_TYPES:
            return []

    # 财年推断
    fiscal_year = _extract_year_from_title(title) or 0
    if fiscal_year <= 0:
        ry = ann.release_year
        if ry:
            if "FY" in form_types and ann.release_month <= 6:
                fiscal_year = ry - 1
            elif "Q4" in form_types and ann.release_month <= 4:
                fiscal_year = ry - 1
            else:
                fiscal_year = ry
    if fiscal_year <= 0:
        return []

    return [ClassifiedFiling(announcement=ann, form_type=ft, fiscal_year=fiscal_year)
            for ft in form_types]


def _infer_us_quarter(title: str, release_month: int) -> Optional[str]:
    """美股标题里的季度信息：QX / 第X季度 / 一二三四季 → Q1..Q4；否则用发布月倒推。"""
    m = re.search(r"[Qq]\s*([1-4])", title)
    if m:
        return f"Q{m.group(1)}"
    m = re.search(r"第\s*([一二三四1-4])\s*(?:季度|季)", title)
    if m:
        return {"一": "Q1", "二": "Q2", "三": "Q3", "四": "Q4",
                "1": "Q1", "2": "Q2", "3": "Q3", "4": "Q4"}.get(m.group(1))
    # 兜底：release month 反推 —— 以季度末月为界，把发布月归到最近结束的季度。
    # 3/31 财季结束 → 4-6 月发；6/30 → 7-9 月；9/30 → 10-12 月；12/31 → 1-3 月。
    # 覆盖 12 个月完整区间：3/6/9/12 月发的通用 6-K（标题不带 QX / 第X季度）也能兜到。
    if release_month in (1, 2, 3):
        return "Q4"
    if release_month in (4, 5, 6):
        return "Q1"
    if release_month in (7, 8, 9):
        return "Q2"
    if release_month in (10, 11, 12):
        return "Q3"
    return None


# ---------------------------------------------------------------------------
# 公告标题过滤（每公司自定义）
# ---------------------------------------------------------------------------

def title_passes_filters(title: str, entry: CompanyEntry) -> bool:
    """按 companies.json 的 supportFileTypes + titleKeyWords 双重过滤（AND 关系）：
      - supportFileTypes 非空时，标题必须至少包含一个（如 "10-K"、"6-K"）
      - titleKeyWords 非空时，标题必须至少包含一个（如 "年报"、"业绩"）
      - 两者都为空 → 不做过滤（全部放行，由 classify 决定归类）"""
    if not title:
        return False
    tl = title.lower()

    if entry.support_file_types:
        if not any(kw.lower() in tl for kw in entry.support_file_types):
            return False

    if entry.title_keywords:
        if not any(kw in title or kw.lower() in tl for kw in entry.title_keywords):
            return False

    return True


# ---------------------------------------------------------------------------
# SEC 目录文件过滤（每公司自定义 + 表格默认规则）
# ---------------------------------------------------------------------------

def _title_contains_any(title: str, tokens: list[str]) -> bool:
    tl = title.lower()
    return any(t.lower() in tl for t in tokens)


def sec_file_passes_filters(file_name: str, announcement_title: str,
                             ticker: str, entry: CompanyEntry) -> bool:
    """判断 SEC accession folder 里的某个文件是否需要下载。规则（按优先级）：
       b1) filingSuffixNames 非空 → 文件名以任一后缀（不区分大小写）结尾即通过
       b2) filingSuffixNames 为空，且公告标题含 "10-K"/"10-Q" → 只接受 "<ticker>_yyyymmdd.htm"
       b3) filingSuffixNames 为空，且公告标题含 "6-K"/"20-F" → 只接受
              以 "20f.htm" 结尾，或文件名含 "ex99-1"
       其他情况 → 拒绝（保守，避免混入无关文件）
    """
    if not file_name:
        return False
    fn = file_name.lower()

    # b1) 用户显式配置
    if entry.filing_suffix_names:
        return any(fn.endswith(sfx.lower()) for sfx in entry.filing_suffix_names)

    # b2) 10-K / 10-Q 表格 → 主报告文件命名规则 <ticker>_<yyyymmdd>.htm
    if _title_contains_any(announcement_title, ["10-K", "10-Q"]):
        pattern = re.compile(rf"^{re.escape(ticker.lower())}[-_]\d{{8}}\.htm[l]?$")
        return bool(pattern.match(fn))

    # b3) 6-K / 20-F 表格 → 20-F 主报告或 6-K 附带的 ex99-1 业绩发布
    if _title_contains_any(announcement_title, ["6-K", "20-F"]):
        if fn.endswith("20f.htm") or fn.endswith("20f.html"):
            return True
        if "ex99-1" in fn:
            return True

    return False


# ---------------------------------------------------------------------------
# HTTP 层
# ---------------------------------------------------------------------------

def load_cookies(path: Path) -> dict[str, str]:
    if not path.exists():
        print(f"ERROR: cookies file not found: {path}\n"
              f"       run: python {path.parent}/scripts/login.py --force-interactive",
              file=sys.stderr)
        sys.exit(3)
    payload = json.loads(path.read_text(encoding="utf-8"))
    now = int(time.time())
    expired = [c["name"] for c in payload.get("cookies", []) if 0 < c.get("expires", -1) < now]
    if expired:
        print(f"[downloader] warning: expired cookies: {expired}", file=sys.stderr)
    return {c["name"]: c["value"] for c in payload.get("cookies", []) if c.get("value")}


def build_headers(referer: str) -> dict[str, str]:
    return {
        "User-Agent": DEFAULT_UA,
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        "Referer": referer,
        "Origin": "https://www.futunn.com",
        "X-Requested-With": "XMLHttpRequest",
    }


# ------ quote-token 反签 --------------------------------------------------
# axios 拦截器逻辑：
#   payload = JSON.stringify({k: String(v) for k,v in params if v is not None})
#   quote-token = SHA256( HmacSHA512(payload, "quote_web").hex()[:10] ).hex()[:10]
# 缺失会被服务端拒为 {code:500,message:"Params Error"}。

def _serialize_params_for_token(params: dict) -> str:
    coerced = {k: str(v) for k, v in params.items() if v is not None}
    return json.dumps(coerced, separators=(",", ":"), ensure_ascii=False)


def compute_quote_token(params: dict) -> str:
    payload = _serialize_params_for_token(params) or "quote"
    hmac_hex = hmac.new(b"quote_web", payload.encode("utf-8"), hashlib.sha512).hexdigest()
    return hashlib.sha256(hmac_hex[:10].encode("utf-8")).hexdigest()[:10]


def _augment_headers_with_signature(headers: dict, cookies: dict, params: dict) -> dict:
    csrf = cookies.get("csrfToken")
    result = dict(headers)
    if csrf:
        result.setdefault("futu-x-csrf-token", csrf)
    result["quote-token"] = compute_quote_token(params)
    return result


def fetch_news_list(session: requests.Session, stock_id: str, market_type: int,
                    cookies: dict, referer: str, page_size: int = 50) -> list[dict]:
    """调 futunn 的 get-news-list 接口，返回原始 list。"""
    params = {
        "stock_id": stock_id,
        "market_type": market_type,
        "type": 1,
        "subType": 1,
        "pageSize": page_size,   # 服务端只在 camelCase pageSize 上生效
        "_": int(time.time() * 1000),
    }
    headers = _augment_headers_with_signature(build_headers(referer), cookies, params)
    resp = session.get(NEWS_LIST_API, params=params, headers=headers,
                       cookies=cookies, timeout=20)
    if resp.status_code != 200:
        raise RuntimeError(f"list API HTTP {resp.status_code}: {resp.text[:300]}")
    payload = resp.json()
    if payload.get("code") not in (0, "0", None):
        raise RuntimeError(f"list API code={payload.get('code')} message={payload.get('message')}")
    return payload.get("data", {}).get("list", []) or []


def warm_up_session(session: requests.Session, cookies: dict) -> None:
    """访问一次根域拿全 WAF cookies；否则 news.futunn.com 详情页返回 10 KB 空壳而非 SSR 内容。
    幂等：同一 session 反复调也没坏处。"""
    session.get("https://www.futunn.com/",
                headers={"User-Agent": DEFAULT_UA,
                         "Accept": "text/html,application/xhtml+xml,*/*",
                         "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8"},
                cookies=cookies, timeout=15)


def fetch_notice_pdf_url(session: requests.Session, detail_url: str, cookies: dict,
                         profile: MarketProfile) -> Optional[str]:
    """拉详情页 SSR HTML，按 profile.primaryPdfPatterns → fallbackPdfPatterns 顺序匹配。"""
    headers = {
        "User-Agent": DEFAULT_UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        "Referer": "https://www.futunn.com/",
        "Cache-Control": "no-cache",
        "Sec-Fetch-Dest": "document",
        "Sec-Fetch-Mode": "navigate",
        "Sec-Fetch-Site": "cross-site",
        "Upgrade-Insecure-Requests": "1",
    }
    try:
        resp = session.get(detail_url, headers=headers, cookies=cookies, timeout=20)
    except Exception as e:
        print(f"[downloader] notice fetch failed: {e}", file=sys.stderr)
        return None
    if resp.status_code != 200:
        print(f"[downloader] notice HTTP {resp.status_code} for {detail_url}", file=sys.stderr)
        return None
    text = resp.text
    for pat in list(profile.primary_pdf_patterns) + list(profile.fallback_pdf_patterns):
        m = pat.search(text)
        if m:
            return m.group(0)
    return None


# ---------------------------------------------------------------------------
# 下载：HK 单 PDF vs US SEC accession folder
# ---------------------------------------------------------------------------

# 判断"6-K 里的 ex99-1 到底是不是业绩报告"的正/负关键词表。
# —— Futu 的 news list API 对所有 6-K 都吐同一个通用标题（"6-K：外国私营发行人报告"），
# 上游 title/classify 无法区分季度业绩、股东大会决议、章程修订、上市转换等。
# 到 SEC 文件下载完之后，读一次 primary 文件的前 20 KB 用关键词分类：
# 命中正向关键词 & 没命中强负向关键词 → 保留；否则删掉临时目录，丢弃这份公告。
_FINANCIAL_POSITIVE_MARKERS = (
    # 英文业绩发布 —— "Announces <period> Results"
    "announces first quarter", "announces second quarter",
    "announces third quarter", "announces fourth quarter",
    "announces full year", "announces fiscal", "announces annual",
    "announces march quarter", "announces june quarter",
    "announces september quarter", "announces december quarter",
    # 常见结构语
    "financial results for", "unaudited financial results",
    "business and financial highlights",
    "business highlights",
    "financial review and prospects",
    "quarter summary financial",           # BABA 常见小标题
    "consolidated results of the company", # BABA 通稿开场白
    # 中概股常见的"合并财务报表附件"（LI/BABA 半年报把 F-2/F-3 的 balance sheet 直接嵌 ex99-1）
    "unaudited condensed consolidated financial statements",
    "condensed consolidated balance sheet",
    "condensed consolidated statements of comprehensive",
    "condensed consolidated statements of operations",
    # SEC 表格自身标识（20-F 主报告文件里出现）
    "annual report pursuant to section",
    "form 20-f", "form 10-k", "form 10-q",
    # 半年度/中期
    "interim report", "interim financial",
)

# 强负向关键词：只要出现（在标题区）就基本可确认是非业绩类 6-K。
# 谨慎起见，用**明确指向公告标题主题**的措辞，而不是常见于正文脚注的短语：
#   "postponement of certain projects" 是业绩通稿常见用语 —— 不能用 "postponement of " 简写捕捉。
#   "annual general meeting" 也常出现在通稿"授权回购"章节 —— 已通过限定"仅前 2000 字符"避免。
_FINANCIAL_NEGATIVE_MARKERS = (
    "announces results of annual general meeting",
    "announces results of extraordinary general meeting",
    "notice of annual general meeting",
    "notice of extraordinary general meeting",
    "notice of special meeting",
    "to hold annual general meeting",
    "to hold extraordinary general meeting",
    "proposed amendments to the memorandum",
    "amendments to the memorandum and articles",
    "amended and restated memorandum",
    "voluntary conversion to dual primary listing",
    "adjournment of annual general",
    "adjournment of extraordinary",
    "postponement of annual general",
    "postponement of extraordinary",
    "poll results of the annual general",
    "circular in relation to",
    "form of proxy for",
    "announces change of ",         # 董事变更、公司名变更等
    "announces resignation of ",
    "announces appointment of ",
    "announces retirement of ",
    "announces the completion of the ",  # 交易/发债完成
)


def is_financial_report_content(primary_path: Path, max_bytes: int = 120_000) -> bool:
    """读 primary 文件前 max_bytes 字节，按关键词判定是否为财报正文。

    专为**美股 6-K 的 ex99-1 附件（HTML）**做二次筛查设计 —— Futu news list API
    对所有 6-K 都返回同一条通用中文标题，无法在上游 title/classify 阶段区分业绩发布 vs
    股东大会决议/章程修订/董事变更等，此函数用文件内容关键词兜底。

    **PDF 二进制安全**：真正的 PDF 文件（以 `%PDF-` magic 起头）直接返回 True —— 用来
    读 UTF-8 会全是乱码，任何关键词都匹配不到，会把合法财报全部误杀。HK/CN 单 PDF 流
    在上游 titleKeyWords 已经做过精确过滤，也不需要走内容级筛查。

    对 20-F/10-K/10-Q 主报告文件走"文件名兜底"分支：那些文件前几十 KB 通常是 XBRL/CSS
    头，业务文本要 100 KB 之后才出现。

    小工具原则：读文件失败或内容太短一律返回 True，让下游按老逻辑保留。
    """
    if primary_path is None:
        return True

    # PDF 二进制 → 直接放行（内容级筛查不适用）
    try:
        with primary_path.open("rb") as f:
            magic = f.read(8)
        if magic.startswith(b"%PDF-"):
            return True
    except Exception:
        return True

    # 文件名兜底：SEC 表格主报告的命名极稳定，直接放行不再看内容。
    #   20-F   → <ticker>-yyyymmddx20f.htm[l]  /  *_20f.htm  /  *20f.html
    #   10-K   → <ticker>-yyyymmdd.htm         （无后缀标识但目录里只有它 + XBRL）
    #   10-Q   → 同上，或者 *_10q.htm
    name_lower = primary_path.name.lower()
    if (name_lower.endswith("20f.htm") or name_lower.endswith("20f.html")
            or name_lower.endswith("_20f.htm") or name_lower.endswith("_20-f.htm")
            or name_lower.endswith("_10k.htm") or name_lower.endswith("_10-k.htm")
            or name_lower.endswith("_10q.htm") or name_lower.endswith("_10-q.htm")):
        return True
    # <ticker>-yyyymmdd.htm 命名（AAPL/GOOG/MSFT 等 10-K/10-Q 主报告）
    if re.match(r"^[a-z]{2,6}[-_]\d{8}\.htm[l]?$", name_lower):
        return True

    try:
        with primary_path.open("rb") as f:
            raw = f.read(max_bytes)
        text = raw.decode("utf-8", errors="replace")
    except Exception:
        return True
    if len(text) < 500:
        return True

    # 归一化：小写 + 删掉所有 HTML 标签 + &nbsp;/&mdash; 之类的实体 + 折叠空白
    # 这样跨标签跨换行的短语（"Announces<br>March&nbsp;Quarter"）也能被扁平化匹配到。
    lower = text.lower()
    lower = re.sub(r"<[^>]+>", " ", lower)      # 去标签
    lower = re.sub(r"&[a-z]+;|&#\d+;", " ", lower)  # 去实体
    lower = re.sub(r"\s+", " ", lower)

    # 强负向关键词只在**标题区**（前 2000 字符，覆盖 "Exhibit 99.1 <公司名> <标题>"）
    # 生效 —— 因为业绩通稿正文里常有"于本公司未来三个 annual general meetings 授权
    # 回购股份"这类合法出现，如果全文匹配会误杀。
    head = lower[:2000]
    for kw in _FINANCIAL_NEGATIVE_MARKERS:
        if kw in head:
            return False

    # 命中任一正向 → 保留
    for kw in _FINANCIAL_POSITIVE_MARKERS:
        if kw in lower:
            return True

    # 都没命中：保守拒绝 —— 因为 Futu 6-K 通用标题下能通过 SEC 后缀白名单落地到这里的、
    # 又没有任何业绩关键词的文件，实际上就是非业绩类公告（治理、章程、上市转换等）。
    return False


def _sec_should_download(name: str, announcement_title: str, ticker: str,
                          entry: CompanyEntry) -> bool:
    """判断 SEC accession folder 里的某个文件是否需要下载。
    首选按 companies.json 的 filingSuffixNames / 表格类型 默认规则（sec_file_passes_filters），
    始终保留 accession 的 index 页作为溯源依据。"""
    ln = name.lower()
    if ln.endswith("-index.html") or ln.endswith("-index.htm") or ln.endswith("-index-headers.html"):
        return True
    return sec_file_passes_filters(name, announcement_title, ticker, entry)


def download_hk_single_pdf(session: requests.Session, pdf_url: str, target_dir: Path,
                           cookies: dict) -> Optional[Path]:
    target_dir.mkdir(parents=True, exist_ok=True)
    fname = pdf_url.rsplit("/", 1)[-1]
    fname = re.sub(r"[?#].*$", "", fname)
    if not fname.lower().endswith(".pdf"):
        fname += ".pdf"
    target = target_dir / fname
    headers = {"User-Agent": DEFAULT_UA, "Accept": "application/pdf,*/*",
               "Referer": "https://news.futunn.com/"}
    try:
        r = session.get(pdf_url, headers=headers, cookies=cookies, timeout=60, stream=True)
    except Exception as e:
        print(f"[downloader] hk pdf fetch failed: {e}", file=sys.stderr)
        return None
    if r.status_code != 200:
        print(f"[downloader] hk pdf HTTP {r.status_code} for {pdf_url}", file=sys.stderr)
        return None
    with target.open("wb") as f:
        for chunk in r.iter_content(64 * 1024):
            f.write(chunk)
    return target


def download_sec_accession(session: requests.Session, seed_url: str, target_dir: Path,
                           sec_ua: str, sleep_between_files: float,
                           announcement_title: str, ticker: str,
                           entry: CompanyEntry) -> list[Path]:
    """给定 SEC EDGAR 某个 accession 里的任意一个文件 URL，拉 index.json，
    按 companies.json 的过滤规则（filingSuffixNames 或表格类型默认规则）逐个下载到 target_dir。
    返回落地的文件列表；空列表表示失败。"""
    # 反推 accession folder
    if "/" not in seed_url:
        return []
    accession_base = seed_url.rsplit("/", 1)[0]
    index_url = f"{accession_base}/index.json"
    hdrs = {"User-Agent": sec_ua, "Accept": "application/json, */*"}
    try:
        r = session.get(index_url, headers=hdrs, timeout=20)
    except Exception as e:
        print(f"[downloader] sec index fetch failed: {e}", file=sys.stderr)
        return []
    if r.status_code != 200:
        print(f"[downloader] sec index HTTP {r.status_code} for {index_url}", file=sys.stderr)
        return []
    try:
        idx = r.json()
    except Exception:
        print(f"[downloader] sec index not JSON: {r.text[:200]}", file=sys.stderr)
        return []
    files = idx.get("directory", {}).get("item", []) or []
    if not files:
        return []
    target_dir.mkdir(parents=True, exist_ok=True)
    saved: list[Path] = []
    for item in files:
        name = item.get("name")
        if not name:
            continue
        if not _sec_should_download(name, announcement_title, ticker, entry):
            continue
        file_url = f"{accession_base}/{name}"
        target = target_dir / name
        if target.exists() and target.stat().st_size > 0:
            saved.append(target)
            continue
        try:
            fr = session.get(file_url, headers={"User-Agent": sec_ua}, timeout=60, stream=True)
        except Exception as e:
            print(f"[downloader]   sec fetch failed {name}: {e}", file=sys.stderr)
            continue
        if fr.status_code != 200:
            print(f"[downloader]   sec HTTP {fr.status_code} for {name}", file=sys.stderr)
            continue
        with target.open("wb") as f:
            for chunk in fr.iter_content(64 * 1024):
                f.write(chunk)
        saved.append(target)
        time.sleep(sleep_between_files)
    return saved


# ---------------------------------------------------------------------------
# meta.json / 目录布局
# ---------------------------------------------------------------------------

def sha256_of_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(64 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def sanitize_ticker(t: str) -> str:
    return re.sub(r"[^A-Za-z0-9_]", "", t)


def build_hk_target_dir(workspace: Path, profile: MarketProfile, ticker: str,
                       fiscal_year: int, form_type: str) -> tuple[Path, str]:
    doc_id = f"fil_{profile.dir_prefix}_{ticker}_{fiscal_year}_{form_type}"
    return workspace / "portfolio" / ticker / "filings" / doc_id, doc_id


def build_sec_target_dir(workspace: Path, ticker: str, seed_url: str) -> tuple[Path, str]:
    """SEC 用 accession number 作 dir 名（与现有 fil_0001104659-25-049400 一致）。"""
    # seed_url 形如 https://www.sec.gov/Archives/edgar/data/1737806/000110465926067186/tm...
    m = re.search(r"/data/\d+/(\d+)/", seed_url)
    if not m:
        # fallback: use last folder in URL
        parts = seed_url.rsplit("/", 2)
        accession_raw = parts[-2] if len(parts) >= 2 else "unknown"
    else:
        accession_raw = m.group(1)
    # 转成 hyphen 形式：000110465926067186 → 0001104659-26-067186
    if re.fullmatch(r"\d{18}", accession_raw):
        formatted = f"{accession_raw[:10]}-{accession_raw[10:12]}-{accession_raw[12:]}"
    else:
        formatted = accession_raw
    doc_id = f"fil_{formatted}"
    return workspace / "portfolio" / ticker / "filings" / doc_id, doc_id


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def _resolve_targets(args, config: Config) -> list[tuple[CompanyEntry, MarketProfile]]:
    """把 --ticker（可逗号分隔）+ 兼容旧参数 --stock-id/--market-type 解析成待处理列表。

    未命中 companies.json 时会调 {@link companies_registry.CompaniesRegistry}：
    走 futunn predict 接口查一个候选、按"美股>港股>A股 / 剔除 ADR/ETF"选中一只、
    构建 entry 并写回 companies.json。写回后重新加载 config，然后照原逻辑消费。
    """
    tickers = [t.strip().upper() for t in (args.ticker or "").split(",") if t.strip()]
    targets: list[tuple[CompanyEntry, MarketProfile]] = []

    # 惰性初始化 registry：只有真的需要联网时才导入并 new
    registry = None
    config_path = Path(args.config)

    for t in tickers:
        entry = config.companies.get(t)
        if not entry:
            # 走 registry 补齐：命中 config 直接返回；未命中调 predict 接口并写回 config
            if registry is None:
                # 延迟导入以避免"没写这个 registry"的老代码兼容问题
                try:
                    from companies_registry import CompaniesRegistry
                except ImportError as e:
                    print(f"[downloader] companies_registry unavailable: {e}",
                          file=sys.stderr)
                    CompaniesRegistry = None
                registry = (CompaniesRegistry(config_path)
                            if CompaniesRegistry is not None else False)
            if registry:
                if registry.resolve(t) is not None:
                    # 写回后 reload 一次，保证 config.companies 拿到最新
                    config = load_config(config_path)
                    if args.page_size:
                        config.page_size = args.page_size
                    entry = config.companies.get(t)

        if entry:
            profile = config.profiles.get(entry.market)
            if not profile:
                print(f"ERROR: no marketProfile for market={entry.market!r} (ticker {t})", file=sys.stderr)
                continue
            targets.append((entry, profile))
        elif args.stock_id and args.market_type:
            # 兼容：ticker 不在配置里但用户显式给了 stock-id/market-type，构造临时 entry
            mkt_name = next((k for k, v in config.profiles.items()
                             if v.market_type == args.market_type), None)
            profile = config.profiles.get(mkt_name or "")
            if not profile:
                print(f"ERROR: unknown --market-type {args.market_type}", file=sys.stderr)
                continue
            targets.append((CompanyEntry(ticker=t, market=profile.market_id,
                                         stock_id=args.stock_id), profile))
        else:
            print(f"ERROR: ticker {t} not in config/companies.json, and no fallback --stock-id.",
                  file=sys.stderr)

    return targets


def process_one_ticker(session: requests.Session, cookies: dict, entry: CompanyEntry,
                       profile: MarketProfile, args, config: Config) -> dict:
    """处理一只股票：拉列表 → 分类 → 每条公告下载对应文件 → 生成 meta.json。"""
    ticker = sanitize_ticker(entry.ticker)
    print(f"\n[downloader] === {ticker} ({entry.market}, stockId={entry.stock_id}) ===",
          file=sys.stderr)

    items = fetch_news_list(session, entry.stock_id, profile.market_type, cookies,
                            referer="https://www.futunn.com/", page_size=config.page_size)
    print(f"[downloader] got {len(items)} announcements", file=sys.stderr)

    types_filter = {t.strip().upper() for t in (args.filing_types or "").split(",") if t.strip()}
    years_filter = {int(y) for y in (args.fiscal_years or "").split(",") if y.strip().isdigit()}
    # 起止财年闭区间 —— 与 years_filter 是 AND 关系（都命中才通过）
    year_start = args.fiscal_year_start
    year_end = args.fiscal_year_end
    if year_start is not None and year_end is not None and year_start > year_end:
        year_start, year_end = year_end, year_start

    downloaded: list[dict] = []
    skipped: list[dict] = []
    errors: list[dict] = []

    for item in items:
        ann = Announcement.from_api(item)

        # 公告标题过滤：companies.json 里 supportFileTypes + titleKeyWords 的 AND 组合
        # （都空则不过滤，交给 classify 决定归类）
        if not title_passes_filters(ann.title, entry):
            skipped.append({"newsId": ann.news_id, "title": ann.title,
                            "reason": "filtered by supportFileTypes/titleKeyWords"})
            continue

        classifieds = classify(ann)
        if not classifieds:
            skipped.append({"newsId": ann.news_id, "title": ann.title,
                            "reason": "not a financial filing"})
            continue

        # 应用过滤 —— 只要有一种分类通过就处理；不通过的分类被丢掉
        # supportPeriodTypes（配置层）与 --filing-types（CLI 层）都是白名单，AND 关系。
        period_whitelist = set(entry.support_period_types) if entry.support_period_types else None
        remaining = [c for c in classifieds
                     if (not types_filter or c.form_type in types_filter)
                     and (period_whitelist is None or c.form_type in period_whitelist)
                     and (not years_filter or c.fiscal_year in years_filter)
                     and (year_start is None or c.fiscal_year >= year_start)
                     and (year_end is None or c.fiscal_year <= year_end)]
        if not remaining:
            skipped.append({"newsId": ann.news_id, "title": ann.title,
                            "reason": "filter did not match"})
            continue

        # 每条 announcement 只拉一次详情页；同 URL 落地成多份 meta（H1 报同时归 H1 和 Q2）
        pdf_url = fetch_notice_pdf_url(session, ann.detail_url, cookies, profile)
        time.sleep(config.sleep_between_docs)  # 详情页请求节流

        if not pdf_url:
            errors.append({"newsId": ann.news_id, "title": ann.title,
                           "reason": "no PDF url in notice page"})
            continue

        primary_result_by_form: dict[str, dict] = {}
        for classified in remaining:
            result = _download_one(session, cookies, ann, classified, profile, entry,
                                   pdf_url, args, config)
            if result.get("ok"):
                downloaded.append(result["record"])
                primary_result_by_form[classified.form_type] = result["record"]
            elif result.get("skipped"):
                skipped.append({"newsId": ann.news_id, "title": ann.title,
                                "reason": result["skipped"]})
            else:
                errors.append({"newsId": ann.news_id, "title": ann.title,
                               "reason": result.get("error", "unknown")})
        time.sleep(config.sleep_between_docs)

    return {
        "ticker": ticker,
        "market": entry.market,
        "stockId": entry.stock_id,
        "downloaded": downloaded, "skipped": skipped, "errors": errors,
        "counts": {"downloaded": len(downloaded), "skipped": len(skipped), "errors": len(errors)},
    }


def _download_one(session, cookies, ann, classified: ClassifiedFiling,
                  profile: MarketProfile, entry: CompanyEntry,
                  pdf_url: str, args, config: Config) -> dict:
    """按 profile.download_style 下载一份文件到对应目录，返回 {ok/skipped/error, record?}。"""
    workspace = Path(args.workspace).resolve()
    ticker = sanitize_ticker(entry.ticker)

    if profile.download_style == "sec_folder":
        target_dir, doc_id = build_sec_target_dir(workspace, ticker, pdf_url)
    else:
        target_dir, doc_id = build_hk_target_dir(workspace, profile, ticker,
                                                classified.fiscal_year, classified.form_type)

    meta_path = target_dir / "meta.json"
    if meta_path.exists() and not args.overwrite:
        return {"skipped": "already exists"}

    print(f"[downloader]   {classified.form_type} FY{classified.fiscal_year} — {ann.title[:60]!r}",
          file=sys.stderr)

    saved_files: list[Path] = []
    primary_path: Optional[Path] = None
    if profile.download_style == "single":
        result = download_hk_single_pdf(session, pdf_url, target_dir, cookies)
        if result:
            saved_files = [result]
            primary_path = result
    elif profile.download_style == "sec_folder":
        # US 市场额外校验：链接前缀必须是 SEC Archives（避免误抓到其他 CDN 上的 htm/pdf）
        if profile.url_prefix and not pdf_url.startswith(profile.url_prefix):
            return {"error": f"URL prefix mismatch (expected {profile.url_prefix})", "url": pdf_url}
        saved_files = download_sec_accession(session, pdf_url, target_dir,
                                            config.sec_ua, config.sleep_between_files,
                                            ann.title, ticker, entry)
        # primary = 最大的 .htm/.pdf（排除 index），或首个 non-index htm
        candidates = [p for p in saved_files
                     if p.suffix.lower() in (".htm", ".html", ".pdf")
                     and "-index" not in p.name.lower()]
        if candidates:
            primary_path = max(candidates, key=lambda p: p.stat().st_size)

    if not saved_files:
        return {"error": "download failed", "url": pdf_url}

    # SEC 目录只留下了 -index 文件、没抓到正经的主报告/展品 —— 说明这份
    # 公告没有配置里想要的表格类型（如 6-K/20-F 里没有 ex99-1，可能是任命公告、
    # 章程变更等非业绩类 6-K）。清理临时索引文件，跳过该公告。
    if profile.download_style == "sec_folder" and primary_path is None:
        for p in saved_files:
            try:
                p.unlink()
            except Exception:
                pass
        try:
            target_dir.rmdir()
        except Exception:
            pass
        return {"skipped": "SEC folder had no primary file matching filters"}

    # 内容级过滤：美股 6-K 的 ex99-1 附件在这里做二次筛查 ——
    # Futu news list API 对所有 6-K 都返回同一条通用标题（"6-K：外国私营发行人报告"），
    # 无法在上游 title/classify 阶段区分业绩发布、股东大会决议、章程修订等；等到
    # SEC ex99-1 落地后读一次文件正文，用关键词判定是否财报。
    #
    # 只对 sec_folder 生效：HK/CN 单 PDF 流已经在 titleKeyWords 阶段用中文标题
    # ("业绩公告"/"季度报告"等) 精确过滤过；PDF 二进制读为 UTF-8 会全是乱码，
    # 关键词永远匹配不到，会把合法财报全误杀。
    if (profile.download_style == "sec_folder"
            and primary_path is not None
            and not is_financial_report_content(primary_path)):
        print(f"[downloader]   skip non-financial content: "
              f"{primary_path.name}", file=sys.stderr)
        for p in saved_files:
            try:
                p.unlink()
            except Exception:
                pass
        try:
            target_dir.rmdir()
        except Exception:
            pass
        return {"skipped": "primary content is not a financial report"}

    # 写 meta.json
    primary_info = {}
    if primary_path:
        try:
            sha = sha256_of_file(primary_path)
        except Exception:
            sha = ""
        primary_info = {
            "name": primary_path.name,
            "sha256": sha,
            "size": primary_path.stat().st_size,
            "contentType": "application/pdf" if primary_path.suffix.lower() == ".pdf" else "text/html",
            "sourceUrl": pdf_url,
        }

    meta = {
        "documentId": doc_id,
        "announcementId": ann.news_id,
        "ticker": ticker,
        "market": entry.market,
        "formType": classified.form_type,
        "fiscalYear": classified.fiscal_year,
        "reportDate": ann.release_date,
        "filingDate": ann.release_date,
        "source": "futunn",
        "sourceNoticeUrl": ann.detail_url,
        "downloadTimestamp": dt.datetime.utcnow().isoformat() + "Z",
        "primaryFile": primary_info,
        "files": [p.name for p in saved_files],
    }
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")

    return {
        "ok": True,
        "record": {
            "documentId": doc_id,
            "formType": classified.form_type,
            "fiscalYear": classified.fiscal_year,
            "primaryFile": str(primary_path) if primary_path else "",
            "files": [str(p) for p in saved_files],
            "url": pdf_url,
            "title": ann.title,
        },
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Download Futu announcement PDFs/HTMs (multi-market)")
    parser.add_argument("--ticker", required=True,
                        help="股票代码（配置里查表）。多个用逗号分隔，如 '00700,BABA,PDD'")
    parser.add_argument("--workspace", required=True,
                        help="workspace 根目录（下载会落到 workspace/portfolio/<TICKER>/filings/...）")
    parser.add_argument("--config", default=str(DEFAULT_CONFIG),
                        help=f"配置文件路径（默认 {DEFAULT_CONFIG}）")
    parser.add_argument("--cookies", default=str(DEFAULT_COOKIES))
    parser.add_argument("--page-size", type=int, default=None, help="覆盖 config.defaults.pageSize")
    parser.add_argument("--filing-types", default="",
                        help="逗号分隔的类型白名单：FY,H1,Q1,Q2,Q3,Q4（默认全部）")
    parser.add_argument("--fiscal-years", default="",
                        help="逗号分隔的财年白名单：2024,2025（默认全部）")
    parser.add_argument("--fiscal-year-start", type=int, default=None,
                        help="起始财年（含）；与 --fiscal-year-end 一起组成闭区间")
    parser.add_argument("--fiscal-year-end", type=int, default=None,
                        help="结束财年（含）；与 --fiscal-year-start 一起组成闭区间")
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--output-summary", default=None,
                        help="额外输出 JSON 摘要供 Java 上游解析")
    # 兼容旧参数
    parser.add_argument("--stock-id", help="[兼容] 直接指定 stockId，绕过配置查表")
    parser.add_argument("--market-type", type=int, help="[兼容] 直接指定 marketType(1=HK,2=US)")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    config = load_config(Path(args.config))
    if args.page_size:
        config.page_size = args.page_size

    targets = _resolve_targets(args, config)
    if not targets:
        print("ERROR: no valid targets", file=sys.stderr)
        return 1

    cookies = load_cookies(Path(args.cookies))
    session = requests.Session()
    session.cookies.update(cookies)
    warm_up_session(session, cookies)  # 拿全 WAF cookies，让详情页 SSR 内容能返回

    all_summaries: list[dict] = []
    total_errors = 0
    for i, (entry, profile) in enumerate(targets):
        try:
            summary = process_one_ticker(session, cookies, entry, profile, args, config)
        except RuntimeError as e:
            print(f"[downloader] ticker {entry.ticker} failed: {e}", file=sys.stderr)
            summary = {"ticker": entry.ticker, "market": entry.market,
                       "stockId": entry.stock_id, "downloaded": [], "skipped": [],
                       "errors": [{"reason": str(e)}],
                       "counts": {"downloaded": 0, "skipped": 0, "errors": 1}}
        all_summaries.append(summary)
        total_errors += summary["counts"]["errors"]
        # 多 ticker 之间也节流一下
        if i < len(targets) - 1:
            time.sleep(1.0)

    payload = {"tickers": all_summaries,
               "totals": {
                   "downloaded": sum(s["counts"]["downloaded"] for s in all_summaries),
                   "skipped": sum(s["counts"]["skipped"] for s in all_summaries),
                   "errors": total_errors,
               }}
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    if args.output_summary:
        Path(args.output_summary).write_text(
            json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0 if total_errors == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
