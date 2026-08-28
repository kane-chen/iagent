package io.invest.iagent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
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

import java.util.Objects;

@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
public class FilingRagAgentTest {

    @Autowired
    private HarnessAgent filingRagAgent;

    private RuntimeContext context;

    @BeforeEach
    public void init() {
    }

    @Test
    public void test_retrieve_capex_1Q() {
        Msg qaMsg = this.buildUserMsg("腾讯公司（股票代码00700）2026Q2的资本开支是多少？请从财报文件中获取信息。");
        Msg response = filingRagAgent.call(qaMsg).block();
        String responseText = Objects.requireNonNull(response).getTextContent();
        System.out.println("question response:::" + responseText);
        Assert.notNull(responseText, "question response");
        Assertions.assertThat(responseText).containsAnyOf("593亿", "人民币", "资本开支");
    }

    @Test
    public void test_retrieve_capex_5Q() {
        Msg qaMsg = this.buildUserMsg("腾讯公司（股票代码00700）最近5个季度的资本开支分别是多少？请从财报文件中获取信息。");
        Msg response = filingRagAgent.call(qaMsg).block();
        String responseText = Objects.requireNonNull(response).getTextContent();
        System.out.println("question response:::" + responseText);
        Assert.notNull(responseText, "question response");
        Assertions.assertThat(responseText).containsAnyOf("593亿", "人民币", "资本开支");
    }

    @Test
    public void test_retrieve2() {
        Msg qaMsg = this.buildUserMsg("英伟达近4个季度经营损益分别是多少？每个季度的同比变化多少？");
        Msg response = filingRagAgent.call(qaMsg).block();
        String responseText = Objects.requireNonNull(response).getTextContent();
        System.out.println("question response:::" + responseText);
        Assert.notNull(responseText, "question response");
//        Assertions.assertThat(responseText).containsAnyOf("经营损益", "MEGA", "销售组合");
    }

    @Test
    public void test_retrieve3() {
        Msg qaMsg = this.buildUserMsg("阿里巴巴最近8个季度经营损益分别是多少？每个季度的同比变化多少？变化的原因是什么？");
        Msg response = filingRagAgent.call(qaMsg).block();
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

}
