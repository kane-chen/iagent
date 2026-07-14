package io.invest.iagent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.invest.AgentConfig4Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Objects;
import java.util.Scanner;

@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
public class AgentChatTest {


    @Autowired
    private HarnessAgent agent;

    @Autowired
    private HarnessAgent qaAgent;

    private RuntimeContext context;

    @BeforeEach
    public void init() {
        context = RuntimeContext.builder()
                .sessionId("console-chat-session00001")
                .userId("ironman")
                .build();
    }

    /**
     * 交互式会话测试：从 console 读取用户输入 → 送入 agent → 打印回复，循环直到用户退出。
     *
     * console如果是只读模式，需要增加以下vm option：-Deditable.java.test.console=true
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
    public void test_interactive_chat() {

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

            System.out.print("助手: ");
            UserMessage userMsg = new UserMessage(input);

            Msg response = qaAgent.call(userMsg, context).block();
            System.out.println("助手: " + Objects.requireNonNull(response).getTextContent());

        }
    }

}
