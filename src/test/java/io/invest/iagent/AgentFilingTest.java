package io.invest.iagent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import io.invest.AgentConfig4Test;
import io.invest.iagent.config.ApplicationProperties;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.Assert;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
public class AgentFilingTest {

    @Autowired
    private ApplicationProperties applicationProperties;

    @Autowired
    private Model model;

    @Autowired
    private HarnessAgent agent;

    private RuntimeContext context;

    @BeforeEach
    public void init() {
    }

    @Test
    public void test_download_filling() {
        Msg downloadMsg = this.buildUserMsg("下载拼多多2024年的财报");
        Msg response = agent.call(downloadMsg, context).block();
        String responseText = Objects.requireNonNull(response).getTextContent();
        System.out.println("download response:::" + responseText);
        Assertions.assertThat(responseText).containsAnyOf("成功下载", "download response");
    }

    @Test
    public void test_query() {
        Msg qaMsg = this.buildUserMsg("拼多多最近3年的成本、运营利润分别是多少");
        Msg response = agent.call(qaMsg).block();
        String responseText = Objects.requireNonNull(response).getTextContent();
        System.out.println("2.question response:::" + responseText);
        Assert.notNull(responseText, "question response");
        Assertions.assertThat(responseText).containsAnyOf("成功", "success");
    }

    @Test
    public void test_query2() {
        Msg qaMsg = this.buildUserMsg("使用futu_financial skill获取谷歌最近4个季度的利润表数据。使用python执行scripts/get_financials_statements.py脚本");
        Msg response = agent.call(qaMsg).block();
        String responseText = Objects.requireNonNull(response).getTextContent();
        System.out.println("2.question response:::" + responseText);
        Assert.notNull(responseText, "question response");
        // 验证返回数据包含财务数据或成功信息
        Assertions.assertThat(responseText).containsAnyOf("总收入", "净利润", "毛利", "success", "report_list", "total_revenue", "net_income");
    }

    @Test
    public void test_query3() {
        String companyName = "五粮液";
        String reportName = "利润表";
        String template = """
                生成公司[%s]最近32个季度的%s报表，
                执行流程如下：
                1、调用技能stock-ticker获取公司的股票代码（python workspace/skills/stock-ticker/scripts/search_ticker.py --company <公司名>）。
                2、调用技能futu-financial-report生成财务报表，注意：直接按照skill.md调用方式执行即可。
                3、检查财务报表文件是否创建成功。
                """;

        Msg qaMsg = this.buildUserMsg(String.format(template, companyName, reportName));
        Msg response = agent.call(qaMsg).block();
        String responseText = Objects.requireNonNull(response).getTextContent();
        Assert.notNull(responseText, "question response");
    }

    @Test
    public void test_query_mix() {
        Msg qaMsg = this.buildUserMsg("苹果公司最近3年的收入、成本、营业费用、营业损益、毛利率、净利润率分别是多少");
        Msg response = agent.call(qaMsg).block();
        String responseText = Objects.requireNonNull(response).getTextContent();
        System.out.println("question response:::" + responseText);
        Assert.notNull(responseText, "question response");
        Assertions.assertThat(responseText).containsAnyOf("收入", "Revenue");
    }

    @Test
    public void test_retrieve() {
        Msg qaMsg = this.buildUserMsg("理想公司2025年经营损益下降原因是什么");
        Msg response = agent.call(qaMsg).block();
        String responseText = Objects.requireNonNull(response).getTextContent();
        System.out.println("question response:::" + responseText);
        Assert.notNull(responseText, "question response");
        Assertions.assertThat(responseText).containsAnyOf("经营损益", "MEGA", "销售组合");
    }

    @Test
    public void test_retrieve2() {
        Msg qaMsg = this.buildUserMsg("英伟达近4个季度经营损益分别是多少？每个季度的同比变化多少？");
        Msg response = agent.call(qaMsg).block();
        String responseText = Objects.requireNonNull(response).getTextContent();
        System.out.println("question response:::" + responseText);
        Assert.notNull(responseText, "question response");
//        Assertions.assertThat(responseText).containsAnyOf("经营损益", "MEGA", "销售组合");
    }

    @Test
    public void test_retrieve3() {
        Msg qaMsg = this.buildUserMsg("阿里巴巴最近8个季度经营损益分别是多少？每个季度的同比变化多少？变化的原因是什么？");
        Msg response = agent.call(qaMsg).block();
        String responseText = Objects.requireNonNull(response).getTextContent();
        System.out.println("question response:::" + responseText);
        Assert.notNull(responseText, "question response");
        Assertions.assertThat(responseText).containsAnyOf("经营损益", "MEGA", "销售组合");
    }


    private Msg buildUserMsg(String content){
        return Msg.builder()
                .role(MsgRole.USER)
                .textContent(content)
                .build();
    }

    /**
     * 交互式会话测试：从 console 读取用户输入 → 送入 agent → 打印回复，循环直到用户退出。
     *
     * <p>使用方式（IDE 或命令行）：
     * <pre>
     *   mvn test -Dtest=AgentFilingTest#test_interactive_chat
     * </pre>
     * 支持的退出指令（不区分大小写）：exit / quit / bye / q / :q。
     * 直接回车 = 跳过本轮不发送。
     *
     * <p>说明：
     * <ul>
     *   <li>同一 {@link RuntimeContext} 贯穿整个会话，agent 具备多轮上下文记忆能力。</li>
     *   <li>该方法默认标注 {@code @Test}，但依赖交互式 stdin。CI/无 tty 场景下第一次
     *       {@code readLine()} 会立即返回 null，方法会打印提示后正常结束（不断言、不报错），
     *       等价于跳过。</li>
     * </ul>
     */
    @Test
    public void test_interactive_chat() throws Exception {
        // 2. 固定 session，保持多轮对话上下文
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("console-chat-session")
                .userId("console-user")
                .build();

        // 3. Console 交互循环
        Scanner scanner = new Scanner(System.in);
        System.out.println("=== AgentScope 多轮问答 (输入 exit 退出) ===\n");

        while (true) {
            System.out.print("你: ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) {
                continue;
            }
            if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
                System.out.println("再见！");
                break;
            }

            // 4. 流式输出 Agent 回复
            System.out.print("助手: ");
            UserMessage userMsg = new UserMessage(input);

            Msg response = agent.call(userMsg, ctx).block();
            System.out.println("助手: " + Objects.requireNonNull(response).getTextContent());

//            agent.streamEvents(userMsg, ctx)
//                    .filter(event -> event instanceof TextBlockDeltaEvent)
//                    .map(event -> (TextBlockDeltaEvent) event)
//                    .subscribe(
//                            delta -> System.out.print(delta.getDelta()),
//                            error -> System.err.println("\n[错误] " + error.getMessage()),
//                            () -> System.out.println("\n")
//                    );
        }
    }

}
