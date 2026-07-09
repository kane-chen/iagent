package io.invest.iagent.service.filingrag.backend.ragflow;

import io.invest.iagent.service.filingrag.FilingRagConfig;
import io.invest.iagent.service.filingrag.chunker.FilingChunker;
import io.invest.iagent.service.filingrag.chunker.OverlapWindowChunker;
import io.invest.iagent.service.filingrag.chunker.PdfTextExtractor;
import io.invest.iagent.service.filingrag.chunker.RawSection;
import io.invest.iagent.service.filingrag.embed.EmbeddingProvider;
import io.invest.iagent.service.filingrag.embed.OllamaEmbeddingProvider;
import io.invest.iagent.service.filingrag.model.FilingChunk;
import io.invest.iagent.service.filingrag.model.FilingDocumentMeta;
import io.invest.iagent.service.filingrag.model.FilingQuery;
import io.invest.iagent.service.filingrag.model.FilingQueryResult;
import io.invest.iagent.utils.WorkspacePaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RAGFlow filing RAG backend e2e smoke test.
 * Requires: local RAGFlow at localhost:9380 with API key set via RAGFLOW_API_KEY env var.
 * Ollama is required for query embedding (we use Java-side embedding for query; RAGFlow uses its own model for chunk embedding).
 * Enabled only when RAGFLOW_API_KEY env var is set.
 */
@EnabledIfEnvironmentVariable(named = "RAGFLOW_API_KEY", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RagflowFilingRagBackendSmokeTest {

    private RagflowFilingRagBackend backend;
    private EmbeddingProvider embeddingProvider;
    private FilingChunker chunker;

    private static final String TICKER = "00700";

    @BeforeEach
    void setUp() {
        FilingRagConfig.Ragflow cfg = new FilingRagConfig.Ragflow();
        cfg.setBaseUrl("http://localhost:9380");
        cfg.setApiKey(System.getenv("RAGFLOW_API_KEY"));
        cfg.setDatasetPrefix("filing_rag_smoke_");
        cfg.setSimilarityThreshold(0.2);
        cfg.setKeywordWeight(0.3);
        cfg.setParsePollTimeoutSeconds(120);
        cfg.setParsePollIntervalSeconds(3);
        cfg.setRequestTimeoutSeconds(60);

        embeddingProvider = new OllamaEmbeddingProvider("http://localhost:11434/api/embed", "qwen3-embedding:4b", 2560);
        backend = new RagflowFilingRagBackend(cfg, embeddingProvider);
        chunker = new OverlapWindowChunker(400, 600, 80);
    }

    @Test
    @Order(1)
    void healthCheck() {
        assertDoesNotThrow(() -> backend.healthCheck());
    }

    @Test
    @Order(2)
    void buildAndSearch() throws Exception {
        Path workspace = Paths.get(System.getProperty("user.dir")).resolve("workspace");
        Path filingsDir = WorkspacePaths.filingsDir(workspace, TICKER);
        if (!Files.isDirectory(filingsDir)) {
            filingsDir = WorkspacePaths.filingsDir(workspace, "BABA");
        }
        assertTrue(Files.isDirectory(filingsDir), "No filings directory found");
        Path docDir = null;
        try (var ds = Files.list(filingsDir)) {
            docDir = ds.filter(Files::isDirectory).findFirst().orElse(null);
        }
        assertNotNull(docDir);
        String documentId = docDir.getFileName().toString();

        PdfTextExtractor pdfExtractor = new PdfTextExtractor();
        List<FilingChunk> chunks = null;
        try (var fs = Files.list(docDir)) {
            for (Path f : fs.filter(Files::isRegularFile).toList()) {
                if (f.getFileName().toString().toLowerCase().endsWith(".pdf")) {
                    List<RawSection> sections = pdfExtractor.extract(f);
                    FilingDocumentMeta meta = FilingDocumentMeta.builder()
                            .ticker(TICKER).documentId(documentId).formType("FY")
                            .fiscalYear(2022).fiscalPeriod("FY").filingDate("2023-03-22").build();
                    chunks = chunker.chunk(meta, f.getFileName().toString(), sections);
                    break;
                }
            }
        }
        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        System.out.println("Extracted " + chunks.size() + " chunks");

        // upsert — embeddings param is unused by RAGFlow backend (it embeds server-side), but pass empty list
        List<List<Float>> embeddings = List.of();
        backend.upsertDocument(TICKER, documentId, chunks, embeddings);

        // Search
        FilingQuery query = FilingQuery.builder()
                .question("revenue growth")
                .ticker(TICKER)
                .topK(3)
                .similarityThreshold(0.2)
                .build();
        List<Float> qembed = embeddingProvider.embed(query.getQuestion());
        FilingQueryResult result = backend.search(query, qembed);
        assertNotNull(result);
        System.out.println("Search returned " + result.getChunks().size() + " chunks");
        for (FilingChunk c : result.getChunks()) {
            System.out.println("  score=" + c.getScore() + " section=" + c.getSectionTitle());
        }

        // cleanup
        int deleted = backend.delete(TICKER, documentId);
        System.out.println("Deleted " + deleted + " documents");
    }
}
