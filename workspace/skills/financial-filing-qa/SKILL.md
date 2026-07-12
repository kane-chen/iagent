---
name: financial-filing-qa
description: 基于财报RAG索引回答关于财报原文的问题，支持milvus与ragflow两种后端；支持按公司、时间段、财务主题检索，返回带引用编号的中文答案。触发词：财报问答、为什么、下滑原因、业务来源、财报里怎么说、EBITA变化、收入增长、利润变动、管理层讨论、risk factors
---

# Financial Filing QA Skill

**基于已构建的财报RAG索引回答问题**（Java 侧 `FilingRagService` 提供索引构建 `filing_qa_build`；本 skill 只做检索+问答，不做切片/建索引）。

## 应用场景

当用户询问具体财报内容时——例如"阿里巴巴国内零售业务2025Q1 EBITA下滑原因"、"腾讯云业务收入增长情况"——使用本 skill 获取答案。输出为带 `[Cn]` 编号引用的中文回答。

## 使用方式

```bash
# 需要索引已构建（通过 filing_qa_build tool 或 Java API）
python workspace/skills/financial-filing-qa/scripts/qa.py \
  --question "携程2025Q3住宿业务收入" --ticker TCOM --mode answer
```

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
    ├── qa.py           # CLI 入口
    ├── _common.py      # 配置、周期解析
    ├── _ollama.py      # Ollama embed + chat
    ├── _milvus.py      # Milvus 检索
    ├── _ragflow.py     # RAGFlow 检索
    └── _textsearch.py  # 文本检索（BM25F + 词典 + LLM sufficiency）
```

## 前置准备

```bash
pip install requests
```
