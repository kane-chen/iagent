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
public class MarketResearchAgentTest {

    @Autowired
    private HarnessAgent marketResearchAgent;


    @Test
    public void test_e_car() {
        String question = "分析下中国新能源汽车行业";
        String response = this.doCall(question);
        Assert.notNull(response, "question response");
        System.out.println(response);
        Assertions.assertThat(response).containsAnyOf("竞争","毛利率","营销");
    }

    private String doCall(String question){
        Msg qaMsg = Msg.builder().role(MsgRole.USER)
                .textContent(question).build();
        Msg response = marketResearchAgent.call(qaMsg).block();
        return Objects.requireNonNull(response).getTextContent();
    }

}
