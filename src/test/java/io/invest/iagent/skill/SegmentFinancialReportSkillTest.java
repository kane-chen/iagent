package io.invest.iagent.skill;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.invest.AgentConfig4Test;
import io.invest.iagent.utils.ProcessRunner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
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
public class SegmentFinancialReportSkillTest {

    @Autowired
    private HarnessAgent agent;

    @Test
    public void test_excel_baba() {
        String companyName = "阿里巴巴";
        Msg response = this.doExecute(companyName);
        String responseText = Objects.requireNonNull(response).getTextContent();
        Assert.notNull(responseText, "question response");
    }

    @Test
    public void test_excel_pdd() {
        String companyName = "拼多多";
        Msg response = this.doExecute(companyName);
        String responseText = Objects.requireNonNull(response).getTextContent();
        Assert.notNull(responseText, "question response");
    }

    @Test
    public void test_excel_google() {
        String companyName = "谷歌";
        Msg response = this.doExecute(companyName);
        String responseText = Objects.requireNonNull(response).getTextContent();
        Assert.notNull(responseText, "question response");
    }

    @Test
    @Disabled
    public void test_excel_microsoft() {
        String companyName = "微软";
        Msg response = this.doExecute(companyName);
        String responseText = Objects.requireNonNull(response).getTextContent();
        Assert.notNull(responseText, "question response");
    }

    @Test
    @Disabled
    public void test_excel_amazon() {
        String companyName = "亚马逊";
        Msg response = this.doExecute(companyName);
        String responseText = Objects.requireNonNull(response).getTextContent();
        Assert.notNull(responseText, "question response");
    }

    @Test
    public void test_excel_tencent() {
        String companyName = "腾讯控股";
        Msg response = this.doExecute(companyName);
        String responseText = Objects.requireNonNull(response).getTextContent();
        Assert.notNull(responseText, "question response");
    }

    @Test
    public void test_excel_tcom() {
        String companyName = "携程";
        Msg response = this.doExecute(companyName);
        String responseText = Objects.requireNonNull(response).getTextContent();
        Assert.notNull(responseText, "question response");
    }

    private Msg doExecute(String ticker) {
        String template = """
                生成公司[%s]所有分部的财务报表，
                执行流程如下：
                1、调用技能stock-ticker获取公司的股票代码。
                2、调用技能segment-financial-report生成财务报表。
                3、检查财务报表文件是否创建成功。
                """;

        Msg qaMsg = this.buildUserMsg(String.format(template, ticker));
        return agent.call(qaMsg).block();
    }

    private Msg buildUserMsg(String content){
        return Msg.builder()
                .role(MsgRole.USER)
                .textContent(content)
                .build();
    }

    @Test
    public void test_skill_direct_pdd() throws Exception {
        int code = this.runSkill("PDD", 360);
        Assertions.assertEquals(0,code);
    }

    @Test
    public void test_skill_direct_00700() throws Exception {
        int code = this.runSkill("00700", 360);
        Assertions.assertEquals(0,code);
    }

    @Test
    public void test_skill_direct_83690() throws Exception {
        int code = this.runSkill("83690", 360);
        Assertions.assertEquals(0,code);
    }

    @Test
    public void test_skill_direct_baba() throws Exception {
        int code = this.runSkill("BABA", 360);
        Assertions.assertEquals(0,code);
    }

    @Test
    public void test_skill_direct_beke() throws Exception {
        int code = this.runSkill("BEKE", 360);
        Assertions.assertEquals(0,code);
    }

    @Test
    public void test_skill_direct_tcom() throws Exception {
        int code = this.runSkill("TCOM", 360);
        Assertions.assertEquals(0,code);
    }

    @Test
    public void test_skill_direct_microsoft() throws Exception {
        int code = this.runSkill("MSFT", 160);
        Assertions.assertEquals(0,code);
    }

    @Test
    public void test_skill_direct_amazon() throws Exception {
        int code = this.runSkill("AMZN", 360);
        Assertions.assertEquals(0,code);
    }

    @Test
    public void test_skill_direct_google() throws Exception {
        int code = this.runSkill("GOOG", 360);
        Assertions.assertEquals(0,code);
    }

    private int runSkill(String ticker, int timeoutSeconds) throws Exception {
        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        Path script = projectRoot.resolve("workspace/skills/segment-financial-report/scripts/extract_segments.py");
        Assertions.assertTrue(Files.exists(script), "extract script missing at " + script);

        List<String> cmd = List.of(
                "python3", script.toString(),
                "--ticker", ticker,
                "--excel"
        );
        ProcessRunner.Result result = ProcessRunner.run(cmd, projectRoot, timeoutSeconds);
        Assertions.assertEquals(0, result.getExitCode(),
                "extract_segments.py failed, stderr: " + result.getStderr());
        return result.getExitCode() ;
    }

}
