"""通用的 OpenAI 兼容 chat/completions 客户端（对齐 Java LlmClient）。

用于 financial-filing-qa skill 内所有需要调用 LLM chat 接口的组件——
查询改写、语义 rerank、答案合成——避免每个脚本重复编写 HTTP 调用逻辑。

特性：
- 支持 API Key 认证（Bearer token）
- 每次调用可覆盖 temperature、max_tokens、timeout 参数
- 默认禁用推理模型 thinking 模式（think=False），发送 think + chat_template_kwargs 双字段，
  防止 reasoning 耗尽 token 导致 content 为空
- content 为空时自动回退到 reasoning_content/reasoning/thinking 字段原文返回，
  并尝试从中提取 JSON
- 提供 extract_json_from_text / extract_answer_from_reasoning 辅助方法
- 所有异常 fail-soft，返回空字符串（对齐 Java 设计，调用方负责降级）
"""
from __future__ import annotations

import json
import logging
import re
from typing import Any

import requests

logger = logging.getLogger(__name__)

# 默认值（对齐 Java LlmClient）
_DEFAULT_MAX_TOKENS = 10240
_DEFAULT_TEMPERATURE = 0.3
_DEFAULT_TIMEOUT = 180
_CONNECT_TIMEOUT = 10

# 复用 Session 以启用连接池
_session: requests.Session | None = None


def _get_session() -> requests.Session:
    global _session
    if _session is None:
        _session = requests.Session()
    return _session


# ------------------------------------------------------------------
# HTTP 底层
# ------------------------------------------------------------------

def _post_json(url: str, body: dict, api_key: str = "", timeout: int = _DEFAULT_TIMEOUT) -> dict:
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    sess = _get_session()
    # requests timeout 可以是 (connect, read) 元组
    resp = sess.post(
        url,
        headers=headers,
        data=json.dumps(body, ensure_ascii=False),
        timeout=(_CONNECT_TIMEOUT, timeout),
    )
    if resp.status_code < 200 or resp.status_code >= 300:
        raise RuntimeError(
            f"HTTP {resp.status_code} from {url}: {resp.text[:500]}"
        )
    return resp.json() or {}


# ------------------------------------------------------------------
# 核心 chat 接口
# ------------------------------------------------------------------

def chat(
    system_prompt: str,
    user_prompt: str,
    llm_cfg: dict,
    *,
    temperature: float | None = None,
    max_tokens: int | None = None,
    timeout: int | None = None,
    think: bool | None = None,
) -> str:
    """调用 OpenAI 兼容 /chat/completions 接口，返回 assistant content 文本。

    针对推理模型（如 qwen3），默认禁用 thinking 模式（think=False），
    避免 reasoning_content 耗尽 max_tokens 导致 content 为空。
    如调用方需要保留 thinking 能力，显式传 think=True。

    所有异常 fail-soft，返回空字符串。对齐 Java LlmClient.chat。

    Args:
        system_prompt: 系统提示词
        user_prompt: 用户提示词
        llm_cfg: 配置 dict，支持 baseUrl/model/apiKey/temperature/maxTokens/requestTimeoutSeconds
        temperature: 覆盖 temperature（None 则使用 cfg 或默认值 0.3）
        max_tokens: 覆盖 max_tokens（None 则使用 cfg 或默认值 10240）
        timeout: 覆盖请求超时秒数（None 则使用 cfg 或默认值 180）
        think: 控制 thinking 模式。
               False（默认）→ 发送 think=False + chat_template_kwargs={think:False} 禁用推理；
               True → 不发送 think 参数（使用模型默认行为，即启用推理）；
               None → 检查 cfg 中的 think 字段，未配置则默认 False（禁用）。
    """
    try:
        base = llm_cfg.get("baseUrl", "http://localhost:11434/v1").rstrip("/")
        model = llm_cfg.get("model", "qwen3.5:4b")
        api_key = llm_cfg.get("apiKey", "")

        # 参数合并：显式 keyword args > cfg > 默认值
        eff_temp = temperature if temperature is not None else float(
            llm_cfg.get("temperature", _DEFAULT_TEMPERATURE)
        )
        eff_max = max_tokens if max_tokens is not None else int(
            llm_cfg.get("maxTokens", _DEFAULT_MAX_TOKENS)
        )
        eff_timeout = timeout if timeout is not None else int(
            llm_cfg.get("requestTimeoutSeconds", _DEFAULT_TIMEOUT)
        )
        # think 参数：默认禁用（对齐 Java disableThinking=true）
        if think is None:
            cfg_think = llm_cfg.get("think")
            if cfg_think is True:
                think = True  # cfg 显式启用
            else:
                think = False  # 默认禁用

        body: dict[str, Any] = {
            "model": model,
            "temperature": eff_temp,
            "max_tokens": eff_max,
            "stream": False,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
        }
        # 显式控制 thinking 模式：双字段发送以兼容不同推理模型提供商
        # （对齐 Java LlmClient.buildRequestBody）
        if think is False:
            body["think"] = False
            body["chat_template_kwargs"] = {"think": False}

        data = _post_json(base + "/chat/completions", body, api_key, eff_timeout)

        msg = data["choices"][0]["message"]
        content = msg.get("content") or ""

        # 兜底：content 为空时依次 fallback 到 reasoning_content → reasoning → thinking
        if not content.strip():
            reasoning = (
                msg.get("reasoning_content")
                or msg.get("reasoning")
                or msg.get("thinking")
                or ""
            )
            if reasoning.strip():
                # 尝试从 reasoning 中提取 JSON（结构化输出场景）
                extracted = extract_json_from_text(reasoning)
                content = extracted if extracted else reasoning

        return content
    except Exception as e:
        logger.warning("LLM chat failed: %s", e)
        return ""


