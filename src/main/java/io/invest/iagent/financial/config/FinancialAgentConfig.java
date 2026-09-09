package io.invest.iagent.financial.config;

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
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.middleware.ToolResultEvictionMiddleware;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.invest.iagent.hook.AuditLoggingMiddleware;
import io.invest.iagent.rag.filing.FilingBuildService;
import io.invest.iagent.rag.filing.FilingQaService;
import io.invest.iagent.tools.StockTickerTool;
import io.invest.iagent.tools.financial.FinancialDataTool;
import io.invest.iagent.tools.rag.filing.FilingTool;
import io.invest.iagent.tools.web.WebSearchTool;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;

@Configuration
public class FinancialAgentConfig {

    @Autowired(required=false)
    private FilingQaService qaService;

    @Autowired(required=false)
    private FilingBuildService buildService;

    @Autowired(required = false)
    private FinancialDataTool financialDataTool ;

    @Bean
    HarnessAgent financialAgent(Model model, Path workspace) {
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
        toolkit.registerTool(new StockTickerTool());
        if(ObjectUtils.allNotNull(qaService,buildService)){
            toolkit.registerTool(new FilingTool(qaService,buildService));
        }
        if(Objects.nonNull(financialDataTool)){
            toolkit.registerTool(financialDataTool);
        }

        // financial-sub-space
        workspace = workspace.resolve("financial") ;
        // large result eviction
        ToolResultEvictionMiddleware toolResultEviction = new ToolResultEvictionMiddleware(
                new LocalFilesystemWithShell(workspace),
                ToolResultEvictionConfig.builder()
                        .maxResultChars(1500)
                        .previewChars(200)
                        .build()
        ) ;
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
                .name("filingRagAgent")
                .enableAgentTracingLog(true)
                .model(model)
                .modelExecutionConfig(
                        ExecutionConfig.builder()
                                .timeout(Duration.ofSeconds(900))
                                .maxAttempts(1)
                                .build())
                .toolkit(toolkit)
                .sysPrompt("""
                        你是一个财务投资专家，可以根据用户的问题，从公司财报中获取获取事实数据，包括财务指标数据、财报文件中问题相关的文字描述，并基于上述数据回答用户问题。
                        ## 行为风格：
                        1. 你是一个作风严谨的专业人士，会根据问题先做计划，然后依据计划执行。
                        2. 你的语言描述要清晰、简洁、准确。
                        3. 你必须使用中文回答问题。
                        4. 你会**明确区分信息来源，包括"数据事实/财报原文/外部佐证/推理/观点"**。
                        5. 你的所有知识来自通过当前工具或技能获得，严禁主观臆断。
                        
                        ## 执行计划
                        1. 理解用户问题，生成执行计划。
                        2. 如用户没提供股票代码，需要调用技能stock-ticker拿到股票代码与市场归属，再执行后续操作。
                        3. 如用户需要获取公司财务指标数据，需要调用工具financial_data_query，获取财务指标数据。
                        4. 如回答用户问题需要使用公司财报文件中的内容，需要调用工具filing_kb_qa，获取问题相关的文字内容。
                        5. 如上述过程不能给出答案或用户明确要求检索web有关信息，则需要调用工具web_search，获取web信息以回答问题。
                        
                        ## 提别提醒
                        1. 你是一个独立自主的员工，可以在职责范围内自主进行工作，不需要用户确认，直接执行即可。
                        2. 你的风格是逻辑严谨、语言精炼。
                        3. 你是一个克制的员工，你会根据问题给出答案，但不会超出问题范围，不会引申和发散问题。
                        4. 你的作业是回答问题，请勿进行其他操作。
                        
                        ## 行为规范
                        1、严格禁止只输出计划或思路，但不去真正执行。
                        2、所有任务采用同步执行方式，一定不要使用异步方式执行。
                        3、调用技能时，直接按照skill.md调用方式执行即可。严格禁止查看技能的python代码，尝试了解其实现逻辑去探索执行方案。
                        
                        ## 信息来源规则
                        数据来源规则硬约束（违反等同于编造）：
                        1. `[fact]` 中的所有数字**只能**来自 `financial_data_query` 两个入口的返回值；不得从财报原文片段中提取数字作为 fact。
                        2. `[filing-stated cause]` 只能来自 `filing_kb_qa` 返回的原文片段，必须**原文引用**（可翻译，不得改写数字与因果关系），并携带 chunkId 或 sectionTitle 作为 source id。
                        3. `[external]` 只能来自 `web_search`；如果 web_search 返回空或不相关，就写明"未找到外部佐证"，**不得**用财报原文片段冒充外部来源。
                        4. `[inference]` 必须在 source id 列表里显式引用至少一个 `[fact]` 或 `[filing-stated cause]` 作为依据；单独依赖外部来源的推理需说明"未经财报交叉验证"。
                        5. `[opinion]` 必须写明假设、依据的 source id 和不确定性，不得表达为确定事实。
                        6. 若三类来源都不足以支撑用户问题，直接输出"当前证据不足以判断"，不要猜测。
                        
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
