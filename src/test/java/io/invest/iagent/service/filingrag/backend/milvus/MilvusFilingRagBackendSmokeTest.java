package io.invest.iagent.service.filingrag.backend.milvus;

import io.invest.iagent.service.filingrag.chunker.FilingChunker;
import io.invest.iagent.service.filingrag.chunker.HtmlTextExtractor;
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
 * Milvus filing RAG backend e2e smoke test.
 * Requires: local Milvus running at 127.0.0.1:19530, Ollama running at localhost:11434 with qwen3-embedding:4b.
 * Enabled only when MILVUS_SMOKE=1 env var is set to avoid failing in CI without services.
 */
@EnabledIfEnvironmentVariable(named = "MILVUS_SMOKE", matches = "1")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MilvusFilingRagBackendSmokeTest {

    private MilvusFilingRagBackend backend;
    private EmbeddingProvider embeddingProvider;
    private FilingChunker chunker;

    private static final String TICKER = "00700";
    private static final String COLLECTION = "invest_filing_rag_smoke";

    @BeforeEach
    void setUp() {
        embeddingProvider = new OllamaEmbeddingProvider("http://localhost:11434/api/embed", "qwen3-embedding:4b", 2560);
        backend = new MilvusFilingRagBackend("http://127.0.0.1:19530", null, COLLECTION, 50, embeddingProvider);
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
        // Find a real PDF in workspace
        Path workspace = Paths.get(System.getProperty("user.dir")).resolve("workspace");
        Path filingsDir = WorkspacePaths.filingsDir(workspace, TICKER);
        if (!Files.isDirectory(filingsDir)) {
            // fall back to a test ticker
            filingsDir = WorkspacePaths.filingsDir(workspace, "BABA");
        }
        assertTrue(Files.isDirectory(filingsDir), "No filings directory found; download a filing first");

        // find a PDF doc
        Path docDir = null;
        try (var ds = Files.list(filingsDir)) {
            docDir = ds.filter(Files::isDirectory).findFirst().orElse(null);
        }
        assertNotNull(docDir, "No document dirs found");
        String documentId = docDir.getFileName().toString();

        PdfTextExtractor pdfExtractor = new PdfTextExtractor();
        HtmlTextExtractor htmlExtractor = new HtmlTextExtractor();
        List<FilingChunk> chunks = null;
        String sourceFileName = null;
        try (var fs = Files.list(docDir)) {
            for (Path f : fs.filter(Files::isRegularFile).toList()) {
                String name = f.getFileName().toString().toLowerCase();
                if (name.endsWith(".pdf")) {
                    List<RawSection> sections = pdfExtractor.extract(f);
                    FilingDocumentMeta meta = FilingDocumentMeta.builder()
                            .ticker(TICKER).documentId(documentId).formType("FY")
                            .fiscalYear(2022).fiscalPeriod("FY").filingDate("2023-03-22").build();
                    chunks = chunker.chunk(meta, f.getFileName().toString(), sections);
                    sourceFileName = f.getFileName().toString();
                    break;
                }
            }
        }
        assertNotNull(chunks, "No PDF found in first document dir");
        assertFalse(chunks.isEmpty(), "chunks should not be empty");
        System.out.println("Extracted " + chunks.size() + " chunks from " + sourceFileName);

        // embed in batch
        List<String> texts = chunks.stream().map(FilingChunk::getContent).toList();
        List<List<Float>> embeddings = embeddingProvider.embedBatch(texts);
        assertEquals(chunks.size(), embeddings.size());

        // Upsert
        backend.upsertDocument(TICKER, documentId, chunks, embeddings);

        // Search
        FilingQuery query = FilingQuery.builder()
                .question("收入增长")
                .ticker(TICKER)
                .topK(3)
                .similarityThreshold(0.2)
                .build();
        List<Float> qembed = embeddingProvider.embed(query.getQuestion());
        FilingQueryResult result = backend.search(query, qembed);
        assertNotNull(result);
        assertFalse(result.getChunks().isEmpty(), "search should return at least one chunk");
        System.out.println("Search returned " + result.getChunks().size() + " chunks");
        for (FilingChunk c : result.getChunks()) {
            System.out.println("  score=" + c.getScore() + " section=" + c.getSectionTitle());
        }
        // cleanup
        int deleted = backend.delete(TICKER, documentId);
        System.out.println("Deleted " + deleted + " entities");
    }
}
