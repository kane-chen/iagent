package io.invest.iagent.config;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.SkillHook;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import io.agentscope.core.tool.coding.ShellCommandTool;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tracing.TracerRegistry;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.hook.AgentTraceHook;
import io.invest.iagent.hook.DetailedTracingHook;
import io.invest.iagent.hook.LoggingTracer;
import io.invest.iagent.service.filing.FinancialFilingDownloadService;
import io.invest.iagent.service.filing.FinancialMetricsQueryService;
import io.invest.iagent.service.kb.FilingKnowledgeBaseService;
import io.invest.iagent.service.kb.FilingPreprocessService;
import io.invest.iagent.service.kb.backend.KnowledgeBaseBackend;
import io.invest.iagent.service.kb.backend.MilvusKnowledgeBaseBackend;
import io.invest.iagent.service.kb.backend.RagflowKnowledgeBaseBackend;
import io.invest.iagent.service.kb.backend.ragflow.RagflowClient;
import io.invest.iagent.service.kb.category.FilingQueryCategoryResolver;
import io.invest.iagent.service.kb.embedding.EmbeddingService;
import io.invest.iagent.service.kb.embedding.ModelEmbeddingService;
import io.invest.iagent.service.kb.vector.VectorStoreService;
import io.invest.iagent.service.kb.vector.VectorStoreServiceByMilvus;
import io.invest.iagent.tools.filing.FinancialFilingDownloadTool;
import io.invest.iagent.tools.filing.FinancialMetricsQueryTool;
import io.invest.iagent.tools.filing.FinancialSegmentMetricsTool;
import io.invest.iagent.tools.filing.FinancialTrendAnalysisTool;
import io.invest.iagent.tools.kb.FilingKnowledgeBaseTool;
import io.invest.iagent.tools.web.CompanySourceSearchTool;
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

@Configuration
public class AgentConfig {

    private Path workspace ;

    @Autowired
    private ApplicationProperties applicationProperties;

    @PostConstruct
    public void init() {
        TracerRegistry.register(new LoggingTracer(102400));
        String workSpaceBaseDir = applicationProperties.getWorkspace().getBaseDir() ;
        if(StringUtils.isBlank(workSpaceBaseDir)){
            workspace = Paths.get(System.getProperty("user.dir")).resolve("workspace");
        }else{
            workspace = Paths.get(workSpaceBaseDir);
        }
    }

    @Bean
    public FilingKnowledgeBaseService filingKnowledgeBaseService() {
        String backendType = StringUtils.lowerCase(
                StringUtils.defaultIfBlank(applicationProperties.getKb().getBackend(), "milvus"));
        KnowledgeBaseBackend backend = "ragflow".equals(backendType)
                ? buildRagflowBackend()
                : buildMilvusBackend();
        return new FilingKnowledgeBaseService(backend);
    }

    @Bean("milvusKnowledgeBaseBackend")
    public KnowledgeBaseBackend buildMilvusBackend() {
        ApplicationProperties.EmbeddingProperties embeddingProps = applicationProperties.getEmbedding();
        EmbeddingService embeddingService = new ModelEmbeddingService(
                embeddingProps.getBaseUrl(),
                embeddingProps.getApiKey(),
                embeddingProps.getModel(),
                1024);

        ApplicationProperties.MilvusProperties milvusProps = applicationProperties.getMilvus();
        String token = milvusProps.getToken().isBlank() ? null : milvusProps.getToken();
        VectorStoreService vectorStoreService = new VectorStoreServiceByMilvus(
                milvusProps.getEndpoint(), token, milvusProps.getCollection());

        FilingPreprocessService preprocessService = new FilingPreprocessService(workspace);
        return new MilvusKnowledgeBaseBackend(
                preprocessService,
                embeddingService,
                vectorStoreService,
                new FilingQueryCategoryResolver(),
                applicationProperties);
    }

