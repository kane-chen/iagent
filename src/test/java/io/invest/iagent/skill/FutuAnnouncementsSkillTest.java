package io.invest.iagent.skill;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.invest.AgentConfig4Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.Assert;

import java.util.Objects;

@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
public class FutuAnnouncementsSkillTest {

    @Autowired
    private HarnessAgent baseAgent ;

    private RuntimeContext context;

    @BeforeEach
    public void init() {
    }

    @Test
    public void test_tencent() {
        String responseText = doDownload("腾讯", "2024", "2026");
        Assert.notNull(responseText, "call response");
    }

    @Test
    public void test_mt() {
        String responseText = doDownload("美团", "2023", "2026");
        Assert.notNull(responseText, "call response");
    }

    @Test
    public void test_ppmt() {
        String responseText = doDownload("泡泡玛特", "2024", "2026");
        Assert.notNull(responseText, "call response");
    }

    @Test
    public void test_pdd() {
        String responseText = doDownload("拼多多", "2020", "2026");
        Assert.notNull(responseText, "call response");
    }

    @Test
    public void test_baba() {
        String responseText = doDownload("阿里巴巴", "2020", "2026");
        Assert.notNull(responseText, "call response");
    }

    @Test
    public void test_beke() {
        String responseText = doDownload("贝壳", "2020", "2026");
        Assert.notNull(responseText, "call response");
    }

    @Test
    public void test_li() {
        String responseText = doDownload("理想汽车", "2024", "2026");
        Assert.notNull(responseText, "call response");
    }

    @Test
    public void test_apple() {
        String responseText = doDownload("苹果", "2020", "2025");
        Assert.notNull(responseText, "call response");
    }

    @Test
    public void test_google() {
        String responseText = doDownload("谷歌", "2020", "2026");
        Assert.notNull(responseText, "call response");
    }

    @Test
    public void test_ms() {
        String responseText = doDownload("微软", "2020", "2026");
        Assert.notNull(responseText, "call response");
    }

    @Test
    public void test_nvda() {
        String responseText = doDownload("英伟达", "2023", "2026");
        Assert.notNull(responseText, "call response");
    }

    private String doDownload( String companyName ,String fiscalYearStart,String fiscalYearEnd){
        String template = """
                下载公司[%s]从%s至%s的财务报表，
                执行流程如下：
                1、调用工具get_stock_ticker获取公司的股票代码。
                2、调用技能futu-announcements下载财报文件，注意：直接按照skill.md调用方式执行即可。
                特别注意：
                1、严格禁止只输出执行方式，但不去真正执行。
                2、严格禁止不通过get_stock_ticker工具获取股票代码。
                3、严格禁止查看技能的python代码，尝试了解其实现逻辑去探索执行方案。
                """;

        Msg qaMsg = this.buildUserMsg(String.format(template, companyName, fiscalYearStart,fiscalYearEnd));
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
