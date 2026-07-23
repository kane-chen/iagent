package io.invest.iagent.subagent;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.invest.AgentConfig4Test;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.Assert;

import java.nio.file.Path;
import java.util.Objects;

@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
public class AnthropicsAgentTest {

    @Autowired
    private HarnessAgent anthropicsAgent;

    @Autowired
    private Path workspace ;

    @Test
    public void test_market_research() {
        String question = "输出中国电商的行业分析";
        String response = this.doCall(question);
        Assert.notNull(response, "question response");
        System.out.println(response);
        Assertions.assertThat(response).containsAnyOf("报告","保存","成功");
    }

    @Test
    public void test_model_build() {
        String question = """
        构建US.PDD的DCF估值模型，使用中文作答。
        """;
        String response = this.doCall(question);
        Assert.notNull(response, "question response");
        System.out.println(response);
        Assertions.assertThat(response).containsAnyOf("模型","保存","成功");
    }

    @Test
    public void test_model_build2() {
        String path = workspace.toAbsolutePath().toString() ;
        String skillPath = workspace.resolve("skills").toAbsolutePath().toString() ;
        String question = """
        拼多多当前股价为86美元、有效税率为0.15，请构建拼多多的估值模型（要求包括dcf、lbo、三表联动模型、可比公司模型），使用中文作答。
        # 特别提醒：
        1、你会根据问题先做计划，然后依据计划执行。
        2、严格禁止只输出计划但不实际执行。
        3、严格禁止直行计划未完成，就中途返回结果。

        # 注意：
        1、skill中有用到workspace输入参数的地方，使用绝对路径%s
        2、skill中有用到python命令的时候请使用python3
        3、skill的脚本根目录在%s
        4、skill执行时，不要使用多条命令（比如 cd ｛skill根目录｝ && python3 ｛脚本相对目录｝ --arg0 ），而是使用一条命令（即 python3 {脚本绝对路径} --arg0） ...
        """;
        String input = String.format(question,path,skillPath) ;
        String response = this.doCall(input);
        Assert.notNull(response, "question response");
    }

    @Test
    public void test_competitive_analysis() {
        String path = workspace.toAbsolutePath().toString() ;
        String skillPath = workspace.resolve("skills").toAbsolutePath().toString() ;
        String question = """
        使用技能`competitive-analysis`，以拼多多 (PDD) 为主角做中国电商行业竞争格局分析，对标 BABA、JS、VIPS，给投资委员会看，需要 Bull/Base/Bear 情景。
        # 特别提醒：
        1、你会根据问题先做计划，然后依据计划执行。
        2、严格禁止只输出计划但不实际执行。
        3、严格禁止直行计划未完成，就中途返回结果。
        4、使用中文作答。

        # 注意：
        1、skill中有用到workspace输入参数的地方，使用绝对路径%s
        2、skill中有用到python命令的时候请使用python3
        3、skill的脚本根目录在%s
        """;
        String input = String.format(question,path,skillPath) ;
        String response = this.doCall(input);
        Assert.notNull(response, "question response");
    }

    @Test
    public void test_skill_direct_00700() {
        String result = this.doExecute("00700", "2026Q1");
        org.junit.jupiter.api.Assertions.assertNotNull(result);
    }

    @Test
    public void test_skill_direct_google() {
        String result = this.doExecute("GOOG", "2026Q2");
        org.junit.jupiter.api.Assertions.assertNotNull(result);
    }

    private String doExecute( String companyName ,String fiscalPeriod){
        String template = """
                使用技能'earnings-analysis'分析下%s公司%s的财报，并输出分析报告。
                # 特别提醒：
                1、你会根据问题先做计划，然后依据计划执行。
                2、严格禁止只输出计划但不实际执行。
                3、严格禁止直行计划未完成，就中途返回结果。
                4、使用中文作答。
        
                # 注意：
                1、skill中有用到workspace输入参数的地方，使用绝对路径%s
                2、skill中有用到python命令的时候请使用python3
                3、skill的脚本根目录在%s
                """;
        String path = workspace.toAbsolutePath().toString() ;
        String skillPath = workspace.resolve("skills").toAbsolutePath().toString() ;
        String question = String.format(template, companyName, fiscalPeriod,path,skillPath) ;
        return this.doCall(question );
    }

    private String doCall(String question){
        Msg qaMsg = Msg.builder().role(MsgRole.USER)
                .textContent(question).build();
        Msg response = anthropicsAgent.call(qaMsg).block();
        return Objects.requireNonNull(response).getTextContent();
    }

}