    @Bean("ragflowKnowledgeBaseBackend")
    public KnowledgeBaseBackend buildRagflowBackend() {
        RagflowClient client = new RagflowClient(applicationProperties.getRagflow());
        return new RagflowKnowledgeBaseBackend(workspace, client, applicationProperties);
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

    @Bean("filingAgent")
    ReActAgent filingAgent(Model model, FilingKnowledgeBaseService filingKnowledgeBaseService) {

        // tool-kit
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new FilingKnowledgeBaseTool(filingKnowledgeBaseService));
        // shell-command (use python to call futu_financial skill scripts on Windows)
        // 注意：Windows 环境不要使用 withShell()，避免内部调用不存在的 sh 导致错误
        // skill-box
        SkillBox skillBox = new SkillBox(toolkit) ;
        skillBox.codeExecution()
                .withRead()
                .withWrite()
                .workDir(workspace.toAbsolutePath().toString())
        ;
        // memory
        AutoContextConfig config = AutoContextConfig.builder()
                .maxToken(applicationProperties.getLlm().getMaxTokens())
                .minCompressionTokenThreshold(14*1024)
                .minConsecutiveToolMessages(10)
                .msgThreshold(20)
                .tokenRatio(0.85)
//                .largePayloadThreshold(3*1024)
                .build();
        PlanNotebook planNotebook = PlanNotebook.builder()
                .needUserConfirm(false)
                .maxSubtasks(6)
                .keyPrefix("SubAgentFilingPlan")
                .build();
        AutoContextMemory memory = new AutoContextMemory(config, model);
        // agent
        return ReActAgent.builder()
                .name("SubAgentFiling")
                .sysPrompt("""
                        你是一个财报文件助理，可以回答用户有关公司财报数据的问题。
                        注意：
                        1、用户如果没有提供公司股票代码，你需要首先运行 stock-ticker skill（python workspace/skills/stock-ticker/scripts/search_ticker.py --company <公司名>）拿到股票代码与市场归属，再执行后续操作。
                        2、不需要检查工作空间的文件，工具接口里自己会检查财报文件，你只需要调用对应的工具即可。
                        3、用户询问财报定性原因、管理层解释、风险因素时，可以使用财报知识库工具检索相关段落；仅在检索结果为空且用户明确需要时才构建知识库。
                        提别提醒：
                        1、你是一个独立自主的员工，可以在职责范围内自主进行工作，不需要用户确认，直接执行即可。
                        2、你的风格是逻辑严谨、语言精炼。
                        3、你是一个克制的员工，仅会就用户提到的问题进行回答，不会引申和发散问题。
                        4、避免重复下载或重复构建已有的知识库，优先复用已有数据。
                        """)
                .model(model)
                .toolkit(toolkit)
                .skillBox(skillBox)
                .memory(memory)
                .planNotebook(planNotebook)
                .maxIters(20)
                .hook(new DetailedTracingHook(102400))
                .hook(new AgentTraceHook())
//                .hook(new SkillHook(skillBox))
                .build();
    }

    @Bean("companyFilingQaAgent")
    ReActAgent companyFilingQaAgent(Model model, FilingKnowledgeBaseService filingKnowledgeBaseService) {
        Toolkit toolkit = new Toolkit();
        // 结构化"事实"入口：分部指标（读 workspace/excels/*.xlsx 中的提取结果）
        toolkit.registerTool(new FinancialSegmentMetricsTool(workspace));
        // 结构化"事实"入口：季度趋势（SEC XBRL + 本地缓存）
        toolkit.registerTool(new FinancialTrendAnalysisTool(workspace, applicationProperties.getFiling().getSecUserAgent()));
        // "原因/管理层解释"入口：财报知识库检索（Milvus 或 RAGFlow 由 app.kb.backend 决定）
        toolkit.registerTool(new FilingKnowledgeBaseTool(filingKnowledgeBaseService));
        // "外部媒体/券商" 入口：白名单域名限定的 web 搜索
        toolkit.registerTool(new CompanySourceSearchTool());
        AutoContextConfig config = AutoContextConfig.builder()
                .maxToken(applicationProperties.getLlm().getMaxTokens())
                .minCompressionTokenThreshold(14*1024)
                .minConsecutiveToolMessages(10)
                .msgThreshold(20)
                .tokenRatio(0.85)
                .build();
        AutoContextMemory memory = new AutoContextMemory(config, model);
        PlanNotebook planNotebook = PlanNotebook.builder()
                .needUserConfirm(false)
                .maxSubtasks(10)
                .keyPrefix("companyFilingQaPlan")
                .build();
        return ReActAgent.builder()
                .name("CompanyFilingQaAgent")
                .sysPrompt(companyFilingQaAgentPrompt())
                .model(model)
                .toolkit(toolkit)
                .memory(memory)
                .planNotebook(planNotebook)
                .maxIters(20)
                .hook(new DetailedTracingHook(102400))
                .hook(new AgentTraceHook())
                .build();
    }

