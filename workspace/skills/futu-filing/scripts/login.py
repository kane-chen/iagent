#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Futu 网页登录态获取脚本（一次性 / 过期时重跑）。

**重要观察**：抓 futunn 公告只需要 WAF 建立的一套会话 cookie（`csrfToken` / `uid` /
`web_sig` / `ci_sig`），并**不**要求账号登录。所以本脚本默认是**全自动的**：
  1. 无头启动 Chromium 访问 futunn.com
  2. 等 WAF cookie 齐全（一般 3-8 秒），落地 cookies.json
  3. 退出 —— 全程不需要 stdin 交互，也不会弹浏览器

只有当抓的是账号相关数据（自选股 / 私有报表等）时才需要人工登录：

  python login.py --interactive              # 弹浏览器让人手动登录（首次跑）
  python login.py --skip-if-valid            # 已经有 cookies.json 就 no-op 退出（幂等）

用法参数：
  --headless             全自动模式的开关（默认 True）；--no-headless / --interactive 会弹窗
  --interactive          等价于 --no-headless --force-interactive，弹窗+等回车
  --skip-if-valid        cookies.json 已存在且未过期就直接退出 0
  --wait-seconds N       全自动/非交互模式最长等待秒数（默认 60）
  --profile-dir <path>   跨次复用 Chrome profile
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

# ---------------------------------------------------------------------------
# 导入 Playwright
# ---------------------------------------------------------------------------
try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print(
        "ERROR: playwright not installed. Run:\n"
        "  pip install -r workspace/skills/futu-filing/scripts/requirements.txt\n"
        "  python -m playwright install chromium",
        file=sys.stderr,
    )
    sys.exit(2)


DEFAULT_START_URL = "https://www.futunn.com/stock/00700-HK"
DEFAULT_OUTPUT = Path(__file__).parent.parent / "cookies.json"

# 抓 futunn 公告接口需要的 WAF 会话字段 —— 有这几个就足以调 get-news-list（本 skill 主用途）
_SESSION_COOKIES = {"csrfToken", "uid", "web_sig"}

# 真正的账号登录 cookie（未来抓自选/私有数据时才需要）
_ACCOUNT_LOGIN_COOKIES = {"mainAccount", "identifier",
                          "userId", "user_id", "futu_user_id",
                          "loginid", "login_id",
                          "web_session", "web_token", "futu_web_session"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Futu login → cookies.json（默认全自动）")
    parser.add_argument("--output", "-o", default=str(DEFAULT_OUTPUT),
                        help=f"Cookie 输出文件（默认：{DEFAULT_OUTPUT}）")
    parser.add_argument("--start-url", default=DEFAULT_START_URL,
                        help="登录起始页 URL")
    parser.add_argument("--headless", dest="headless", action="store_true", default=True,
                        help="无头模式（默认；自动化用）")
    parser.add_argument("--no-headless", dest="headless", action="store_false",
                        help="弹出浏览器窗口（人机场景）")
    parser.add_argument("--interactive", action="store_true",
                        help="等价于 --no-headless + 等 TTY 回车 —— 首次账号登录时用")
    parser.add_argument("--profile-dir", default=None,
                        help="持久化浏览器 profile 目录，跨次复用登录态")
    parser.add_argument("--wait-seconds", type=int, default=60,
                        help="等 WAF/登录 cookie 出现的最长秒数（默认 60）")
    parser.add_argument("--poll-interval", type=float, default=1.5,
                        help="轮询检查 cookies 的间隔（秒，默认 1.5）")
    parser.add_argument("--skip-if-valid", action="store_true",
                        help="cookies.json 已存在且未过期就直接退出 0；"
                             "适合 LLM harness 里作为幂等调用")
    parser.add_argument("--require-account", action="store_true",
                        help="要求账号登录 cookie（mainAccount 等）—— 抓私有数据时用；"
                             "默认只需 WAF session cookie 就算成功")
    return parser.parse_args()


def _cookie_names(cookies: list[dict]) -> set[str]:
    return {c.get("name") for c in cookies if c.get("name")}


def _has_session_cookies(names: set[str]) -> bool:
    """WAF 会话建立完成，可以调 futunn 公告 API。"""
    return _SESSION_COOKIES.issubset(names)


def _has_account_login(names: set[str]) -> bool:
    """账号登录（非仅 WAF session）—— 极少数私有数据接口需要。"""
    return bool(names & _ACCOUNT_LOGIN_COOKIES)


def _check_existing_cookies(output_path: Path, require_account: bool) -> bool:
    """cookies.json 已存在且未过期且符合 require_account 要求就返回 True。"""
    if not output_path.exists():
        return False
    try:
        payload = json.loads(output_path.read_text(encoding="utf-8"))
    except Exception:
        return False
    cookies = payload.get("cookies", []) or []
    if not cookies:
        return False
    now = int(time.time())
    names = set()
    for c in cookies:
        expires = c.get("expires", -1)
        if expires > 0 and expires < now:
            continue  # 过期，忽略
        names.add(c.get("name"))
    if require_account:
        return _has_account_login(names)
    return _has_session_cookies(names)


def _serialize_cookies(context) -> list[dict]:
    cookies = context.cookies("https://www.futunn.com/")
    keep_domains = ("futunn.com", ".futunn.com", "www.futunn.com")
    out = []
    for c in cookies:
        domain = c.get("domain", "")
        if not any(d in domain for d in keep_domains):
            continue
        out.append({
            "name": c["name"], "value": c["value"], "domain": domain,
            "path": c.get("path", "/"), "expires": c.get("expires", -1),
            "httpOnly": c.get("httpOnly", False), "secure": c.get("secure", False),
            "sameSite": c.get("sameSite", "None"),
        })
    return out


def _save_cookies(output_path: Path, cookies: list[dict],
                  has_session: bool, has_account: bool) -> None:
    payload = {
        "generated_at": int(time.time()),
        "has_session": has_session,
        "has_account_login": has_account,
        "logged_in_heuristic": has_account,   # 兼容旧字段
        "cookies": cookies,
    }
    output_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2),
                           encoding="utf-8")


