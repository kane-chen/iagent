package io.invest.iagent.service.filingrag.backend.ragflow;

import io.invest.iagent.service.filingrag.config.FilingRagConfig;
import io.invest.iagent.service.filingrag.chunker.FilingChunker;
import io.invest.iagent.service.filingrag.chunker.HtmlTextExtractor;
import io.invest.iagent.service.filingrag.chunker.OverlapWindowChunker;
import io.invest.iagent.service.filingrag.chunker.PdfTextExtractor;
import io.invest.iagent.service.filingrag.chunker.RawSectionVO;
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
 *
 * <p>This test is self-contained: it constructs the backend directly (no Spring context),
 * so it is not affected by {@code app.filing-rag.backend}. All endpoints/keys/model
 * are configured inline in {@link #setUp()}.
 *
 * <p>Requires: local RAGFlow at localhost:9380, with an API key exposed via
 * {@code RAGFLOW_API_KEY} env var; Ollama at localhost:11434 for query embedding
 * (RAGFlow embeds chunks server-side with its own configured model).
 * Enable by setting env var {@code RAGFLOW_API_KEY=<your-key>}
 * (e.g. {@code RAGFLOW_API_KEY=ragflow-xxx mvn test -Dtest=RagflowFilingRagBackendTest}).
 */
@EnabledIfEnvironmentVariable(named = "RAGFLOW_API_KEY", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RagflowFilingRagBackendTest {

    private RagflowFilingRagBackend backend;
    private EmbeddingProvider embeddingProvider;
    private FilingChunker chunker;

    private static final String TICKER = "00700";
    private static final String RAGFLOW_BASE_URL = "http://localhost:9380";
    private static final String OLLAMA_EMBED_URL = "http://localhost:11434/api/embed";
    private static final String EMBED_MODEL = "qwen3-embedding:4b";
    private static final int EMBED_DIM = 2560;

    @BeforeEach
    void setUp() {
        FilingRagConfig.Ragflow cfg = new FilingRagConfig.Ragflow();
        cfg.setBaseUrl(RAGFLOW_BASE_URL);
        cfg.setApiKey(System.getenv("RAGFLOW_API_KEY"));
        cfg.setDatasetPrefix("filing_rag_smoke_");
        cfg.setSimilarityThreshold(0.2);
        cfg.setKeywordWeight(0.3);
        cfg.setParsePollTimeoutSeconds(120);
        cfg.setParsePollIntervalSeconds(3);
        cfg.setRequestTimeoutSeconds(60);

        embeddingProvider = new OllamaEmbeddingProvider(OLLAMA_EMBED_URL, EMBED_MODEL, EMBED_DIM);
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

        Path docDir;
        try (var ds = Files.list(filingsDir)) {
            docDir = ds.filter(Files::isDirectory).findFirst().orElse(null);
        }
        assertNotNull(docDir);
        String documentId = docDir.getFileName().toString();

        PdfTextExtractor pdfExtractor = new PdfTextExtractor();
        HtmlTextExtractor htmlExtractor = new HtmlTextExtractor();
        List<FilingChunk> chunks = null;
        try (var fs = Files.list(docDir)) {
            for (Path f : fs.filter(Files::isRegularFile).toList()) {
                String name = f.getFileName().toString().toLowerCase();
                if (name.endsWith(".pdf")) {
                    List<RawSectionVO> sections = pdfExtractor.extract(f);
                    FilingDocumentMeta meta = FilingDocumentMeta.builder()
                            .ticker(TICKER).documentId(documentId).formType("FY")
                            .fiscalYear(2022).fiscalPeriod("FY").filingDate("2023-03-22").build();
                    chunks = chunker.chunk(meta, f.getFileName().toString(), sections);
                    break;
                } else if (name.endsWith(".htm") || name.endsWith(".html")) {
                    List<RawSectionVO> sections = htmlExtractor.extract(f);
                    FilingDocumentMeta meta = FilingDocumentMeta.builder()
                            .ticker(TICKER).documentId(documentId).formType("FY")
                            .fiscalYear(2025).fiscalPeriod("FY").filingDate("2025-01-01").build();
                    chunks = chunker.chunk(meta, f.getFileName().toString(), sections);
                    break;
                }
            }
        }
        assertNotNull(chunks, "No PDF/HTML found in first document dir: " + docDir);
        assertFalse(chunks.isEmpty());
        System.out.println("Extracted " + chunks.size() + " chunks");

        // embeddings param is unused by RAGFlow backend (embedding happens server-side)
        backend.upsertDocument(TICKER, documentId, chunks, List.of());

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

        int deleted = backend.delete(TICKER, documentId);
        System.out.println("Deleted " + deleted + " documents");
    }
}
