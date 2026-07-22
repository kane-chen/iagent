package io.invest.iagent.subagent;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.invest.AgentConfig4Test;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.Assert;

import java.util.Objects;

@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
public class AnthropicsAgentTest {

    @Autowired
    private HarnessAgent anthropicsAgent;

    @Test
    public void test_market_research() {
        String question = "输出中国电商的行业分析";
        String response = this.doCall(question);
        Assert.notNull(response, "question response");
        System.out.println(response);
        Assertions.assertThat(response).containsAnyOf("报告","保存","成功");
    }

    @Test
    public void test_model_build() {
        String question = """
        构建US.PDD的DCF估值模型，使用中文作答。
        """;
        String response = this.doCall(question);
        Assert.notNull(response, "question response");
        System.out.println(response);
        Assertions.assertThat(response).containsAnyOf("模型","保存","成功");
    }

    @Test
    public void test_model_build2() {
        String question = """
        构建拼多多的估值模型，使用中文作答。
        """;
        String response = this.doCall(question);
        Assert.notNull(response, "question response");
        System.out.println(response);
        Assertions.assertThat(response).containsAnyOf("模型","保存","成功");
    }

    private String doCall(String question){
        Msg qaMsg = Msg.builder().role(MsgRole.USER)
                .textContent(question).build();
        Msg response = anthropicsAgent.call(qaMsg).block();
        return Objects.requireNonNull(response).getTextContent();
    }

}
