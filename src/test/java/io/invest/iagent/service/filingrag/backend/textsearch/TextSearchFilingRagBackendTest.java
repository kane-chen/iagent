package io.invest.iagent.service.filingrag.backend.textsearch;

import io.invest.iagent.service.filingrag.config.FilingRagConfig;
import io.invest.iagent.service.filingrag.model.FilingChunk;
import io.invest.iagent.service.filingrag.model.FilingQuery;
import io.invest.iagent.service.filingrag.model.FilingQueryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TextSearchFilingRagBackend 单元测试。
 * 使用临时目录模拟workspace，不依赖外部服务（LLM client为null，自动降级为词典模式）。
 */
class TextSearchFilingRagBackendTest {

    @TempDir
    Path tempWorkspace;

    private TextSearchFilingRagBackend backend;
    private FilingRagConfig.TextSearch config;

    @BeforeEach
    void setUp() {
        config = new FilingRagConfig.TextSearch();
        config.setRerankTopN(10);
        config.setFullTextMaxChunks(50);
        config.setFullTextFallback(true);
        config.setMinKeywordScore(0.01);
        config.setLlmTimeoutSeconds(10);
        // 传入null LLM client → 词典模式，不需要外部LLM服务
        backend = new TextSearchFilingRagBackend(tempWorkspace, config, null);
    }

    @Test
    void nameReturnsTextsearch() {
        assertEquals("textsearch", backend.name());
    }

    @Test
    void requiresEmbeddingsReturnsFalse() {
        assertFalse(backend.requiresEmbeddings());
    }

    @Test
    void healthCheckCreatesDirectories() {
        assertDoesNotThrow(() -> backend.healthCheck());
        assertTrue(Files.isDirectory(tempWorkspace.resolve("portfolio")));
    }

    @Test
    void upsertAndSearch() {
        String ticker = "TEST";
        String docId = "fil_test_2024_FY";

        // 准备测试chunks
        List<FilingChunk> chunks = new ArrayList<>();
        chunks.add(FilingChunk.builder()
                .chunkId("c1").ticker(ticker).documentId(docId)
                .formType("FY").fiscalYear(2024).fiscalPeriod("FY")
                .sectionTitle("营业收入")
                .content("2024年公司营业收入达到1000亿元，同比增长15%。其中云计算业务收入200亿元。")
                .score(0.0).metadata(new HashMap<>())
                .build());
        chunks.add(FilingChunk.builder()
                .chunkId("c2").ticker(ticker).documentId(docId)
                .formType("FY").fiscalYear(2024).fiscalPeriod("FY")
                .sectionTitle("净利润")
                .content("2024年净利润为200亿元，同比增长10%。每股收益1.5元。")
                .score(0.0).metadata(new HashMap<>())
                .build());
        chunks.add(FilingChunk.builder()
                .chunkId("c3").ticker(ticker).documentId(docId)
                .formType("FY").fiscalYear(2024).fiscalPeriod("FY")
                .sectionTitle("风险因素")
                .content("公司面临市场竞争加剧、宏观经济下行等风险因素。")
                .score(0.0).metadata(new HashMap<>())
                .build());

        // 创建filings目录和meta.json（search需要）
        Path filingsDir = tempWorkspace.resolve("portfolio").resolve(ticker).resolve("filings").resolve(docId);
        try {
            Files.createDirectories(filingsDir);
            String metaJson = """
                    {"formType":"FY","fiscalYear":2024,"fiscalPeriod":"FY","filingDate":"2025-03-01"}
                    """;
            Files.writeString(filingsDir.resolve("meta.json"), metaJson);
        } catch (Exception e) {
            fail("Failed to setup test filings: " + e.getMessage());
        }

        // upsert
        backend.upsertDocument(ticker, docId, chunks, List.of());

        // 验证chunks.json文件存在
        Path chunksFile = tempWorkspace.resolve("portfolio").resolve(ticker)
                .resolve("processed").resolve(docId).resolve("chunks.json");
        assertTrue(Files.exists(chunksFile), "chunks.json should exist after upsert");

        // 搜索：关于收入的问题
        FilingQuery query = FilingQuery.builder()
                .question("2024年营业收入是多少？")
                .ticker(ticker)
                .topK(3)
                .build();
        FilingQueryResult result = backend.search(query, List.of());

        assertNotNull(result);
        assertEquals("textsearch", result.getBackend());
        assertEquals(ticker, result.getTicker());
        assertFalse(result.getChunks().isEmpty(), "应该找到相关chunks");
        // 第一个chunk应该是关于营业收入的
        assertTrue(result.getChunks().get(0).getContent().contains("营业收入"),
                "第一个chunk应该包含营业收入内容");
    }

