# workspace 

## 目录结构

```
workspace/                           ← 默认 .agentscope/workspace
├── AGENTS.md                        ← 人格 / 行为约定（每次注入全文）
├── MEMORY.md                        ← 整理过的长期记忆（每次注入，受 token 预算）
├── knowledge/
│   ├── KNOWLEDGE.md                 ← 领域知识入口
│   └── *                            ← 其他参考文件，按需 read_file 打开
├── memory/
│   ├── YYYY-MM-DD.md                ← 每日记忆流水账（追加，由 MemoryFlushManager 写入）
│   └── .consolidation_state         ← MemoryConsolidator 内部状态
├── skills/<skill-name>/SKILL.md     ← 自定义技能
├── subagents/<id>.md                ← 子 agent 声明（文件名=agent_id，自动发现）
└── agents/<agentId>/
    ├── workspace/                   ← isolated 子 agent 的运行时根（无 workspace.path 时自动创建）
    └── sessions/
        ├── sessions.json            ← 会话索引（id / summary / updatedAt）
        ├── <sessionId>.jsonl        ← LLM 可见的压缩上下文
        └── <sessionId>.log.jsonl   ← 完整对话日志（追加）
```

## 配置模板

### subAgent

```yaml
---
description: Code review expert           # 【必填】子 agent 的描述，编排者据此决定何时委派
workspace:
  mode: isolated                          # isolated | shared，默认 isolated
  path: ./defs/reviewer                   # 可选；定义工作空间目录路径（相对 mainWorkspace 或绝对路径）
model: openai:gpt-4o-mini                 # 可选；模型覆盖，为空则继承父 agent 模型
steps: 8                                  # 可选；最大推理轮数，默认 10（旧字段 maxIters 仍兼容）
temperature: 0.7                          # 可选；采样温度覆盖
top_p: 0.9                                # 可选；nucleus 采样覆盖（支持 top_p 和 topP 两种写法）
variant: thinking                         # 可选；模型变体标识（如 DashScope 的 thinking 模式）
mode: subagent                            # primary | subagent | all，默认 all
hidden: false                             # 可选；是否对 LLM 隐藏此子 agent，默认 false
expose_to_user: true                      # 可选；三态布尔：true=始终暴露 / false=永不暴露 / 不设置=按调用决定
tools: [read_file, grep_files, edit_file] # 可选；工具白名单，为空则继承父 agent 全部工具
---
 
You are a subagent focused on code review.   # Markdown 正文，作为内联 system prompt
```