    @Bean HarnessAgent agent(Model model, ReActAgent filingAgent, ReActAgent companyFilingQaAgent) {
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
        // shell-command (python is allowed for futu_financial skill)
        ShellCommandTool shellCommandTool = new ShellCommandTool(Set.of("python","python3"));
        toolkit.registerTool(shellCommandTool);
        SkillBox skillBox = new SkillBox(toolkit) ;
        skillBox.codeExecution()
                .withShell(shellCommandTool)
                .withRead()
                .withWrite()
                .workDir(workspace.toAbsolutePath().toString());
        // sub-agent
        toolkit.registration()
                .subAgent(()-> filingAgent, SubAgentConfig.builder().toolName("filingAgent-sub").description("下载财报文件、维护财报知识库；不做业绩分析与观点判断").build())
                .subAgent(()-> companyFilingQaAgent, SubAgentConfig.builder().toolName("companyFilingQaAgent-sub").description("公司财报问答与业绩分析：季度趋势、变动原因、管理层解释、外部佐证、推理与前瞻观点；所有结论按 [fact]/[filing-stated cause]/[external media report]/[broker research]/[inference]/[opinion] 分层标注来源").build())
                .apply();
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
                        你是一个理智严谨的工作助理，你的特点如下：
                        1、你可以在职责范围内独立自主进行工作，职责范围内不需要用户确认，直接执行即可。
                        2、你的风格是逻辑严谨、语言精炼，仅会就用户提到的问题进行回答，不会做问题的引申和发散。

                        分诊规则（严格遵守，不要试图自己去数字化分析财报）：
                        3、当用户询问近期事件、新闻、赛程、价格、政策或需要互联网实时信息时，优先调用 web_search 工具。
                        4、当用户询问公司财报、最近几个季度收入/成本/运营损益、同比趋势、业绩变化原因、管理层解释、未来展望/预测/观点时，必须调用 companyFilingQaAgent-sub，把用户原问题原样转交，等待其结构化答复。
                        5、当用户要求下载财报或重建财报知识库时，调用 filingAgent-sub。
                        6、companyFilingQaAgent-sub 返回的分层标注（[fact]/[filing-stated cause]/[external media report]/[broker research]/[inference]/[opinion]）与 Sources 列表必须原样保留，不得改写或合并来源。
                        """)
                .workspace(workspace)
                .disableMemoryHooks()
                .disableSessionPersistence()
//                .skillRepository(new ClasspathSkillRepository("skill"))
//                .skillRepository(new FileSystemSkillRepository(Paths.get(System.getProperty("user.dir")).resolve("workspace/skills")))
//                .filesystem(new LocalFilesystemSpec())
//                .abstractFilesystem(new LocalFilesystem(workspace))
                .maxIters(50)
                .hook(new DetailedTracingHook(102400))
                .hook(new AgentTraceHook())
                .hook(new SkillHook(skillBox))
                .build();
    }

    public String companyFilingQaAgentPrompt() {
        return """
                你是公司财报问答与业绩分析助理，负责回答公司财报、经营变化、业绩趋势、原因解释和前瞻观点相关问题。

                可用工具与分工（严格遵守，禁止越权）：
                - analyze_financial_trends            → 结构化"事实"来源之一：最近 N 季度收入/成本/运营利润/亏损、YoY，用于产出 [fact]。
                - query_financial_segment_metrics     → 结构化"事实"来源之二：分部（segment）收入、EBITA 等已提取到 Excel 的分部指标，用于产出 [fact]。
                - retrieve_filing_kb                  → 财报正文片段检索，用于产出 [filing-stated cause]；返回内容作为原因依据，禁止从中回填任何数字。
                - search_media_reports                → 白名单媒体检索，用于产出 [external media report]。
                - search_broker_research              → 白名单券商检索，用于产出 [broker research]；未配置白名单时会 fail-closed，视为"未找到"。
                - build_filing_kb / list_filing_kb    → 仅当 retrieve_filing_kb 因 "知识库尚未建立" 而无结果时，才调用 build_filing_kb 触发一次构建，然后重试检索。

                数据源硬约束（违反等同于编造）：
                1、[fact] 中的所有数字，只能来自 analyze_financial_trends 或 query_financial_segment_metrics 的返回值；不得从 retrieve_filing_kb 的原文片段中提取数字作为 fact。
                2、[filing-stated cause] 只能来自 retrieve_filing_kb 返回的原文片段，必须原文引用（可翻译，不得改写数字与因果关系），并携带 chunkId 或 sectionTitle 作为 source id。
                3、[external media report] 只能来自 search_media_reports；[broker research] 只能来自 search_broker_research。任一 web 搜索返回空或未配置，就写明"未找到"，不得用 retrieve_filing_kb 的结果冒充外部来源。
                4、[inference] 必须在 source id 列表里显式引用至少一个 [fact] 或 [filing-stated cause] 作为依据；单独引用外部来源做出的推理需说明"未经财报交叉验证"。
                5、[opinion] 必须写明假设、依据的 source id 和不确定性，不得表达为确定事实。
                6、若三类来源都不足以支撑用户问题，直接输出"当前证据不足以判断"，不要猜测。

                执行模式：
                1、遇到需要多个来源、多个季度或同时包含事实/原因/观点的问题时，先用 create_plan 建立一次性执行计划，按子任务批量收集"事实-原因-外部-观点"四类证据，再统一综合回答。
                2、创建计划后直接执行，不要等待用户确认；每完成一个子任务就更新状态，最后调用 finish_plan 结束计划。
                3、工具调用前先合并同类需求：季度趋势一次性使用 analyze_financial_trends，分部数据一次性使用 query_financial_segment_metrics，财报原因一次性 retrieve_filing_kb（可按 category 参数分层），媒体/券商仅在需要外部佐证时调用，避免反复问答式试探。
                4、若用户明确问"最近 N 个季度趋势"，必须先出 analyze_financial_trends 的表格，再解释原因，再补外部佐证，最后给推理和观点，顺序不可颠倒。
                5、Sources 列表中的每一条必须能追溯到具体工具调用（例如 F1=analyze_financial_trends#Revenue-FY2024，C1=retrieve_filing_kb#chunk_xxx，M1=search_media_reports#reuters.com/xxx）。

                标准输出结构：
                ## 结论
                - [fact][F1] ...
                - [filing-stated cause][C1] ...
                - [external media report][M1] ...
                - [broker research][B1] ...
                - [inference][F1,C1] ...
                - [opinion][F1,C1,M1] ...

                ## 最近N个季度数据
                | 财期 | 收入 | YoY | 成本 | YoY | 运营利润/亏损 | YoY | 来源 |

                ## 变化原因
                ## 后续发展观点
                ## Sources
                ## 限制
                """;
    }


    @Bean HarnessAgent baseAgent(Model model) {
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
        toolkit.registerTool(new FinancialSegmentMetricsTool(workspace));
        // shell-command
        ShellCommandTool shellCommandTool = new ShellCommandTool(Set.of("python","python3"));
        toolkit.registerTool(shellCommandTool);
        SkillBox skillBox = new SkillBox(toolkit) ;
        skillBox.codeExecution()
                .withShell(shellCommandTool)
                .withRead()
                .withWrite()
                .workDir(workspace.toAbsolutePath().toString());
        // agent
        return HarnessAgent.builder()
                .name("BaseAgent")
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
                        你是一个理智严谨的工作助理，你的特点如下：
                        1、你可以在职责范围内独立自主进行工作，职责范围内不需要用户确认，直接执行即可。
                        2、你的风格是逻辑严谨、语言精炼，仅会就用户提到的问题进行回答，不会做问题的引申和发散。
                        """)
                .workspace(workspace)
                .disableMemoryHooks()
                .disableSessionPersistence()
                .disableSubagents()
                .disableMemoryTools()
                .maxIters(50)
                .hook(new DetailedTracingHook(102400))
                .hook(new AgentTraceHook())
                .hook(new SkillHook(skillBox))
                .build();
    }

}

