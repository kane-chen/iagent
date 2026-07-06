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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
public class SegmentFinancialReportSkillTest {

    @Autowired
    private HarnessAgent baseAgent ;

    private RuntimeContext context;

    @BeforeEach
    public void init() {
    }

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
    public void test_excel_microsoft() {
        String companyName = "微软";
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
                特别注意：
                1、严格禁止只输出执行方式，但不去真正执行。
                2、严格禁止不通过stock-ticker技能获取股票代码。
                3、调用技能时，直接按照skill.md调用方式执行即可。严格禁止查看技能的python代码，尝试了解其实现逻辑去探索执行方案。
                """;

        Msg qaMsg = this.buildUserMsg(String.format(template, ticker));
        return baseAgent.call(qaMsg).block();
    }

    private Msg buildUserMsg(String content){
        return Msg.builder()
                .role(MsgRole.USER)
                .textContent(content)
                .build();
    }

    @Test
    public void test_skill_direct_baba() throws Exception {
        int result = this.runSkill("BABA", 100) ;
        Assert.isTrue(result == 0, "call failed");
    }

    @Test
    public void test_skill_direct_pdd() throws Exception {
        int result = this.runSkill("PDD", 120) ;
        Assert.isTrue(result == 0, "call failed");
    }

    @Test
    public void test_skill_direct_tcom() throws Exception {
        int result = this.runSkill("TCOM", 120) ;
        Assert.isTrue(result == 0, "call failed");
    }

    @Test
    public void test_skill_direct_00700() throws Exception {
        int result = this.runSkill("00700", 360) ;
        Assert.isTrue(result == 0, "call failed");
    }

    private int runSkill(String ticker, int timeoutSeconds) throws Exception {
        // workspace
        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        Path script = projectRoot.resolve("workspace/skills/segment-financial-report/scripts/extract_segments.py");
        Assertions.assertTrue(script.toFile().isFile(), "generate script missing at " + script);
        // command
        List<String> cmd = List.of(
                "python", script.toString(),
                "--ticker", ticker,
                "--excel"
        );
        ProcessRunner.Result result = ProcessRunner.run(cmd, projectRoot, timeoutSeconds);
        return result.getExitCode();
    }

}
