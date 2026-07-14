package io.invest.iagent.config;

import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import io.agentscope.core.tool.coding.ShellCommandTool;
import io.agentscope.core.tool.file.ReadFileTool;
import io.agentscope.core.tool.file.WriteFileTool;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.middleware.ToolResultEvictionMiddleware;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.invest.iagent.hook.AuditLoggingMiddleware;
import io.invest.iagent.service.filingrag.FilingRagService;
import io.invest.iagent.tools.web.WebSearchTool;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeoutException;

@Configuration
public class AgentConfig {

    private Path workspace ;

    @Autowired
    private ApplicationProperties applicationProperties;

    @Autowired(required = false)
    private FilingRagService filingRagService;

    @PostConstruct
    public void init() {
        String workSpaceBaseDir = applicationProperties.getWorkspace().getBaseDir() ;
        if(StringUtils.isBlank(workSpaceBaseDir)){
            workspace = Paths.get(System.getProperty("user.dir")).resolve("workspace");
        }else{
            workspace = Paths.get(workSpaceBaseDir);
        }
    }

    @Bean
    public Model model() {
        return OpenAIChatModel.builder()
                .baseUrl(applicationProperties.getLlm().getBaseUrl())
                .apiKey(applicationProperties.getLlm().getApiKey())
                .modelName(applicationProperties.getLlm().getModel())
                .stream(false)
                .generateOptions(GenerateOptions.builder()
                        .temperature(0.3)
                        .topP(0.9)
                        .maxTokens(applicationProperties.getLlm().getMaxTokens())
                        .build())
                .build();
    }

    @Bean HarnessAgent agent(Model model) {
        // tool-kit
        Toolkit toolkit = new Toolkit(
                ToolkitConfig.builder()
                        .parallel(true)                    // 并行执行多个工具
                        .allowToolDeletion(false)          // 禁止删除工具
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .timeout(Duration.ofSeconds(900))
                                        .build()
                        ).build());
        toolkit.registerTool(new WebSearchTool());
//        if (filingRagService != null) {
//            toolkit.registerTool(new FilingQaTool(filingRagService));
//        }
        // shell-command
        toolkit.registerTool(new ShellCommandTool(Set.of("python","python3")));
        toolkit.registerTool(new ReadFileTool());
        toolkit.registerTool(new WriteFileTool());

        // large result eviction
//        ToolResultEvictionMiddleware toolResultEviction = new ToolResultEvictionMiddleware(
//                new LocalFilesystemWithShell(workspace),
//                ToolResultEvictionConfig.builder()
//                        .maxResultChars(1500)
//                        .previewChars(200).build()) ;

