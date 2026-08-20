package io.invest.iagent.rag.filing;

import io.invest.AgentConfig4Test;
import io.invest.iagent.rag.filing.model.FilingBuildReport;
import io.invest.iagent.rag.filing.model.FilingChunk;
import io.invest.iagent.rag.filing.retrieve.FilingTagKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 *  端到端集成测试：
 */
@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
class FilingIntegrationTest {

    private static final String TICKER = "00700";

    @Autowired
    private FilingBuildService buildService;
    @Autowired
    private FilingQaService qaService;

    @Test
    void build_then_retrieve_with_tags_and_citation() {
        // ---- 1. 建库 ----
//        FilingBuildReport report = buildService.buildTicker(TICKER, false);
//        System.out.println("[build] docs=" + report.getDocuments() + " chunks=" + report.getChunks() + " errors=" + report.getErrors());
//        assertThat(report.getErrors()).isEmpty();
//        assertThat(report.getDocuments()).isGreaterThan(0);
//        assertThat(report.getChunks()).isGreaterThan(0);

        // ---- 2. 检索：
        List<FilingChunk> latest = qaService.ask("增值服务的收入是多少亿元", TICKER, "2025Q2", 5);
        assertThat(latest).isNotEmpty();
        System.out.println("=== latest period results ===");
        latest.forEach(c -> System.out.println(c.getCitation() + " | " + c.getContent()));

        FilingChunk top = latest.get(0);
        assertThat(top.getTags()).containsEntry(FilingTagKeys.TICKER, TICKER);
        assertThat(top.getTags()).containsEntry(FilingTagKeys.FISCAL_PERIOD, "2025Q2");
        assertThat(top.getTags()).containsKey(FilingTagKeys.HEADING);
        assertThat(top.getCitation()).contains(TICKER).contains("2025Q2").contains("[C1]");
        // 命中文本应来自 Q2（913亿元）
        assertThat(top.getContent()).contains("914");

    }
}
