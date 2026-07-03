package io.invest.iagent.subagent;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.invest.AgentConfig4Test;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.Assert;

import java.util.Objects;

@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
public class FinancialReportSubAgentTest {

    @Autowired
    private HarnessAgent baseAgent ;

    @Test
    public void test_baba() {
        String responseText = doCall("阿里巴巴最近8个季度的经营利润分别是多少");
        Assert.notNull(responseText, "call response");
    }

    @Test
    public void test_pdd() {
        String responseText = doCall("拼多多最近8个季度的毛利率分别是多少");
        Assert.notNull(responseText, "call response");
    }

    private String doCall(String question){
        String template = """
                调用financial-report-subagent回答问题【%s】。
                特别注意：
                1、严格禁止只输出执行方式，但不去真正执行。
                2、调用技能时，直接按照skill.md调用方式执行即可。严格禁止查看技能的python代码，尝试了解其实现逻辑去探索执行方案。
                """;
        Msg qaMsg = Msg.builder().role(MsgRole.USER)
                .textContent(String.format(template, question)).build();
        Msg response = baseAgent.call(qaMsg).block();
        return Objects.requireNonNull(response).getTextContent();
    }

}
