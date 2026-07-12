# iagent

## 概述

iagent是基于agentscope构建的Agentic 服务，用于为用户提供投资分析服务。

## 项目架构

### 系统架构: Java Spring Boot + AgentScope HarnessAgent + Python skills

The application is a Spring Boot app that boots a single `HarnessAgent` ("BossAgent") configured in `io.invest.iagent.config.AgentConfig`. The agent is the orchestrator; Java services back its tools, but the bulk of domain work is done by **Python scripts** living under `workspace/skills/<skill-name>/scripts/`. The agent invokes these via a `ShellCommandTool` that only allows `python`/`python3` (configured in `AgentConfig`).

### 代码目录 (`src/main/java/io/invest/iagent/`)

| Package | Purpose                                          |
|---|--------------------------------------------------------|
| `config/` | 应用配置: `AgentConfig`和`ApplicationProperties` |
| `hook/` | Agent Hook，用于拦截Agent的调用                     |
| `tools/` | Agent的工具实现                                   |
| `service/` | 业务逻辑实现                                    |
| `utils/` | 基础工具类                                        |

### Workspace structure (`workspace/`)

This is the agent's working directory (set in `AgentConfig.init()` from `app.workspace.base-dir` or `<user.dir>/workspace`). Layout is the source of truth shared by both Java (`WorkspacePaths`) and Python (`workspace_paths.py`):

```
workspace/
├── agents/<agentId>/AGENTS.md    # Agent persona & config (YAML frontmatter)
├── skills/<skill-name>/          # Skills discovered by AgentScope SkillBox
│   ├── SKILL.md                  # Skill definition (YAML frontmatter + instructions)
│   ├── config/                   # JSON/YAML config (e.g., retrieve.json for KB backend)
│   ├── scripts/*.py              # Python entry points called by the agent via shell_command
│   └── references/, docs/        # Reference docs
├── subagents/<id>.md             # Subagent declarations (filename = agent_id)
├── portfolio/<TICKER>/           # Per-company data (WorkspacePaths)
│   ├── filings/<documentId>/     # Raw SEC/Futunn filings (HTML/PDF) + meta.json
│   ├── materials/<documentId>/   # Other materials
│   └── processed/<documentId>/   # Preprocessing output + meta.json
├── excels/                       # Generated financial Excel files
├── logs/                         # App logs (per logging.file.path in application.yml)
└── temp/                         # Temporary files (safe to clean)
```

## 编码规范

- **Lombok** (`@Data`, `@Builder`, `@Slf4j`, etc.) is preferred over hand-written getters/setters.
- **Utilities** go in `io.invest.iagent.utils` — time, file, HTTP helpers should be extracted there to avoid duplication (DRY).
- **Spring config**: use `@ConfigurationProperties` (see `ApplicationProperties`, `KnowledgeBaseConfig`) rather than `@Value`; separate optional modules with `@ConditionalOnProperty`.
- **Workspace paths**: always resolve via `WorkspacePaths` in Java or `workspace_paths.py` in Python — never hardcode `portfolio/`, `filings/`, etc.
- **Temp files**: put them under `workspace/temp/`.
- **Logging**: use `@Slf4j`; domain-specific loggers use names `filing_download`, `filing_process`, `filing_query` (configured in `application.yml` at DEBUG).
- **Timeouts**: agent model/tool execution is 900 seconds; RAGFlow parse polling defaults to 300s with 3s interval (configurable).
- **Comments**：use Chinese.

## 常用命令

```bash
# Build (skip tests by default — many integration tests need external services)
mvn clean package -DskipTests

# Run the application
mvn spring-boot:run
# or
mvn -DskipTests spring-boot:run

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=SearchEngineTest

# Run a single test method
mvn test -Dtest=SearchEngineTest#testHybridSearch

# Compile only
mvn compile
```