def _wait_for_cookies(context, wait_seconds: int, poll_interval: float,
                      require_account: bool) -> tuple[list[dict], bool]:
    """轮询等待条件 cookie 就绪。返回 (cookies, ok)。"""
    deadline = time.time() + wait_seconds
    last_report = 0.0
    while True:
        cookies = _serialize_cookies(context)
        names = _cookie_names(cookies)
        ok = _has_account_login(names) if require_account else _has_session_cookies(names)
        if ok:
            return cookies, True
        now = time.time()
        remaining = int(deadline - now)
        if remaining <= 0:
            return cookies, False
        if now - last_report >= 10:
            target = "account login" if require_account else "session"
            print(f"[futu-login] 等 {target} cookies… 剩余 {remaining}s / 已抓 {len(cookies)} 条",
                  file=sys.stderr)
            last_report = now
        time.sleep(poll_interval)


def _interactive_prompt(context) -> tuple[list[dict], bool, bool]:
    """交互模式：等用户按回车，然后判定是否登录。返回 (cookies, has_session, has_account)。"""
    print("\n[futu-login] === 请在浏览器窗口里完成登录 ===")
    print("[futu-login] 完成后回到这里按回车继续。", file=sys.stderr)
    try:
        input("[futu-login] 按回车导出 cookies... ")
    except EOFError:
        print("[futu-login] stdin 关闭，退化到轮询", file=sys.stderr)
        cookies, _ = _wait_for_cookies(context, 180, 2.0, True)
        names = _cookie_names(cookies)
        return cookies, _has_session_cookies(names), _has_account_login(names)
    cookies = _serialize_cookies(context)
    names = _cookie_names(cookies)
    return cookies, _has_session_cookies(names), _has_account_login(names)


def main() -> int:
    args = parse_args()
    output_path = Path(args.output).resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)

    # --interactive 等价于 --no-headless（弹窗）
    headless = args.headless and not args.interactive

    # 幂等快速退出
    if args.skip_if_valid and _check_existing_cookies(output_path, args.require_account):
        print(f"[futu-login] cookies.json 已存在且有效：{output_path}")
        print("[futu-login] --skip-if-valid 命中，直接退出。")
        return 0

    print(f"[futu-login] 模式：{'交互（弹窗）' if args.interactive else ('无头' if headless else '有窗')}"
          f"，起始页：{args.start_url}", file=sys.stderr)

    with sync_playwright() as p:
        launch_kwargs = {"headless": headless}
        browser = None
        if args.profile_dir:
            profile = Path(args.profile_dir).resolve()
            profile.mkdir(parents=True, exist_ok=True)
            context = p.chromium.launch_persistent_context(
                str(profile), **launch_kwargs,
                viewport={"width": 1280, "height": 900},
            )
        else:
            browser = p.chromium.launch(**launch_kwargs)
            context = browser.new_context(viewport={"width": 1280, "height": 900})

        page = context.new_page()
        page.goto(args.start_url, wait_until="domcontentloaded")

        if args.interactive:
            cookies, has_session, has_account = _interactive_prompt(context)
        else:
            cookies, ok = _wait_for_cookies(context, args.wait_seconds, args.poll_interval,
                                            args.require_account)
            names = _cookie_names(cookies)
            has_session = _has_session_cookies(names)
            has_account = _has_account_login(names)

        if not cookies:
            print("[futu-login] 未在 www.futunn.com 域下拿到任何 cookie —— 网页没加载？",
                  file=sys.stderr)
            context.close()
            if browser is not None: browser.close()
            return 1

        print(f"[futu-login] 拿到 {len(cookies)} 条 cookies"
              f" | session={'YES' if has_session else 'NO'}"
              f" | account_login={'YES' if has_account else 'NO'}",
              file=sys.stderr)
        _save_cookies(output_path, cookies, has_session, has_account)
        print(f"[futu-login] cookies 已保存到 {output_path}", file=sys.stderr)

        context.close()
        if browser is not None: browser.close()

    # 决定退出码
    ok = has_account if args.require_account else has_session
    if not ok:
        target = "account login" if args.require_account else "WAF session"
        print(f"[futu-login] 警告：等待超时，未拿到 {target} cookies。", file=sys.stderr)
        if not args.interactive:
            print("[futu-login] 试试 `python login.py --interactive` 手动完成。",
                  file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
