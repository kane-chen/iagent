package io.invest.iagent.skill;

import com.alibaba.fastjson2.JSON;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.invest.AgentConfig4Test;
import io.invest.iagent.utils.ProcessRunner;
import org.junit.jupiter.api.Assertions;
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
public class FinancialFillingQaSkillTest {

    @Autowired
    private HarnessAgent agent;

    @Test
    public void test_excel_83690() {
        String question = "美团公司核心本地商业分部2026Q1相较于2025Q4经营亏损减少的原因";
        Msg response = this.doExecute(question);
        String responseText = Objects.requireNonNull(response).getTextContent();
        Assert.notNull(responseText, "question response");
    }

    private Msg doExecute(String question) {
        String template = """
                回答用户的问题：[%s]，
                执行流程如下：
                1、调用技能stock-ticker获取公司的股票代码。
                2、调用技能financial-filing-qa回答用户问题。
                特别注意：
                1、严格禁止只输出执行方式，但不去真正执行。
                2、严格禁止不通过stock-ticker技能获取股票代码。
                3、调用技能时，直接按照skill.md调用方式执行即可。严格禁止查看技能的python代码，尝试了解其实现逻辑去探索执行方案。
                """;

        Msg qaMsg = this.buildUserMsg(String.format(template, question));
        return agent.call(qaMsg).block();
    }

    private Msg buildUserMsg(String content){
        return Msg.builder()
                .role(MsgRole.USER)
                .textContent(content)
                .build();
    }

    @Test
    public void test_skill_direct_00700() throws Exception {
        int code = this.runSkill("00700", "经营利润减少的原因","2025",240);
        Assertions.assertEquals(0,code);
    }

    @Test
    public void test_skill_direct_83690() throws Exception {
        int code = this.runSkill("83690", "2026Q1相较于2025Q4经营亏损减少的原因","2026",360);
        Assertions.assertEquals(0,code);
    }

    @Test
    public void test_skill_direct_83690_2() throws Exception {
        int code = this.runSkill("83690", "核心本地商业分部2025Q3经营利润率同比下降的原因是什么","2025",360);
        Assertions.assertEquals(0,code);
    }

    private int runSkill(String ticker,String question,String fromPeriod, int timeoutSeconds) throws Exception {
        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        Path script = projectRoot.resolve("workspace/skills/financial-filing-qa/scripts/qa.py");
        Assertions.assertTrue(Files.exists(script), "extract script missing at " + script);

        List<String> cmd = List.of(
                "python", script.toString(),
                "--ticker", ticker,
                "--question", question ,
                "--from-period", fromPeriod
        );
        ProcessRunner.Result result = ProcessRunner.run(cmd, projectRoot, timeoutSeconds);
        System.out.println(JSON.toJSONString(result));
        Assertions.assertEquals(0, result.getExitCode(),
                "qa.py failed, stderr: " + result.getStderr());
        return result.getExitCode() ;
    }

}
