package io.invest.iagent.skill;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.invest.AgentConfig4Test;
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
public class FutuFinancialReportSkillTest {

    @Autowired
    private HarnessAgent baseAgent ;

    private RuntimeContext context;

    @BeforeEach
    public void init() {
    }

    @Test
    public void test_nvda() {
        String responseText = doCall("英伟达", "利润表");
        Assert.notNull(responseText, "call response");
    }

    @Test
    public void test_baba() {
        String responseText = doCall("阿里巴巴", "利润表");
        Assert.notNull(responseText, "call response");
    }

    @Test
    public void test_pdd() {
        String responseText = doCall("拼多多", "资产负债表");
        Assert.notNull(responseText, "call response");
    }

    @Test
    public void test_byd() {
        String responseText = doCall("比亚迪股份", "利润表");
        Assert.notNull(responseText, "call response");
    }

    private String doCall(String companyName, String reportName){
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
        return Objects.requireNonNull(response).getTextContent();
    }

    private Msg buildUserMsg(String content){
        return Msg.builder()
                .role(MsgRole.USER)
                .textContent(content)
                .build();
    }

}
