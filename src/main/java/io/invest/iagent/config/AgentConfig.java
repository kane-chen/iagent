package io.invest.iagent.config;

import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import io.agentscope.core.tool.coding.ShellCommandTool;
import io.agentscope.core.tool.file.ReadFileTool;
import io.agentscope.core.tool.file.WriteFileTool;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
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
        // shell-command (python is allowed for futu_financial skill)
        toolkit.registerTool(new ShellCommandTool(Set.of("python","python3")));
        toolkit.registerTool(new ReadFileTool());
        toolkit.registerTool(new WriteFileTool());

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
                        # 反例（不要这么做）
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
                // large result eviction
//                .middleware(new ToolResultEvictionMiddleware(
//                        new LocalFilesystemWithShell(workspace),
//                        ToolResultEvictionConfig.builder()
//                                .maxResultChars(1500)
//                                .previewChars(200).build()))
                .build();
    }

}

