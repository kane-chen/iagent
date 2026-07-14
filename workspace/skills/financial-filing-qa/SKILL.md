---
name: financial-filing-qa
description: 基于财报RAG索引回答关于财报原文的问题，支持milvus与ragflow两种后端；支持按公司、时间段、财务主题检索，返回带引用编号的中文答案。触发词：财报问答、为什么、下滑原因、业务来源、财报里怎么说、EBITA变化、收入增长、利润变动、管理层讨论、risk factors
---

# Financial Filing QA Skill

**基于已构建的财报RAG索引回答问题**。

## 应用场景

当用户询问具体财报内容时——例如"阿里巴巴国内零售业务2025Q1 EBITA下滑原因"、"腾讯云业务收入增长情况"——使用本 skill 获取答案。输出为带 `[Cn]` 编号引用的中文回答。

## 使用方式

```bash
# 需要索引已构建（通过 filing_qa_build tool 或 Java API）
python workspace/skills/financial-filing-qa/scripts/qa.py \
  --question "携程2025Q3住宿业务收入" --ticker TCOM --mode answer
```

> ⏱️ **超时约定（必读）**：本 skill 单次执行链路包含 `ensure_processed`（首次触发时切片入库，可能 1–3 分钟）+ LLM 关键词改写 + BM25F 检索 + LLM 语义重排 + LLM 生成中文答案，**总耗时通常在 60–300 秒**，未处理过的公司首次调用可能 5–6 分钟。**通过 `execute_shell_command` 调用时，`timeout` 参数必须显式设为 `600`（秒），禁止使用 30/60/120 等默认值**。超时导致的失败不代表 skill 无结果，请**用更大的 timeout 重试**，不要缩短 `--question` 或改换脚本。

## 参数

| 参数 | 必填 | 说明 |
|---|---|---|
| `--question` | ✓ | 问题文本 |
| `--ticker` | ✓ | 股票代码，例如 BABA / TCOM / 00700 |
| `--from-period` | | 起始报告期，如 2024Q1 / FY2024 / 2024 |
| `--to-period` | | 结束报告期 |
| `--form-type` | | 报告类型：FY / Q1 / H1 等 |
| `--mode` | | `search`(返回chunks JSON) / `answer`(中文回答+引用)，默认 answer |
| `--top-k` | | 返回片段数，默认 5 |
| `--backend` | | 覆盖 backend：milvus / ragflow / textsearch |
| `--workspace` | | workspace 根目录路径，默认从环境变量 `IAGENT_WORKSPACE_DIR` 或脚本位置推导 |
| `--pretty` | | JSON 输出时缩进 |
| `--json` | | answer 模式下也输出完整 JSON（含 answer + citations） |

## 后端

配置 `config/qa.json`：

```json
{
  "backend": "milvus",
  "topK": 5,
  "similarityThreshold": 0.3,
  "milvus": { "endpoint": "http://127.0.0.1:19530", "token": "", "collection": "invest_filing" },
  "ragflow": { "baseUrl": "http://localhost:9380", "apiKey": "", "datasetPrefix": "filing_rag_", "similarityThreshold": 0.3, "keywordWeight": 0.3 },
  "embedding": { "baseUrl": "http://localhost:11434/api/embed", "apiKey": "local", "model": "qwen3-embedding:4b", "dimension": 2560 },
  "llm": { "baseUrl": "http://localhost:11434/v1", "apiKey": "local", "model": "qwen3.5:4b", "temperature": 0.2, "maxTokens": 2048 },
  "textsearch": { "rerankTopN": 15, "fullTextMaxChunks": 50, "fullTextFallback": true, "minKeywordScore": 0.01 }
}
```

环境变量覆盖：`IAGENT_FILING_RAG_BACKEND`, `RAGFLOW_API_KEY`, `IAGENT_MILVUS_ENDPOINT`, `IAGENT_EMBEDDING_BASE_URL`, `IAGENT_LLM_BASE_URL`, `IAGENT_LLM_MODEL`。

## 索引构建

索引必须先用 Java 侧的 `filing_qa_build` tool 或单元测试构建；本 skill 不负责切片/入库。

## 目录结构

```
workspace/skills/financial-filing-qa/
├── SKILL.md
├── config/qa.json
└── scripts/
    ├── qa.py           # ✅ 唯一 CLI 入口
    ├── _common.py      # (私有 helper) 配置、周期解析
    ├── _ollama.py      # (私有 helper) Ollama embed + chat
    ├── _milvus.py      # (私有 helper) Milvus 检索
    ├── _ragflow.py     # (私有 helper) RAGFlow 检索
    └── _textsearch.py  # (私有 helper) 文本检索（BM25F + 词典 + LLM sufficiency）
```

> ⚠️ **只允许 `python qa.py ...`**。所有 `_` 前缀文件都是内部模块，直接以 shell 命令执行会被拒绝（exit 2 + 明确错误提示）。**切换后端请用 `qa.py --backend milvus|ragflow|textsearch`，不要直接跑 `_milvus.py` / `_ragflow.py` / `_textsearch.py`。**

## 反模式（不要这样做）

1. **不要**直接执行 `python .../scripts/_milvus.py`、`_ragflow.py`、`_textsearch.py`——这些是被 `qa.py` import 的内部模块，直接执行现在会退出码 2。**要**切后端就用 `qa.py --backend <milvus|ragflow|textsearch>`。
2. **不要**假设"exit code 0 且 stdout 为空"表示"没检索到结果"——真实入口 `qa.py` 即使没找到也会输出 JSON。stdout 完全为空意味着调用了错误的脚本。
3. **不要**通过 `read_file` 读 `large_tool_results/*.jsonl` 来"回看之前的工具输出"——这些是 harness 的内部落盘缓存，直接重新调用对应工具（同参数即可）。

## 前置准备

```bash
pip install requests
```
