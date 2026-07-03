package io.invest.iagent.skill;

import com.alibaba.fastjson2.JSON;
import io.agentscope.harness.agent.HarnessAgent;
import io.invest.AgentConfig4Test;
import io.invest.iagent.utils.ProcessRunner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.Assert;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
public class FinancialFilingRetrieveSkillTest {

    @Autowired
    private HarnessAgent baseAgent ;

    @Test
    public void test_li() throws IOException, InterruptedException {
        String query = "gross margin" ;
        String ticker = "LI" ;
        String fiscalYears = "2026" ;
        ProcessRunner.Result result = doCall(query,ticker,fiscalYears,300);
        Assert.notNull(result, "call response");
        System.out.println(JSON.toJSONString(result));
        Assert.isTrue(result.isSuccess(),"call failed");
    }

    @Test
    public void test_baba() throws IOException, InterruptedException {
        String query = "gross margin" ;
        String ticker = "BABA" ;
        String fiscalYears = "2026" ;
        ProcessRunner.Result result = doCall(query,ticker,fiscalYears,100);
        Assert.notNull(result, "call response");
        System.out.println(JSON.toJSONString(result));
        Assert.isTrue(result.isSuccess(),"call failed");
    }

    private ProcessRunner.Result doCall(String question,String ticker,String fiscalYears,int timeoutSeconds) throws IOException, InterruptedException {
        // workspace
        Path workspace = Paths.get(System.getProperty("user.dir")).resolve("workspace");
        Path script = workspace.resolve("skills/financial-filing-retrieve/scripts/retrieve.py");
        Assertions.assertTrue(script.toFile().isFile(), "download script missing at " + script);
        // command
        List<String> cmd = List.of(
                "python", script.toString(),
                "--query", question,
                "--ticker", ticker,
                "--fiscal-year", fiscalYears
        );
        return ProcessRunner.run(cmd, workspace, timeoutSeconds);
    }

}
