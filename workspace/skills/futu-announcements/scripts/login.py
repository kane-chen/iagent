#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Futu 网页登录态获取脚本（一次性 / 过期时重跑）。

流程：
  1. 用 Playwright 打开 Chromium 浏览器窗口指向 futunn.com
  2. 用户在浏览器里手动登录（可能是账号密码 / 短信 / 扫码）
  3. 用户完成登录后回到脚本终端按回车（或直接关掉浏览器）
  4. 脚本导出当前 www.futunn.com 域下的 cookies 到 cookies.json
     —— 后续 fetch_announcements.py / download_announcement_pdf.py 复用

用法：
    python login.py                            # 默认落地到本目录的 cookies.json
    python login.py --output /path/cookies.json
    python login.py --headless                 # 无头（仅在已有会话可复用时有用；一般不用）
    python login.py --start-url https://www.futunn.com/  # 起始页

登录成功的判断：
  - www.futunn.com 域下能拿到 `mainAccount` / `identifier` 等登录 cookie，
    或者 wafToken 已经稳定（wafToken 仅表示 WAF 挑战通过）。
  - 但因为登录检测靠 heuristic 容易误判，脚本默认让用户明确按回车确认已完成登录。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

# ---------------------------------------------------------------------------
# 导入 Playwright，失败时提供友好的安装提示
# ---------------------------------------------------------------------------
try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print(
        "ERROR: playwright not installed. Run:\n"
        "  pip install -r workspace/skills/futu-announcements/scripts/requirements.txt\n"
        "  python -m playwright install chromium",
        file=sys.stderr,
    )
    sys.exit(2)


# 默认起始页（登录页）—— 直接进 futu 官网让用户登录
DEFAULT_START_URL = "https://www.futunn.com/"
DEFAULT_OUTPUT = Path(__file__).parent.parent / "cookies.json"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Interactive Futu login → cookies.json")
    parser.add_argument("--output", "-o", default=str(DEFAULT_OUTPUT),
                        help=f"Cookie 输出文件（默认：{DEFAULT_OUTPUT}）")
    parser.add_argument("--start-url", default=DEFAULT_START_URL,
                        help="登录起始页 URL")
    parser.add_argument("--headless", action="store_true",
                        help="无头模式（不推荐，除非已经有可复用的会话）")
    parser.add_argument("--profile-dir", default=None,
                        help="Playwright 持久化 profile 目录，方便下次自动登录 —— "
                             "默认使用临时 profile，登录一次是一次；建议指定后可"
                             "跨次复用 Chrome 的记住密码/自动填充。")
    return parser.parse_args()


def prompt(message: str) -> None:
    """在终端提示用户，读一次回车。"""
    try:
        input(message)
    except EOFError:
        # 非交互场景（管道输入）—— 等一小段时间再继续
        time.sleep(1)


def _serialize_cookies(context) -> list[dict]:
    """把 Playwright 的 cookies 序列化为可 JSON 输出的普通 dict 列表。"""
    cookies = context.cookies("https://www.futunn.com/")
    # Playwright 已经是 dict list，但我们剔除掉一些内部字段 & 只保留 futunn 域
    keep_domains = ("futunn.com", ".futunn.com", "www.futunn.com")
    out = []
    for c in cookies:
        domain = c.get("domain", "")
        if not any(d in domain for d in keep_domains):
            continue
        out.append({
            "name": c["name"],
            "value": c["value"],
            "domain": domain,
            "path": c.get("path", "/"),
            "expires": c.get("expires", -1),
            "httpOnly": c.get("httpOnly", False),
            "secure": c.get("secure", False),
            "sameSite": c.get("sameSite", "None"),
        })
    return out


def _looks_logged_in(cookies: list[dict]) -> bool:
    """快速判断是否已经登录。

    Futu 登录后会下发若干 mainAccount / futu_visited / identifier 类 cookie；
    未登录 tab 里只有 wafToken 与匿名统计 cookie。这里用几个已知的关键 key 做启发式判断。
    """
    seen = {c["name"] for c in cookies}
    # 观测到的登录标志字段（宽松匹配）
    signals = {
        "mainAccount", "identifier",
        "userId", "user_id", "futu_user_id",
        "loginid", "login_id",
        "web_session", "web_token", "futu_web_session",
    }
    if seen & signals:
        return True
    # 有 wafToken 但没有其他登录字段 → 视为未登录
    return False


def main() -> int:
    args = parse_args()
    output_path = Path(args.output).resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)

    print(f"[futu-login] 启动浏览器，起始页：{args.start_url}")
    print(f"[futu-login] Cookies 将保存到：{output_path}")

    with sync_playwright() as p:
        launch_kwargs = {"headless": args.headless}
        if args.profile_dir:
            profile = Path(args.profile_dir).resolve()
            profile.mkdir(parents=True, exist_ok=True)
            context = p.chromium.launch_persistent_context(
                str(profile),
                **launch_kwargs,
                viewport={"width": 1280, "height": 900},
            )
        else:
            browser = p.chromium.launch(**launch_kwargs)
            context = browser.new_context(viewport={"width": 1280, "height": 900})

        page = context.new_page()
        page.goto(args.start_url, wait_until="domcontentloaded")

        print("\n[futu-login] === 请在浏览器窗口里完成登录 ===")
        print("[futu-login] 完成后回到这里按回车继续。")
        prompt("[futu-login] 按回车导出 cookies... ")

        cookies = _serialize_cookies(context)
        if not cookies:
            print("[futu-login] 未在 www.futunn.com 域下拿到任何 cookie —— 是不是网页没加载完？",
                  file=sys.stderr)
            return 1

        logged_in = _looks_logged_in(cookies)
        print(f"[futu-login] 拿到 {len(cookies)} 条 cookies，登录检测：{'YES' if logged_in else 'NO/UNKNOWN'}")

        if not logged_in:
            # 不阻断 —— 让用户决定；有些人 nightly 会话 fingerprint 只有 wafToken 就足够拉公开公告
            print("[futu-login] 警告：cookies 里没找到明确的登录标志字段。可以继续保存，"
                  "但如果后续调用返回未授权错误，请重新登录。")

        payload = {
            "generated_at": int(time.time()),
            "logged_in_heuristic": logged_in,
            "cookies": cookies,
        }
        output_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2),
                               encoding="utf-8")
        print(f"[futu-login] cookies 已保存到 {output_path}")

        context.close()
        if not args.profile_dir:
            browser.close()

    return 0


if __name__ == "__main__":
    sys.exit(main())
