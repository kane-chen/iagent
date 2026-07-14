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
public class QaAgentTest {

    @Autowired
    private HarnessAgent qaAgent;

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

    @Test
    public void test_excel_83690() {
        String question = "美团公司核心本地商业分部2025Q3经营利润率同比下降的原因是什么";
        String response = this.doCall(question);
        Assert.notNull(response, "question response");
        System.out.println(response);
        Assertions.assertThat(response).containsAnyOf("市场竞争","骑手补贴","营销开支");
    }

    @Test
    public void test_excel_83690_2() {
        String question = "美团公司核心本地商业分部2026Q1经营亏损环比改善的原因是什么";
        String response = this.doCall(question);
        Assert.notNull(response, "question response");
        System.out.println(response);
        Assertions.assertThat(response).containsAnyOf("竞争","毛利率","营销");
    }

    private String doCall(String question){
        Msg qaMsg = Msg.builder().role(MsgRole.USER)
                .textContent(question).build();
        Msg response = qaAgent.call(qaMsg).block();
        return Objects.requireNonNull(response).getTextContent();
    }

}