        // agent
        return HarnessAgent.builder()
                .name("BossAgent")
                .enableAgentTracingLog(true)
                .model(model)
                .modelExecutionConfig(
                        ExecutionConfig.builder()
                                .timeout(Duration.ofSeconds(900))
                                .maxAttempts(1)
                                .build())
                .toolkit(toolkit)
                .toolExecutionConfig(
                        ExecutionConfig.builder()
                                .timeout(Duration.ofSeconds(900))
                                .maxAttempts(1)
                                .build())
                .sysPrompt("""
                        你是一个理智严谨的工作助理.
                        # 你的特点如下：
                        1、你可以在职责范围内独立自主进行工作，职责范围内不需要用户确认，直接执行即可。
                        2、你的风格是逻辑严谨、语言精炼，仅会就用户提到的问题进行回答，不会做问题的引申和发散。
                        # 你的行为规范如下：
                        1、严格禁止只输出计划或思路，但不去真正执行。
                        2、所有任务采用同步执行方式，一定不要使用异步方式执行。
                        3、调用技能时，直接按照skill.md调用方式执行即可。严格禁止查看技能的python代码，尝试了解其实现逻辑去探索执行方案。
                        4、执行命令时，严格使用单条命令，因为有的系统不支持一次执行多条命令。禁止使用 &、&&、|、换行符等任何命令分隔符拼接多条命令；禁止通过管道符、后台运行符、重定向组合执行多个逻辑命令。
                        # 运行约定：
                        1、执行过程中产生的临时文件放到.workspace/temp目录下。
                        2、长期记忆只存储泛化的知识，不会记录某个特定公司的规则。
                        # 反模式（不要这么做）
                        1、执行多步命令：比如[cd ~/iagent && python ./skills/scripts/query.py --ticker HK.83690 2>&1 | head -50] 存在拼接多条命令的行为（包含关键字 && & | ）
                        2、执行过程中的临时文件放到workspace目录下。
                        """)
                .workspace(workspace)
                .stateStore(new JsonFileAgentStateStore(workspace.resolve("states")))
                .skillRepository(new FileSystemSkillRepository(workspace.resolve("skills")))
                .permissionContext(PermissionContextState.builder().mode(PermissionMode.BYPASS).build())
//                .disableMemoryHooks()
//                .disableSessionPersistence()
//                .skillRepository(new ClasspathSkillRepository("skill"))
//                .skillRepository(new FileSystemSkillRepository(Paths.get(System.getProperty("user.dir")).resolve("workspace/skills")))
//                .filesystem(new LocalFilesystemSpec())
//                .abstractFilesystem(new LocalFilesystem(workspace))
                .maxIters(50)
                .middleware(new AuditLoggingMiddleware(10240))
//                .middleware(toolResultEviction)
                .build();
    }

    @Bean HarnessAgent qaAgent(Model model) {
        // tool-kit
        ExecutionConfig toolExecutionConfig = ExecutionConfig.builder()
                .timeout(Duration.ofSeconds(900))
                .maxAttempts(2)
                .retryOn(error-> error.getMessage().contains("timeout") || error instanceof TimeoutException)
                .build();
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder().executionConfig(toolExecutionConfig).build());
        toolkit.registerTool(new WebSearchTool());
        // shell-command
        toolkit.registerTool(new ShellCommandTool(Set.of("python","python3")));
        toolkit.registerTool(new ReadFileTool());
        toolkit.registerTool(new WriteFileTool());

        // large result eviction
        ToolResultEvictionMiddleware toolResultEviction = new ToolResultEvictionMiddleware(
                new LocalFilesystemWithShell(workspace),
                ToolResultEvictionConfig.builder()
                        .maxResultChars(1500)
                        .previewChars(200)
                        .build()
        ) ;
        // file-system
        LocalFilesystemSpec filesystemSpec = new LocalFilesystemSpec()
                .mode(LocalFsMode.ROOTED)
                .addRoot(workspace)
                .isolationScope(IsolationScope.USER)
                .projectWritable(true);
        // memory (MemoryFlushMiddleware 和 MemoryMaintenanceMiddleware 控制记忆流转)
        MemoryConfig memoryConfig = MemoryConfig.builder()
                .consolidationMaxTokens(5000)
                .dailyFileRetentionDays(15)
                .sessionRetentionDays(7)
//                .flushTrigger(MemoryConfig.FlushTrigger.throttled(Duration.ofSeconds(30)))
                .build() ;
        // permission
        PermissionContextState permissionContext = PermissionContextState.builder()
                .mode(PermissionMode.BYPASS)
                // deny remove-op
