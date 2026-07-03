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
public class FutuFilingSkillTest {

    @Autowired
    private HarnessAgent baseAgent ;

    private RuntimeContext context;

    @BeforeEach
    public void init() {
    }

    @Test
    public void test_tencent() {
        String responseText = doDownload("腾讯控股", "2024", "2026");
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
    public void test_tcom() {
        String responseText = doDownload("携程", "2024", "2026");
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

    @Test
    public void test_direct_LI() throws Exception {
       int result = runDownloadSkill("LI", "2024,2025,2026", 300);
        Assertions.assertEquals(0,result);
    }

    @Test
    public void test_direct_tcom() throws Exception {
        int result = runDownloadSkill("TCOM", "2023,2024,2025", 200);
        Assertions.assertEquals(0,result);
    }

    private int runDownloadSkill(String ticker, String fiscalYears, int timeoutSeconds) throws Exception {
        // workspace
        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        Path script = projectRoot.resolve("workspace/skills/futu-filing/scripts/download_announcement.py");
        Assertions.assertTrue(script.toFile().isFile(), "download script missing at " + script);
        // command
        List<String> cmd = List.of(
                "python", script.toString(),
                "--ticker", ticker,
                "--workspace", projectRoot.resolve("workspace").toString(),
                "--fiscal-years", fiscalYears
        );
        ProcessRunner.Result result = ProcessRunner.run(cmd, projectRoot, timeoutSeconds);
        return result.getExitCode();
    }

    private String doDownload( String companyName ,String fiscalYearStart,String fiscalYearEnd){
        String template = """
                下载公司[%s]从%s至%s的财务报表，
                执行流程如下：
                1、调用技能stock-ticker获取公司的股票代码。
                2、调用技能futu-filing下载财报文件。
                特别注意：
                1、严格禁止只输出执行方式，但不去真正执行。
                2、严格禁止不通过stock-ticker技能获取股票代码。
                3、调用技能时，直接按照skill.md调用方式执行即可。严格禁止查看技能的python代码，尝试了解其实现逻辑去探索执行方案。
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
