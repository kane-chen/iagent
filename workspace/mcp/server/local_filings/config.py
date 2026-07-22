# -*- coding: utf-8 -*-
"""
运行时配置——统一解析 workspace 根目录。

优先级：
  1. CLI 参数 --directory
  2. 环境变量 IAGENT_WORKSPACE_DIR
  3. 从 server.py 位置向上回溯，寻找包含 pom.xml 的应用根目录，使用 <app-root>/workspace
"""

import os
import sys
from pathlib import Path
from typing import Optional


class WorkspaceConfig:
    """不可变的 workspace 配置，resolve 后冻结。"""

    __slots__ = ("_workspace_root",)

    def __init__(self, workspace_root: Path) -> None:
        if not workspace_root.is_dir():
            raise NotADirectoryError(
                f"workspace 目录不存在或不是一个目录: {workspace_root}"
            )
        self._workspace_root = workspace_root.resolve()

    # ── 核心路径 ──────────────────────────────────────────
    @property
    def workspace_root(self) -> Path:
        return self._workspace_root

    @property
    def excels_dir(self) -> Path:
        return self._workspace_root / "excels"

    @property
    def portfolio_dir(self) -> Path:
        return self._workspace_root / "portfolio"

    @property
    def financials_dir(self) -> Path:
        """兼容旧格式：扁平 ticker_前缀 CSV/XLSX"""
        return self._workspace_root / "financials"

    @property
    def reports_dir(self) -> Path:
        """兼容旧格式：扁平 ticker_前缀 PDF"""
        return self._workspace_root / "reports"


# ── 全局可变配置（测试时可替换） ────────────────────────────
_RUNTIME_CONFIG: Optional[WorkspaceConfig] = None


def set_config(cfg: WorkspaceConfig) -> None:
    global _RUNTIME_CONFIG
    _RUNTIME_CONFIG = cfg


def get_config() -> WorkspaceConfig:
    if _RUNTIME_CONFIG is None:
        raise RuntimeError("WorkspaceConfig 尚未初始化，请先调用 resolve_and_set()")
    return _RUNTIME_CONFIG


# ── 路径解析 ────────────────────────────────────────────────


def find_app_root(marker: str = "pom.xml") -> Path:
    """从 server.py 所在位置向上回溯，找到包含 marker 的目录作为应用根目录。"""
    cursor = Path(__file__).resolve()
    for _ in range(20):
        cursor = cursor.parent
        if (cursor / marker).is_file():
            return cursor
    # 找不到 pom.xml 时回退到当前工作目录
    return Path.cwd()


def resolve_workspace_root(
    cli_directory: Optional[str] = None,
    env_var: str = "IAGENT_WORKSPACE_DIR",
) -> Path:
    """解析 workspace 根目录（含 exceles/ portfolio/）。"""
    # 1. CLI 参数
    if cli_directory:
        return Path(cli_directory).resolve()
    # 2. 环境变量
    env_val = os.environ.get(env_var)
    if env_val:
        return Path(env_val).resolve()
    # 3. 默认：<app-root>/workspace
    return find_app_root() / "workspace"


def resolve_and_set(cli_directory: Optional[str] = None) -> WorkspaceConfig:
    """解析目录并设置全局运行时配置。"""
    root = resolve_workspace_root(cli_directory)
    cfg = WorkspaceConfig(root)
    set_config(cfg)
    return cfg