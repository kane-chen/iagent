package io.invest.iagent.skill.valuation;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import io.invest.AgentConfig4Test;
import io.invest.iagent.utils.ProcessRunner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
public class T3StatementModelSkillTest {

    @Autowired
    private HarnessAgent agent;

    private RuntimeContext context;

    @Test
    public void test_skill_direct_00700() throws Exception {
        int code = this.runSkill("00700", 160);
        Assertions.assertEquals(0,code);
    }

    @Test
    public void test_skill_direct_baba() throws Exception {
        int code = this.runSkill("BABA", 120);
        Assertions.assertEquals(0,code);
    }

    private int runSkill(String ticker, int timeoutSeconds) throws Exception {
        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        Path script = projectRoot.resolve("workspace/skills/3-statement-model/scripts/build_3_statement_model.py");
        Assertions.assertTrue(Files.exists(script), "extract script missing at " + script);
        String workspace = projectRoot.resolve("workspace").toAbsolutePath().toString() ;
        List<String> cmd = List.of(
                "python3", script.toString(),
                "--ticker", ticker,
                "--workspace", workspace
        );
        ProcessRunner.Result result = ProcessRunner.run(cmd, projectRoot, timeoutSeconds);
        Assertions.assertEquals(0, result.getExitCode(),
                "build_dcf_model.py failed, stderr: " + result.getStderr());
        return result.getExitCode() ;
    }

}
