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
        Msg qaMsg = this.buildUserMsg("拼多多最近3年的CostOfRevenue,OperatingIncomeLoss分别是多少");
        Msg response = agent.call(qaMsg).block();
        String responseText = Objects.requireNonNull(response).getTextContent();
        System.out.println("2.question response:::" + responseText);
        Assert.notNull(responseText, "question response");
        Assertions.assertThat(responseText).containsAnyOf("成功", "success");
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
        Msg qaMsg = this.buildUserMsg("使用SubAgentFiling回答'理想公司2025年经营损益下降原因是什么'，不要下载财报文件，现有财报的内容就可以回答。");
        Msg response = agent.call(qaMsg).block();
        String responseText = Objects.requireNonNull(response).getTextContent();
        System.out.println("question response:::" + responseText);
        Assert.notNull(responseText, "question response");
        Assertions.assertThat(responseText).containsAnyOf("经营损益", "MEGA", "销售组合");
    }

    @Test
    public void test_retrieve2() {
        Msg qaMsg = this.buildUserMsg("理想公司2025年4个季度经营损益分别是多少？同比变化多少？变化的原因是什么？");
        Msg response = agent.call(qaMsg).block();
        String responseText = Objects.requireNonNull(response).getTextContent();
        System.out.println("question response:::" + responseText);
        Assert.notNull(responseText, "question response");
        Assertions.assertThat(responseText).containsAnyOf("经营损益", "MEGA", "销售组合");
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

}