# ------------------------------------------------------------------
# Reasoning / JSON 提取辅助方法（对齐 Java LlmClient）
# ------------------------------------------------------------------

def extract_json_from_text(
    text: str,
    target_keys: tuple[str, ...] = ("keywords", "ranked", "sufficient"),
) -> str:
    """从文本（通常是 reasoning_content）中提取结构化 JSON。

    先通过括号深度匹配查找包含 target_keys 中任意 key 的 JSON 对象，
    回退使用正则查找。适用于查询改写、rerank、sufficiency 判断等结构化输出场景。

    对齐 Java LlmClient.extractJsonFromText。
    """
    if not text or not text.strip():
        return ""

    # 第一轮：括号深度扫描，找到第一个包含目标 key 的 JSON 对象
    for i in range(len(text)):
        if text[i] != "{":
            continue
        depth = 0
        end = -1
        in_string = False
        escape = False
        for j in range(i, len(text)):
            c = text[j]
            if escape:
                escape = False
                continue
            if c == "\\":
                escape = True
                continue
            if c == '"':
                in_string = not in_string
                continue
            if in_string:
                continue
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    end = j
                    break
        if end > i:
            candidate = text[i : end + 1]
            if _is_target_json(candidate, target_keys):
                return candidate

    # 回退：正则查找包含目标 key 的简单 JSON 对象（无嵌套）
    for key in target_keys:
        m = re.search(r"\{[^{}]*\"" + re.escape(key) + r"\"[^{}]*\}", text)
        if m:
            return m.group()

    return ""


def extract_answer_from_reasoning(reasoning: str) -> str:
    """从 reasoning 文本中提取最终答案（适用于答案合成场景）。

    查找"最终答案："、"答："等标记后的内容，找不到时返回原文。
    对齐 Java LlmClient.extractAnswerFromReasoning。
    """
    if not reasoning or not reasoning.strip():
        return ""
    markers = [
        "最终答案：", "最终答案:", "答案：", "答案:", "答：", "答:",
        "## 回答", "## 回答内容", "## 结论",
        "Final Answer:", "Final Answer：", "Answer:", "Answer：",
    ]
    # 使用 lastIndexOf 语义（Java 实现），取最后一个标记
    best_idx = -1
    best_marker = ""
    for marker in markers:
        idx = reasoning.rfind(marker)
        if idx > best_idx:
            best_idx = idx
            best_marker = marker
    if best_idx >= 0:
        candidate = reasoning[best_idx + len(best_marker) :].strip()
        if candidate:
            return candidate
    return reasoning


# ------------------------------------------------------------------
# 内部辅助
# ------------------------------------------------------------------

def _is_target_json(candidate: str, target_keys: tuple[str, ...]) -> bool:
    try:
        obj = json.loads(candidate)
        if isinstance(obj, dict):
            return any(k in obj for k in target_keys)
    except Exception:
        pass
    return False
