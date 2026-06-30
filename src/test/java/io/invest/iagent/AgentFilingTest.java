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

    @Autowired
    private HarnessAgent baseAgent ;

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
    public void test_query2() {
        Msg qaMsg = this.buildUserMsg("使用futu_financial skill获取谷歌最近4个季度的利润表数据。使用python执行scripts/get_financials_statements.py脚本");
        Msg response = baseAgent.call(qaMsg).block();
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
                1、调用工具get_stock_ticker获取公司的股票代码。
                2、调用技能futu-financial-report生成财务报表，注意：直接按照skill.md调用方式执行即可。
                3、检查财务报表文件是否创建成功。
                特别注意：
                1、严格禁止只输出执行方式，但不去真正执行。
                2、严格禁止不通过get_stock_ticker工具获取股票代码。
                3、严格禁止查看技能的python代码，尝试了解其实现逻辑去探索执行方案。
                """;

        Msg qaMsg = this.buildUserMsg(String.format(template, companyName, reportName));
        Msg response = baseAgent.call(qaMsg).block();
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

}
