---
name: financial-filing-retrieve
description: 从财报知识库检索与问题相关的原文片段（[filing-stated cause] 数据源），支持 milvus 与 ragflow 两种后端；按公司 ticker、财年、form_type、内容分类过滤；不传分类时根据关键词自动推断。触发词：财报原文、财报知识库、管理层讨论、风险因素、[filing-stated cause]
---

# Financial Filing Retrieve Skill

**从财报知识库拉取原文片段**（替代 Java 侧 `io.invest.iagent.service.kb.backend.KnowledgeBaseBackend#retrieve`）。preprocess / build / list / delete 仍由 Java 侧 `FilingKnowledgeBaseService` 承担，Agent 主流程不再调用。

## 应用场景

Agent 回答"业绩下滑原因/管理层解释/风险因素"这类问题时，`[filing-stated cause]` 层的引用必须来自可核验的财报原文。本 skill 就是这一层数据源：

- 输入：`--query "经营利润下滑原因"`、`--ticker BABA`、可选 `--fiscal-year / --form-type / --category`
- 输出：JSON 结构的 chunks 列表，每条含 `chunkId / sectionTitle / text / score / documentId / formType / fiscalYear`，可直接作为 `[filing-stated cause][C1]` 的引用。

**禁止用途**：本 skill 返回的数字不得进入 `[fact]` 层。`[fact]` 只能来自 `financial-metrics-query` skill。

## 后端

`config/retrieve.json` 里的 `backend` 决定实现：

| 后端 | 检索路径 | 说明 |
|---|---|---|
| `ragflow` | `POST /api/v1/retrieval`（服务端做向量+关键字混合排序） | 推荐；chunk / embedding / 重排全部在 RAGFlow 侧完成 |
| `milvus` | Ollama/OpenAI embedding → `POST /v2/vectordb/entities/search` | 简化实现：仅纯向量召回后按相似度取前 K，未做 BM25F 多轴重排 |

两条路径都会在检索前做本地"分类推断"：`--category` 未提供时用关键词规则映射（risk→operating_risks，利润/收入→financial_operations 等），与 Java `FilingQueryCategoryResolver` 完全一致。

## 目录结构

```
workspace/skills/financial-filing-retrieve/
├── SKILL.md
├── config/
│   └── retrieve.json               # 后端 + 各端点配置（本地默认与 application.properties 对齐）
└── scripts/
    ├── retrieve.py                 # 唯一入口
    ├── _common.py                  # 配置加载、分类推断、公共工具
    ├── _ragflow.py                 # RAGFlow 后端实现
    ├── _milvus.py                  # Milvus + embedding 后端实现
    └── requirements.txt            # requests
```

## 前置准备

```bash
pip install -r workspace/skills/financial-filing-retrieve/scripts/requirements.txt
```

## 用法

```bash
# 最简：BABA 关于经营利润变化的原文片段
python workspace/skills/financial-filing-retrieve/scripts/retrieve.py \
    --query "经营利润变化原因" --ticker BABA --top-k 5

# 指定分类为管理层讨论
python workspace/skills/financial-filing-retrieve/scripts/retrieve.py \
    --query "云业务展望" --ticker BABA --category business_operations --top-k 5

# 加过滤：只看 20-F、FY2024
python workspace/skills/financial-filing-retrieve/scripts/retrieve.py \
    --query "风险因素" --ticker BABA \
    --form-type 20-F --fiscal-year 2024 --category operating_risks

# 覆盖后端（默认从 config/retrieve.json 读取）
python workspace/skills/financial-filing-retrieve/scripts/retrieve.py \
    --query "经营利润下滑" --ticker BABA --backend ragflow --top-k 5
```

### 参数

| 参数 | 说明 | 默认 |
|---|---|---|
| `--query` | 检索问题 | 必填 |
| `--ticker` | 股票代码（自动 upper） | 必填 |
| `--top-k` | 返回条数 | 5（config.resultTopK） |
| `--fiscal-year` | 财年过滤，例如 `2024` | 不限 |
| `--form-type` | 表单类型，例如 `10-K / 20-F / 6-K` | 不限 |
| `--category` | 内容分类；不传则按 query 关键词自动推断 | 自动 |
| `--use-summary-candidates` | Milvus 后端：先在 chunk_summary 候选里召回再取原文；仅当 KB 构建时开启了 summary 才有效 | false |
| `--backend` | 覆盖 config.backend；`milvus` / `ragflow` | config |
| `--config` | 配置文件路径 | `config/retrieve.json` |
| `--pretty` | 缩进输出 | 关闭 |

### 内容分类

与 Java `FilingContentCategory` 对齐：`financial_statements / financial_metrics / business_operations / financial_operations / operating_risks / governance_legal / market_strategy / esg_human_capital / other`。

## 输出格式

```json
{
  "success": true,
  "query": "经营利润变化原因",
  "ticker": "BABA",
  "top_k": 5,
  "backend": "ragflow",
  "category": "financial_operations",
  "inferred_category": "financial_operations",
  "results": [
    {
      "chunk_id": "chunk_20240630_0031",
      "score": 0.7823,
      "text": "本季度经营利润同比下降 3.4%，主要受...影响",
      "ticker": "BABA",
      "document_id": "BABA_20240630",
      "form_type": "20-F",
      "fiscal_year": 2024,
      "fiscal_period": "Q1",
      "filing_date": "2024-08-15",
      "source_file_name": "baba_20f_20240630.pdf",
      "section_title": "管理层讨论与分析",
      "category": "financial_operations",
      "citation": "BABA BABA_20240630 baba_20f_20240630.pdf",
      "metadata": {"similarity": 0.7823, "vector_similarity": 0.71, "term_similarity": 0.5}
    }
  ],
  "count": 1,
  "message": "检索到 1 条相关内容"
}
```

无结果 / 未建库时 `results=[]`，并在 `message` 中说明；`success=true`。真正调用失败时 `success=false, error=...`，退出码 2。

## Agent 集成建议

`companyFilingQaAgent` sysPrompt 里的 `[filing-stated cause]` 数据源改为本 skill：

```
- financial-filing-retrieve skill (retrieve.py) → [filing-stated cause] 原文片段
  调用：python workspace/skills/financial-filing-retrieve/scripts/retrieve.py --query <问题> --ticker <TKR> [--category ...] [--fiscal-year ...]
```

Java 侧 `retrieve_filing_kb / build_filing_kb / list_filing_kb / preprocess_filing_kb / delete_filing_kb` 已从 agent 工具集中移除；构建、删除、列表操作若需要在运维流程中触发，请通过 `FilingKnowledgeBaseService` bean 调用，不再由 Agent 决定。

## Java 端状态

- **已删除**：`FilingKnowledgeBaseTool` 以及 `KnowledgeBaseBackend.retrieve` 相关实现（含 `MilvusKnowledgeBaseBackend` 的 BM25F 重排链路、`RagflowKnowledgeBaseBackend` 的 retrieve/toChunkDTO）。
- **保留**：`FilingKnowledgeBaseService` / `MilvusKnowledgeBaseBackend` / `RagflowKnowledgeBaseBackend` 的 preprocess / build / list / delete；这些仍作为独立服务，供批处理、运维 API 或后台任务调用。
