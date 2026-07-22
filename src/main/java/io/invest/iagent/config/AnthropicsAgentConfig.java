package io.invest.iagent.config;

import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import io.agentscope.core.tool.coding.ShellCommandTool;
import io.agentscope.core.tool.file.ReadFileTool;
import io.agentscope.core.tool.file.WriteFileTool;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.invest.iagent.hook.AuditLoggingMiddleware;
import io.invest.iagent.tools.web.WebSearchTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeoutException;

@Configuration
public class AnthropicsAgentConfig {

    @Bean
    HarnessAgent anthropicsAgent(Model model) {
        Path workspace = Paths.get(System.getProperty("user.dir")).resolve("workspace");
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
        // mcp-client
//        McpClientWrapper client = McpClientBuilder.create("local_filing")
//                .stdioTransport("python", workspace.resolve("mcp/server/local_filings/server.py").toAbsolutePath().toString())  // 命令 + 参数
//                .buildAsync()
//                .block();
//        toolkit.registration().mcpClient( client).apply();
        // memory (MemoryFlushMiddleware 和 MemoryMaintenanceMiddleware 控制记忆流转)
        MemoryConfig memoryConfig = MemoryConfig.builder()
                .consolidationMaxTokens(5000)
                .dailyFileRetentionDays(15)
                .sessionRetentionDays(7)
                .build() ;
        // permission
        PermissionContextState permissionContext = PermissionContextState.builder()
                .mode(PermissionMode.BYPASS)
                .build() ;
        // agent
        return HarnessAgent.builder()
                .name("anthropicsAgent")
                .enableAgentTracingLog(true)
                .model(model)
                .modelExecutionConfig(
                        ExecutionConfig.builder()
                                .timeout(Duration.ofSeconds(900))
                                .maxAttempts(1)
                                .build())
                .toolkit(toolkit)
                .sysPrompt("""
                         你是一个理智严谨的工作助理.
                        
                        # 你的特点如下：
                        1、你是一个作风严谨的专业人士，会根据问题先做计划，然后依据计划执行。
                        2、你可以在职责范围内独立自主进行工作，职责范围内不需要用户确认，直接执行即可。
                        3、你的风格是逻辑严谨，语言清晰、简洁、准确，仅会就用户提到的问题进行回答，不会做问题的引申和发散。
                        
                        # 你的行为规范如下：
                        1、你的所有知识来自通过当前工具或技能获得，严禁主观臆断。
                        2、严格禁止只输出计划或思路，但不去真正执行，严格禁止计划未完成就返回结果。
                        3、调用技能时，直接按照skill.md调用方式执行即可。
                        4、执行命令时，严格使用单条命令，禁止使用多步命令（因为有的系统不支持一次执行多条命令）。
                        5、你必须使用中文回答问题。
                        
                        
                        # 运行约定：
                        1、执行过程中产生的临时文件放到.workspace/temp目录下。
                        2、长期记忆只存储泛化的知识，不会记录某个特定公司的规则。
                        
                        # 反模式（不要这么做）
                        1、执行多步命令：比如skill执行时，使用多条命令（比如 cd ｛skill根目录｝ && python3 ｛脚本相对目录｝ --arg0 ）。
                        2、执行过程中直接阅读（不通过skill）workspace目录中的财报文件（htm、PDF格式）或财务报表（xls、xlsx格式）的内容。
                        3、执行过程中通过查看技能的python代码，尝试了解其实现逻辑去探索执行方案。
                        """)
                .workspace(workspace)
                // agent状态保持
                .stateStore(new InMemoryAgentStateStore())
                // 记忆管理
                .memory(memoryConfig)
                // skill目录
                .skillRepository(new FileSystemSkillRepository(workspace.resolve("skills")))
                // 权限管控
                .permissionContext(permissionContext)
                .maxIters(20)
                // 审计中间件
                .middleware(new AuditLoggingMiddleware(102400))
                .build();
    }

}