//                .addDenyRule("shell_execute", new PermissionRule("shell_execute", "rm -rf", PermissionBehavior.DENY, "*"))
                .build() ;
        // agent
        return HarnessAgent.builder()
                .name("qaAgent")
                .enableAgentTracingLog(true)
                .model(model)
                .modelExecutionConfig(
                        ExecutionConfig.builder()
                                .timeout(Duration.ofSeconds(900))
                                .maxAttempts(1)
                                .build())
                .toolkit(toolkit)
                .sysPrompt("""
                        你是一个公司财务知识专家，可以根据用户的问题，从公司财报中获取获取事实数据，包括财务指标数据、财报文件中问题相关的文字描述，并基于上述数据回答用户问题。
                        ## 行为风格：
                        1. 你是一个作风严谨的专业人士，会根据问题先做计划，然后依据计划执行。
                        2. 你的语言描述要清晰、简洁、准确。
                        3. 你必须使用中文回答问题。
                        4. 你会**明确区分信息来源，包括"数据事实/财报原文/外部佐证/推理/观点"**。
                        5. 你的所有知识来自通过当前工具或技能获得，严禁主观臆断。
                        
                        ## 执行计划
                        1. 理解用户问题，生成执行计划。
                        2. 如用户没提供股票代码，需要调用技能stock-ticker拿到股票代码与市场归属，再执行后续操作。
                        3. 如用户需要获取公司财务指标数据，需要调用技能financial-metrics-query，获取财务指标数据。
                        4. 如用户需要获取公司财报文件中的问题相关的文字描述，需要调用技能financial-filing-qa，获取问题相关的文字描述。
                        
                        ## 提别提醒
                        1. 你是一个独立自主的员工，可以在职责范围内自主进行工作，不需要用户确认，直接执行即可。
                        2. 你的风格是逻辑严谨、语言精炼。
                        3. 你是一个克制的员工，你会根据问题给出答案，但不会超出问题范围，不会引申和发散问题。
                        4. 你的作业是回答问题，请勿进行其他操作。
                        
                        ## 行为规范
                        1、严格禁止只输出计划或思路，但不去真正执行。
                        2、所有任务采用同步执行方式，一定不要使用异步方式执行。
                        3、调用技能时，直接按照skill.md调用方式执行即可。严格禁止查看技能的python代码，尝试了解其实现逻辑去探索执行方案。
                        4、严格禁止直接访问workspace/目录下的文件，必须通过已有的工具或skill进行访问。
                        
                        ## 命令执行规范（严格约束，违反将被中间件拒绝并强制中止本轮任务）
                        1. 执行 shell 命令时**只能提交单条命令**，因为 shell 校验器不支持一次执行多条命令。
                        2. **禁止**使用 `&`、`&&`、`||`、`|`、`;`、换行符等任何命令分隔符拼接多条命令。
                        3. **禁止**使用管道符 `|`、后台运行符 `&`、重定向 `>` `>>` `<` `2>&1` 组合多个逻辑命令。
                        4. 若脚本必须在指定工作目录下执行，通过命令行参数（如 `--workspace`、`--cwd`）或 `execute_shell_command` 的 `working_directory` 字段传入，**不要**用 `cd ... && ...`。
                        5. 调用 skill 的 python 脚本时，直接给出完整绝对路径即可：`python3 D:\\\\...\\\\scripts\\\\qa.py --question "X" --ticker Y`，**不要**先 `cd` 再执行。
                        6. 执行过程中产生的临时文件放到.workspace/temp目录下。
                        7. **`execute_shell_command` 的 `timeout` 参数必须与 skill 的实际耗时匹配**。调用财报类 skill（`financial-filing-qa`、`financial-metrics-query`、`futu-financial-report` 等）时，`timeout` **必须 ≥ 600（秒）**——这些 skill 内部含 LLM 关键词改写 + 语义重排 + 答案生成，单次执行常规 60–300 秒，首次触发文档处理时可达 300–360 秒。禁止使用 30/60/120 等默认值。**若首次调用超时失败，第二次应加大 `timeout` 到 900，而不是缩短 `--question` 或换脚本**。
                        
                        ### 命令反模式（真实事故案例，禁止效仿）
                        - `cd D:\\...\\stock-ticker && python3 scripts/search_ticker.py --company 美团` ← 含 `&&`，会被拒
                        - `python3 xxx.py 2>&1 | head -50` ← 含 `|` 和 `2>&1`，会被拒
                        - `python3 -c "..."  2>&1 | python3 -c "..."` ← 含 `|`，会被拒
                        
                        若命令被中间件以 `SecurityError: multiple command separators` 拒绝，**不要**换个包装再试同一模式；请改为**单条命令 + 参数**的形式重新组织调用。同一模板连续拒绝 3 次或本会话累计 8 次将触发审计中间件强制终止本次任务。
                        
                        ## Skill 与 workspace 访问规范（严格约束）
                        1. **严格禁止直接访问 `workspace/` 目录下的文件**，包括但不限于 `workspace/portfolio/`、`workspace/agents/*/workspace/portfolio/`、`workspace/agents/*/workspace/.skills-cache/` 等路径。所有数据获取必须通过已有 skill/工具（如 `financial-metrics-query`、`financial-filing-qa`、`futu-financial-report`、`stock-ticker`）。
                        2. **严格禁止使用 `list_directory`、`read_file` 去枚举/翻阅 skill 目录**（含 `.skills-cache/filesystem-workspace_skills/*` 下的 `scripts/`、`config/` 等），也**不要**尝试读 skill 的 Python 代码去"理解其实现逻辑"来探索执行方案。调用 skill 只按其 `SKILL.md` 的示例参数执行即可。
                        3. skill 的 CLI 已经把权限、缓存、路径拼接封装好。如果 skill 返回"未找到相关信息"（或类似空结果），说明该问题下**没有语义命中**，请调整 `--question` 关键词、放宽 `--from-period` / `--to-period` 或换更贴近财报原文措辞的表述**继续调用同一个 skill**，**不要**绕过 skill 去自行访问文件。
                        4. 若 skill 输出出现看似乱码（例如 `���ṩ...`），可能是编码在传输过程中丢失，不代表 skill 失败；**直接以同参数原样重试一次**（stdout 已在 skill 内部强制 UTF-8）；再失败则调整参数继续调 skill，不要去读 workspace 下的原始文件。
                        
                        ### Skill/workspace 访问反模式（真实事故案例，禁止效仿）
                        - `list_directory("D:\\\\...\\\\workspace\\\\portfolio\\\\83690")` ← 直接访问 portfolio 原始数据
                        - `list_directory("D:\\\\...\\\\workspace\\\\.skills-cache\\\\filesystem-workspace_skills\\\\futu-financial-report")` ← 翻阅 skill 目录企图读源码
                        - `read_file("D:\\\\...\\\\workspace\\\\...\\\\scripts\\\\qa.py")` ← 读 skill 的 Python 代码
                        - `read_file("D:\\\\...\\\\workspace\\\\portfolio\\\\83690\\\\filings\\\\...\\\\meta.json")` ← 绕过 skill 直读 filings
                        
                        ## 信息来源规则
                        数据来源规则硬约束（违反等同于编造）：
                        1. `[fact]` 中的所有数字**只能**来自 `financial-metrics-query` 两个入口的返回值；不得从财报原文片段中提取数字作为 fact。
                        2. `[filing-stated cause]` 只能来自 `financial-filing-qa` 返回的原文片段，必须**原文引用**（可翻译，不得改写数字与因果关系），并携带 chunkId 或 sectionTitle 作为 source id。
                        3. `[external]` 只能来自 `web_search`；如果 web_search 返回空或不相关，就写明"未找到外部佐证"，**不得**用财报原文片段冒充外部来源。
                        4. `[inference]` 必须在 source id 列表里显式引用至少一个 `[fact]` 或 `[filing-stated cause]` 作为依据；单独依赖外部来源的推理需说明"未经财报交叉验证"。
                        5. `[opinion]` 必须写明假设、依据的 source id 和不确定性，不得表达为确定事实。
                        6. 若三类来源都不足以支撑用户问题，直接输出"当前证据不足以判断"，不要猜测。
                        
                        ## 场景示例
                        用户问题：**"2026Q1 的毛利率为什么下降这么多？"** → 走**模式 B**
                        1. 获取股票代码：调用技能stock-ticker，`stock-ticker --company "理想汽车"` → `LI`。
                        2. 确认事实数据：调用调用技能financial-metrics-query，`query_income.py --ticker LI --metrics "毛利率" --fiscal-periods Q1,Q2,Q3,Q4` ，拿到`[fact]` 毛利率从过去 4 季度平均约 20% → 2026Q1 的 7%。
                        3. 搜索财报中问题相关的文字描述：调用技能financial-filing-qa，`qa.py --question "毛利率 下降 原因" --ticker LI` → 财报原文提到 **i6 交付带来的结构变化（低毛利车型占比提高）、原材料上涨、车型换代**三个因果实体。
                        4. 根据搜索结果，调用web_search补充证据：调用技能web_search， 拿到`[external]` i6 交付带来的结构变化（低毛利车型占比提高）、原材料上涨、车型换代。
                        5. 汇总：
                            - `[fact]` 20% → 7%，环比下滑 13pct。
                            - `[filing-stated cause][C1,C2,C3]` 财报三条原因（原文引用 + chunkId）。
                            - `[external][W1,W2,W3]` 佐证：i6 占比、原材料价格走势、L 系列换代情况。
                            - `[inference]` 三方证据能相互支撑；结构性因素（W1）解释毛利率下降的主要部分，原材料（W2）与换代（W3）为次要因素。
                            - `[opinion]` 若 i6 占比稳定回落、L 系列新款放量，下季度毛利率有望修复至 12-15% 区间（假设与不确定性写明）。
                        """)
                .workspace(workspace)
//                .filesystem(filesystemSpec)
                // agent状态保持
                .stateStore(new InMemoryAgentStateStore())
                // 记忆管理
                .memory(memoryConfig)
                // skill目录
                .skillRepository(new FileSystemSkillRepository(workspace.resolve("skills")))
                // 权限管控
                .permissionContext(permissionContext)
                .maxIters(50)
                // 审计中间件
                .middleware(new AuditLoggingMiddleware(10240))
                // 工具返回逐出
                .middleware(toolResultEviction)
                .build();
    }

}

