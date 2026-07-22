package io.invest.iagent.skill;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.invest.AgentConfig4Test;
import io.invest.iagent.utils.ProcessRunner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.Assert;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
public class FutuFinancialReportSkillTest {

    @Autowired
    private HarnessAgent agent;

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
    public void test_tCom() {
        String responseText = doCall("携程", "利润表");
        Assert.notNull(responseText, "call response");
    }

    @Test
    public void test_netEase() {
        String responseText = doCall("网易", "利润表");
        Assert.notNull(responseText, "call response");
    }

    @Test
    public void test_vip() {
        String responseText = doCall("唯品会", "利润表");
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
                1、调用技能stock-ticker获取公司的股票代码。
                2、调用技能futu-financial-report生成财务报表。
                3、检查财务报表文件是否创建成功。
                特别注意：
                1、严格禁止不通过stock-ticker技能获取股票代码。
                """;

        Msg qaMsg = this.buildUserMsg(String.format(template, companyName, reportName));
        Msg response = agent.call(qaMsg).block();
        return Objects.requireNonNull(response).getTextContent();
    }

    private Msg buildUserMsg(String content){
        return Msg.builder()
                .role(MsgRole.USER)
                .textContent(content)
                .build();
    }

    @Test
    public void test_skill_direct_00700() throws Exception {
        int code = this.runSkill("HK.00700","income",32, 160);
        Assertions.assertEquals(0,code);
    }

    @Test
    public void test_skill_direct_83690() throws Exception {
        int code = this.runSkill("HK.83690","income",32, 160);
        Assertions.assertEquals(0,code);
    }

    @Test
    public void test_skill_direct_01211() throws Exception {
        int code = this.runSkill("HK.01211","income",32, 160);
        Assertions.assertEquals(0,code);
    }

    @Test
    public void test_skill_direct_microsoft() throws Exception {
        int code = this.runSkill("US.MSFT","income",32, 160);
        Assertions.assertEquals(0,code);
    }

    @Test
    public void test_skill_direct_amazon() throws Exception {
        int code = this.runSkill("US.AMZN","income",32,  360);
        Assertions.assertEquals(0,code);
    }

    @Test
    public void test_skill_direct_google() throws Exception {
        int code = this.runSkill("US.GOOG", "income",32, 360);
        Assertions.assertEquals(0,code);
    }

    @Test
    public void test_skill_direct_beke() throws Exception {
        int code = this.runSkill("US.BEKE", "income",32, 160);
        Assertions.assertEquals(0,code);
    }

    @Test
    public void test_skill_direct_jd() throws Exception {
        int code = this.runSkill("US.JD", "income",32, 160);
        Assertions.assertEquals(0,code);
    }

    @Test
    public void test_skill_direct_nio() throws Exception {
        int code = this.runSkill("US.NIO", "income",32, 160);
        Assertions.assertEquals(0,code);
    }

    @Test
    public void test_skill_direct_xp() throws Exception {
        int code = this.runSkill("US.XPEV", "income",32, 160);
        Assertions.assertEquals(0,code);
    }

    @Test
    public void test_skill_direct_amazon_2() throws Exception {
        int code = this.runSkill("US.AMZN", "balance",8, 160);
        Assertions.assertEquals(0,code);
    }

    @Test
    public void test_skill_direct_baba() throws Exception {
        String ticker = "US.BABA";
        int code = this.runSkill(ticker, "income",32, 160);
        Assertions.assertEquals(0,code);
        code = this.runSkill(ticker, "balance",32, 160);
        Assertions.assertEquals(0,code);
        code = this.runSkill(ticker, "cashflow",32, 160);
        Assertions.assertEquals(0,code);
    }

    @Test
    public void test_skill_direct_tencent() throws Exception {
        String ticker = "HK.00700";
        int code = this.runSkill(ticker, "income",32, 160);
        Assertions.assertEquals(0,code);
        code = this.runSkill(ticker, "balance",32, 160);
        Assertions.assertEquals(0,code);
        code = this.runSkill(ticker, "cashflow",32, 160);
        Assertions.assertEquals(0,code);
    }

    private int runSkill(String ticker,String type,int limit, int timeoutSeconds) throws Exception {
        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        Path script = projectRoot.resolve("workspace/skills/futu-financial-report/scripts/generate_financial_excel.py");
        Assertions.assertTrue(Files.exists(script), "extract script missing at " + script);

        List<String> cmd = List.of(
                "python3", script.toString(),
                ticker,
                "--type", type,
                "--num", limit+""
        );
        ProcessRunner.Result result = ProcessRunner.run(cmd, projectRoot, timeoutSeconds);
        Assertions.assertEquals(0, result.getExitCode(),
                "extract_segments.py failed, stderr: " + result.getStderr());
        return result.getExitCode() ;
    }

}
