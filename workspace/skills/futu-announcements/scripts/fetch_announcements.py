#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
用 cookies.json 调 Futu 的公告列表 API，抓取指定股票的公告并输出为标准 JSON。

Futu 的公告接口（网页版）：
    https://www.futunn.com/quote-api/quote-v2/get-news-list
        ?stock_id=<numeric>       内部股票 id（不是 ticker）
        &market_type=<1/2/...>    1=港股, 2=美股, 其他=A股（有待核实，第一次拉真实响应确认）
        &type=1                   type=1 表示公告
        &subType=1                subType=1 表示业务公告 / 财报类公告
        &_=<ms timestamp>         cache buster

由于 API 结构在真实运行前我们没有采样，本脚本按 "先跑一次抓原始 JSON" 的思路：
  - 保存服务器返回的完整 JSON 到 --output（供人工/后续脚本解析）
  - 在 stderr 打印 top-level 键、data 数组长度、前 3 条的 keys 供快速看
  - 如果 API 返回 code != 0 或非 JSON，进程退出码非零，方便 Java 上游判断

用法示例：
    # 一次性拉腾讯（先跑通链路，看结构）
    python fetch_announcements.py \
        --stock-id 54047868453564 --market-type 1 \
        --output samples/tencent_raw.json

    # 未来加入分页 / 时间过滤时（脚本预留了 --page-size 和 --before）
    python fetch_announcements.py --stock-id 206117 --market-type 2 --page-size 30

前置：先运行 login.py 得到 cookies.json。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

try:
    import requests
except ImportError:
    print("ERROR: requests not installed. Run:\n"
          "  pip install -r workspace/skills/futu-announcements/scripts/requirements.txt",
          file=sys.stderr)
    sys.exit(2)


DEFAULT_COOKIES = Path(__file__).parent.parent / "cookies.json"
DEFAULT_API = "https://www.futunn.com/quote-api/quote-v2/get-news-list"
DEFAULT_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
              "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Fetch Futu announcements via web API")
    parser.add_argument("--stock-id", required=True,
                        help="Futu 内部数字股票 id（不是 ticker）")
    parser.add_argument("--market-type", type=int, required=True,
                        help="市场类型：1=HK, 2=US, （A 股待确认）")
    parser.add_argument("--type", dest="news_type", type=int, default=1,
                        help="接口 type 参数（默认 1）")
    parser.add_argument("--sub-type", type=int, default=1,
                        help="接口 subType 参数（默认 1）")
    parser.add_argument("--page-size", type=int, default=50,
                        help="每页数量（若接口支持；默认 50）")
    parser.add_argument("--cookies", default=str(DEFAULT_COOKIES),
                        help=f"cookies.json 路径（默认：{DEFAULT_COOKIES}）")
    parser.add_argument("--output", "-o", required=True,
                        help="输出 JSON 文件路径")
    parser.add_argument("--api-url", default=DEFAULT_API,
                        help=f"接口地址（默认：{DEFAULT_API}）")
    parser.add_argument("--stock-page-url", default=None,
                        help="用作 Referer 的股票详情页 URL —— 有些 API 校验 Referer；"
                             "如果不填，脚本用一个通用 futunn.com Referer")
    parser.add_argument("--timeout", type=int, default=20)
    return parser.parse_args()


def load_cookies(path: Path) -> dict[str, str]:
    """从 cookies.json 加载出 name=value dict。"""
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
    if not cookies_raw:
        print(f"ERROR: {path} has no cookies", file=sys.stderr)
        sys.exit(3)

    expired = [c["name"] for c in cookies_raw
               if 0 < c.get("expires", -1) < now]
    if expired:
        # 只警告 —— 关键的 waf/session cookie 可能仍然有效
        print(f"[fetch] warning: expired cookies: {expired}", file=sys.stderr)

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


def _summarize(payload: object) -> str:
    """打印 payload 的 top-level 结构，便于第一次跑时看清返回格式。"""
    lines = []
    if isinstance(payload, dict):
        lines.append(f"top-level keys: {list(payload.keys())}")
        # 深入 data 字段
        data = payload.get("data")
        if isinstance(data, list):
            lines.append(f"data: list of {len(data)}")
            if data:
                first = data[0]
                if isinstance(first, dict):
                    lines.append(f"data[0] keys: {list(first.keys())}")
                    # 拿前 3 条的 (title, url, date) 样本
                    for i, item in enumerate(data[:3]):
                        if isinstance(item, dict):
                            preview = {k: v for k, v in item.items()
                                       if k in ("title", "url", "link", "pdfUrl", "pdf_url",
                                                "date", "publishTime", "publish_time",
                                                "categoryName", "category")}
                            lines.append(f"data[{i}] preview: {preview}")
        elif isinstance(data, dict):
            lines.append(f"data (dict) keys: {list(data.keys())}")
            # 常见分页嵌套
            for k in ("list", "items", "results", "news_list"):
                if k in data and isinstance(data[k], list):
                    lines.append(f"  data.{k}: list of {len(data[k])}")
                    if data[k]:
                        item0 = data[k][0]
                        if isinstance(item0, dict):
                            lines.append(f"  data.{k}[0] keys: {list(item0.keys())}")
    return "\n".join(lines) or repr(payload)[:300]


def main() -> int:
    args = parse_args()
    output_path = Path(args.output).resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)

    cookies = load_cookies(Path(args.cookies))
    referer = args.stock_page_url or "https://www.futunn.com/"
    headers = build_headers(referer)

    params = {
        "stock_id": args.stock_id,
        "market_type": args.market_type,
        "type": args.news_type,
        "subType": args.sub_type,
        "page_size": args.page_size,
        "_": int(time.time() * 1000),
    }

    print(f"[fetch] GET {args.api_url}", file=sys.stderr)
    print(f"[fetch] params: {params}", file=sys.stderr)
    print(f"[fetch] cookies: {sorted(cookies.keys())}", file=sys.stderr)

    try:
        resp = requests.get(args.api_url, params=params, headers=headers,
                            cookies=cookies, timeout=args.timeout)
    except Exception as e:
        print(f"ERROR: request failed: {e}", file=sys.stderr)
        return 4

    if resp.status_code != 200:
        print(f"ERROR: HTTP {resp.status_code}: {resp.text[:500]}", file=sys.stderr)
        return 5

    ctype = resp.headers.get("content-type", "")
    body_preview = resp.text[:500]

    try:
        payload = resp.json()
    except Exception as e:
        # 有可能被 WAF 拦成了 HTML —— 保存原始 body 便于排查
        raw_path = output_path.with_suffix(output_path.suffix + ".raw.txt")
        raw_path.write_text(resp.text, encoding="utf-8")
        print(f"ERROR: response is not JSON (content-type={ctype}). Raw saved to {raw_path}", file=sys.stderr)
        print(f"       first 500 chars: {body_preview}", file=sys.stderr)
        return 6

    # 保存原始 JSON —— 不做任何解构，供上游/下次开发解析
    output_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"[fetch] wrote {output_path}", file=sys.stderr)
    print(_summarize(payload), file=sys.stderr)

    # 检测常见错误状态
    if isinstance(payload, dict):
        code = payload.get("code")
        if code not in (0, None, "0"):
            print(f"WARN: API returned code={code} message={payload.get('message')!r}",
                  file=sys.stderr)
            # 不改变输出（保留原始响应），但让退出码非零，Java 侧可以据此提示重新登录
            return 7 if code == 500 else 8

    return 0


if __name__ == "__main__":
    sys.exit(main())
