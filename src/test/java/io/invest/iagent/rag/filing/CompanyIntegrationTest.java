package io.invest.iagent.rag.filing;

import io.invest.AgentConfig4Test;
import io.invest.iagent.rag.filing.model.FilingBuildReport;
import io.invest.iagent.rag.filing.model.FilingChunk;
import io.invest.iagent.rag.filing.retrieve.FilingTagKeys;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
public class CompanyIntegrationTest {

    @Autowired
    private FilingBuildService buildService;
    @Autowired
    private FilingQaService qaService;

    private String ticker = "00700" ;

    @Test
    void build_knowledge() {
        FilingBuildReport report = buildService.buildTicker(ticker,false,"2025Q1","2026Q2",null);
        System.out.println("[build] docs=" + report.getDocuments() + " chunks=" + report.getChunks() + " errors=" + report.getErrors());
        assertThat(report.getErrors()).isEmpty();
        assertThat(report.getDocuments()).isGreaterThan(0);
        assertThat(report.getChunks()).isGreaterThan(0);
    }

    @Test
    void retrieve_knowledge() {
        List<FilingChunk> latest = qaService.ask("2025Q2增值服务的收入是多少亿元", ticker, null, 3).getChunks();
        assertThat(latest).isNotEmpty();
        System.out.println("=== latest period results ===");
        latest.forEach(c -> System.out.println(c.getCitation() + " | " + c.getContent()));

        FilingChunk top = latest.get(0);
        assertThat(top.getTags()).containsEntry(FilingTagKeys.TICKER, ticker);
        assertThat(top.getTags()).containsEntry(FilingTagKeys.FISCAL_PERIOD, "2025Q2");
        assertThat(top.getCitation()).contains(ticker).contains("2025Q2").contains("[C1]");
        // 命中文本应来自 Q2（913亿元）
        assertThat(top.getContent()).contains("914");

    }

    @Test
    public void test_skill_direct_tencent() throws Exception {
        ticker = "US.BABA";
        int code = this.runSkill(ticker, "income",32, 160);
        Assertions.assertEquals(0,code);
//        code = this.runSkill(ticker, "balance",32, 160);
//        Assertions.assertEquals(0,code);
//        code = this.runSkill(ticker, "cashflow",32, 160);
//        Assertions.assertEquals(0,code);
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

    @Test
    public void test_filing_down() throws Exception {
        int result = runDownloadSkill(ticker, "2025,2026", 200);
        Assertions.assertEquals(0,result);
    }

    private int runDownloadSkill(String ticker, String fiscalYears, int timeoutSeconds) throws Exception {
        // workspace
        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        Path script = projectRoot.resolve("workspace/skills/futu-filing/scripts/download_announcement.py");
        Assertions.assertTrue(script.toFile().isFile(), "download script missing at " + script);
        // command
        List<String> cmd = List.of(
                "python3", script.toString(),
                "--ticker", ticker,
                "--workspace", projectRoot.resolve("workspace").toString(),
                "--fiscal-years", fiscalYears
        );
        ProcessRunner.Result result = ProcessRunner.run(cmd, projectRoot, timeoutSeconds);
        return result.getExitCode();
    }

}
