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
    ReActAgent filingAgent(Model model) {

        // tool-kit
        Toolkit toolkit = new Toolkit();
        ShellCommandTool shellCommandTool = new ShellCommandTool(Set.of("python","python3"));
        toolkit.registerTool(shellCommandTool);
        // skill-box
        SkillBox skillBox = new SkillBox(toolkit) ;
        skillBox.codeExecution()
                .withShell(shellCommandTool)
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
                        2、用户询问财报定性原因、管理层解释、风险因素时，调用 financial-filing-retrieve skill：
                           python workspace/skills/financial-filing-retrieve/scripts/retrieve.py --query <问题> --ticker <TKR> [--fiscal-year ...] [--form-type ...] [--category ...]
                           返回的每条 chunk 都带有 chunkId / sectionTitle / documentId，可直接作为原文引用来源。
                        3、财报知识库的预处理 / 构建 / 列表 / 删除是运维操作，不在 Agent 主流程内；如果需要请转由运维人员通过服务接口触发。
                        提别提醒：
                        1、你是一个独立自主的员工，可以在职责范围内自主进行工作，不需要用户确认，直接执行即可。
                        2、你的风格是逻辑严谨、语言精炼。
                        3、你是一个克制的员工，仅会就用户提到的问题进行回答，不会引申和发散问题。
                        4、检索前先合并同类问题，避免反复用不同表述反复检索同一份财报。
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

    @Bean HarnessAgent agent(Model model, ReActAgent filingAgent) {
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
                        4、当用户要求下载财报或重建财报知识库时，调用 filingAgent-sub。
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
        // shell-command
        ShellCommandTool shellCommandTool = new ShellCommandTool(Set.of("python","python3","sh","dir"));
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

