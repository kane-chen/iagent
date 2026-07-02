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
import io.invest.iagent.service.kb.category.FilingQueryCategoryResolver;
import io.invest.iagent.service.kb.embedding.EmbeddingService;
import io.invest.iagent.service.kb.embedding.ModelEmbeddingService;
import io.invest.iagent.service.kb.vector.VectorStoreService;
import io.invest.iagent.service.kb.vector.VectorStoreServiceByMilvus;
import io.invest.iagent.tools.*;
import io.invest.iagent.tools.filing.FinancialFilingDownloadTool;
import io.invest.iagent.tools.filing.FinancialMetricsQueryTool;
import io.invest.iagent.tools.filing.FinancialSegmentMetricsTool;
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
        ApplicationProperties.EmbeddingProperties embeddingProps = applicationProperties.getEmbedding();
        String baseUri = embeddingProps.getBaseUrl();
        String apiKey = embeddingProps.getApiKey();
        String model = embeddingProps.getModel();
        EmbeddingService embeddingService = new ModelEmbeddingService(baseUri, apiKey, model, 1024);

        // vector-store
        ApplicationProperties.MilvusProperties milvusProps = applicationProperties.getMilvus();
        String endpoint = milvusProps.getEndpoint();
        String token = milvusProps.getToken().isBlank() ? null : milvusProps.getToken();
        String collectionName = milvusProps.getCollection();
        VectorStoreService vectorStoreService = new VectorStoreServiceByMilvus(endpoint, token, collectionName);

        // 创建带有财务报表提取功能的预处理服务
        FilingPreprocessService preprocessService = new FilingPreprocessService(workspace);

        return new FilingKnowledgeBaseService(
                preprocessService,
                embeddingService,
                vectorStoreService,
                new FilingQueryCategoryResolver(),
                applicationProperties
        );
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
        toolkit.registerTool(new StockInfoTool());
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
                        1、用户如果没有提供公司股票代码，你需要首先调用工具获取股票代码，然后再执行后续的操作。
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
        toolkit.registerTool(new StockInfoTool());
        toolkit.registerTool(new FilingKnowledgeBaseTool(filingKnowledgeBaseService));
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
                .subAgent(()-> filingAgent, SubAgentConfig.builder().toolName("filingAgent-sub").description("回答有关公司财报数据的问题").build())
//                .subAgent(()-> companyFilingQaAgent, SubAgentConfig.builder().toolName("companyFilingQaAgent-sub").description("回答公司财报、业绩趋势、变动原因、预测和观点类问题，所有结论必须标注来源类型").build())
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
                        """)
//        3、当用户询问近期事件、新闻、赛程、价格、政策或需要互联网实时信息时，优先调用web_search工具。
//        4、当用户询问公司财报、最近几个季度收入/成本/运营损益、同比趋势、业绩变化原因、管理层解释、未来展望/预测/观点时，优先调用companyFilingQaAgent-sub。
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

                执行模式：
                1、遇到需要多个来源、多个季度或同时包含事实/原因/观点的问题时，先用 create_plan 建立一次性执行计划，按子任务批量收集数据、原因和外部来源，再统一综合回答。
                2、创建计划后直接执行，不要等待用户确认；每完成一个子任务就更新状态，最后调用 finish_plan 结束计划。
                3、工具调用前先合并同类需求：季度趋势一次性使用 analyze_financial_trends，财报原因一次性检索 retrieve_filing_kb，外部媒体/券商来源按必要性调用，避免反复问答式试探。

                强制规则：
                1、所有回答必须基于来源，不得编造数字、原因、新闻、券商观点或预测。
                2、每个实质性结论必须标注以下标签之一：[fact]、[filing-stated cause]、[external media report]、[broker research]、[inference]、[opinion]。
                3、[fact] 只能来自财报明确披露的数值或事实，优先使用 analyze_financial_trends 或 query_financial_metrics。
                4、[filing-stated cause] 只能来自财报文本中明确陈述的原因，必须使用 retrieve_filing_kb 返回的原文片段作为依据。
                5、[external media report] 只能来自 search_media_reports 返回的可靠媒体报道。
                6、[broker research] 只能来自 search_broker_research 返回的可识别正规券商研究报告；若没有可靠券商报告来源，必须说明未找到，不得把媒体转载或普通网页当作券商研究。
                7、[inference] 是你基于已引用事实和来源做出的推理，必须写明依据的 source id。
                8、[opinion] 是你的前瞻观点或预测，必须写明依据、假设和不确定性，不得表达为确定事实。
                9、若用户问最近8个季度的收入、成本、营业利润/亏损和同比趋势，必须先使用 analyze_financial_trends 获取结构化数据，并在表格中展示。
                10、若用户问为什么变化，必须检索财报知识库；只有财报明确写出的原因才能标为 [filing-stated cause]。
                11、若来源不足，直接说明“不足以判断”，不要猜测。
                12、输出必须包含 Sources 列表，列出所有使用过的 source id。

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
        toolkit.registerTool(new StockInfoTool());
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

