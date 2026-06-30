package io.invest.iagent.service.kb;

import io.invest.iagent.service.kb.chunk.FilingChunkingStrategyFactory;
import io.invest.iagent.service.kb.summary.FilingChunkSummarizationService;
import io.invest.iagent.service.kb.util.FilingSourceSelector;
import io.invest.iagent.service.kb.model.KnowledgeBaseChunkDTO;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class FilingPreprocessServiceTest {

    private FilingPreprocessService service;

    @BeforeEach
    public void before(){
        Path workspace = Paths.get(System.getProperty("user.dir")).resolve("./test-filings");
        service =  new FilingPreprocessService(workspace);
    }

    @Test
    public void handle_sec_li() throws IOException {
        List<KnowledgeBaseChunkDTO> chunks = service.preprocess("LI","fil_0001104659-25-023764",true) ;
        Assertions.assertNotNull(chunks);
    }

    @Test
    public void handle_sec_li2() throws IOException {
        List<KnowledgeBaseChunkDTO> chunks = service.preprocess("LI",false) ;
        Assertions.assertNotNull(chunks);
    }

    @Test
    void preprocessHtmlFilingCreatesSummariesWhenConfigured(@TempDir Path workspace) throws IOException {
        seedHtmlFiling(workspace);
        FilingChunkSummarizationService summarizer = new FilingChunkSummarizationService() {
            @Override
            public Optional<String> summarize(KnowledgeBaseChunkDTO chunk) {
                return Optional.of("summary: " + chunk.getSectionTitle());
            }

            @Override
            public String model() {
                return "test-summary-model";
            }
        };
        FilingPreprocessService service = new FilingPreprocessService(workspace, new FilingSourceSelector(),
                FilingChunkingStrategyFactory.fromEnv(), summarizer);

        List<KnowledgeBaseChunkDTO> chunks = service.preprocess("TESTX", null, true);
        List<KnowledgeBaseChunkDTO> cached = service.preprocess("TESTX", null, false);

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(c -> StringUtils.startsWith(String.valueOf(c.getMetadata().get("chunk_summary")), "summary:")));
        assertTrue(chunks.stream().allMatch(c -> "test-summary-model".equals(c.getMetadata().get("chunk_summary_model"))));
        assertTrue(Files.readString(workspace.resolve("portfolio/TESTX/processed/fil_test_001/kb_chunks.jsonl")).contains("chunk_summary"));
        assertTrue(cached.stream().allMatch(c -> StringUtils.isNotBlank(String.valueOf(c.getMetadata().get("chunk_summary")))));
    }

    @Test
    void preprocessHtmlFilingCreatesChunksAndProcessedFiles(@TempDir Path workspace) throws IOException {
        Path filingDir = workspace.resolve("portfolio").resolve("TESTX").resolve("filings").resolve("fil_test_001");
        Files.createDirectories(filingDir);
        Files.writeString(filingDir.resolve("meta.json"), """
                {
                  "document_id": "fil_test_001",
                  "ticker": "TESTX",
                  "form_type": "10-K",
                  "fiscal_year": 2025,
                  "fiscal_period": "FY",
                  "filing_date": "2026-02-01",
                  "ingest_complete": true,
                  "is_deleted": false,
                  "source_fingerprint": "abc123",
                  "primary_document": "test.htm",
                  "files": [{"name":"test.htm"},{"name":"test_htm.xml"},{"name":"test_cal.xml"},{"name":"FilingSummary.xml"}]
                }
                """);
        Files.writeString(filingDir.resolve("test.htm"), """
                <html><body><h1>Management Discussion</h1>
                <p>Operating profit declined because vehicle margin decreased and research expenses increased.</p>
                <table><tr><td>Total revenue</td><td>100</td></tr></table>
                </body></html>
                """);
        Files.writeString(filingDir.resolve("test_htm.xml"), "<xbrl>ignore as kb text</xbrl>");
        Files.writeString(filingDir.resolve("test_cal.xml"), "<calculationLink />");
        Files.writeString(filingDir.resolve("FilingSummary.xml"), "<FilingSummary />");

        FilingPreprocessService service = new FilingPreprocessService(workspace);
        List<KnowledgeBaseChunkDTO> chunks = service.preprocess("TESTX", null, true);

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(c -> "test.htm".equals(c.getSourceFileName())));
        assertTrue(chunks.stream().anyMatch(c -> c.getText().contains("Operating profit declined")));
        assertTrue(chunks.stream().allMatch(c -> StringUtils.isNotBlank(c.getCategory())));
        assertTrue(chunks.stream().anyMatch(c -> "financial_operations".equals(c.getCategory())));
        assertTrue(chunks.stream().allMatch(c -> c.getMetadata() != null && c.getCategory().equals(c.getMetadata().get("content_category"))));
        assertTrue(chunks.stream().allMatch(c -> c.getMetadata().containsKey("category_confidence")));
        assertTrue(chunks.stream().allMatch(c -> c.getMetadata().containsKey("category_source")));
        assertTrue(Files.exists(workspace.resolve("portfolio/TESTX/processed/fil_test_001/kb_chunks.jsonl")));
        assertTrue(Files.exists(workspace.resolve("portfolio/TESTX/processed/fil_test_001/kb_meta.json")));
    }

    private void seedHtmlFiling(Path workspace) throws IOException {
        Path filingDir = workspace.resolve("portfolio").resolve("TESTX").resolve("filings").resolve("fil_test_001");
        Files.createDirectories(filingDir);
        Files.writeString(filingDir.resolve("meta.json"), """
                {
                  "document_id": "fil_test_001",
                  "ticker": "TESTX",
                  "form_type": "10-K",
                  "fiscal_year": 2025,
                  "fiscal_period": "FY",
                  "filing_date": "2026-02-01",
                  "ingest_complete": true,
                  "is_deleted": false,
                  "source_fingerprint": "abc123",
                  "primary_document": "test.htm",
                  "files": [{"name":"test.htm"}]
                }
                """);
        Files.writeString(filingDir.resolve("test.htm"), """
                <html><body><h1>Management Discussion</h1>
                <p>Operating profit declined because vehicle margin decreased and research expenses increased.</p>
                </body></html>
                """);
    }
}