    @Test
    void deleteRemovesChunks() {
        String ticker = "TEST";
        String docId = "fil_test_delete";

        List<FilingChunk> chunks = List.of(FilingChunk.builder()
                .chunkId("d1").ticker(ticker).documentId(docId)
                .content("test content").build());

        Path filingsDir = tempWorkspace.resolve("portfolio").resolve(ticker).resolve("filings").resolve(docId);
        try {
            Files.createDirectories(filingsDir);
            Files.writeString(filingsDir.resolve("meta.json"),
                    "{\"formType\":\"FY\",\"fiscalYear\":2024,\"fiscalPeriod\":\"FY\"}");
        } catch (Exception e) {
            fail(e.getMessage());
        }

        backend.upsertDocument(ticker, docId, chunks, List.of());
        Path chunksFile = tempWorkspace.resolve("portfolio").resolve(ticker)
                .resolve("processed").resolve(docId).resolve("chunks.json");
        assertTrue(Files.exists(chunksFile));

        int deleted = backend.delete(ticker, docId);
        assertEquals(1, deleted);
        assertFalse(Files.exists(chunksFile), "chunks.json should be deleted");
    }

    @Test
    void searchWithNoDocumentsReturnsEmpty() {
        FilingQuery query = FilingQuery.builder()
                .question("收入")
                .ticker("NONEXIST")
                .topK(5)
                .build();
        FilingQueryResult result = backend.search(query, List.of());
        assertNotNull(result);
        assertTrue(result.getChunks().isEmpty(), "无文档时应返回空结果");
    }

    @Test
    void searchFiltersByFiscalYear() {
        String ticker = "TEST";
        // doc1: 2023FY
        String docId2023 = "fil_test_2023_FY";
        // doc2: 2024FY
        String docId2024 = "fil_test_2024_FY";

        List<FilingChunk> chunks2023 = List.of(FilingChunk.builder()
                .chunkId("y23").ticker(ticker).documentId(docId2023)
                .formType("FY").fiscalYear(2023).fiscalPeriod("FY")
                .sectionTitle("收入").content("2023年收入500亿元").build());
        List<FilingChunk> chunks2024 = List.of(FilingChunk.builder()
                .chunkId("y24").ticker(ticker).documentId(docId2024)
                .formType("FY").fiscalYear(2024).fiscalPeriod("FY")
                .sectionTitle("收入").content("2024年收入800亿元，同比增长60%").build());

        try {
            setupTestDocument(ticker, docId2023, "FY", 2023, "FY");
            setupTestDocument(ticker, docId2024, "FY", 2024, "FY");
        } catch (Exception e) {
            fail(e.getMessage());
        }

        backend.upsertDocument(ticker, docId2023, chunks2023, List.of());
        backend.upsertDocument(ticker, docId2024, chunks2024, List.of());

        // 只查2024年
        FilingQuery query = FilingQuery.builder()
                .question("收入增长")
                .ticker(ticker)
                .fromFiscalYear(2024)
                .toFiscalYear(2024)
                .topK(5)
                .build();
        FilingQueryResult result = backend.search(query, List.of());

        // 应该只返回2024年的chunk
        for (FilingChunk c : result.getChunks()) {
            assertEquals(2024, c.getFiscalYear(), "应只包含2024年的chunks");
        }
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private void setupTestDocument(String ticker, String docId, String formType,
                                   Integer fiscalYear, String fiscalPeriod) throws Exception {
        Path filingsDir = tempWorkspace.resolve("portfolio").resolve(ticker)
                .resolve("filings").resolve(docId);
        Files.createDirectories(filingsDir);
        String meta = String.format(
                "{\"formType\":\"%s\",\"fiscalYear\":%d,\"fiscalPeriod\":\"%s\"}",
                formType, fiscalYear, fiscalPeriod);
        Files.writeString(filingsDir.resolve("meta.json"), meta);
    }
}
