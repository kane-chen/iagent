package io.invest.iagent.service.filingrag.chunker;

import io.invest.iagent.service.filingrag.model.FilingChunk;
import io.invest.iagent.service.filingrag.model.FilingDocumentMeta;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OverlapWindowChunkerTest {

    private final OverlapWindowChunker chunker = new OverlapWindowChunker(400, 600, 80);

    @Test
    void emptySections() {
        List<FilingChunk> out = chunker.chunk(meta(), "x.html", List.of());
        assertTrue(out.isEmpty());
    }

    @Test
    void singleShortSection_oneChunk() {
        RawSectionVO sec = RawSectionVO.builder().title("Overview").content("Hello world, this is a short section.").build();
        List<FilingChunk> out = chunker.chunk(meta(), "x.html", List.of(sec));
        assertEquals(1, out.size());
        assertTrue(out.get(0).getContent().startsWith("Section: Overview\n"));
        assertNotNull(out.get(0).getChunkId());
        assertEquals("BABA", out.get(0).getTicker());
    }

    @Test
    void longSection_producesMultipleChunksWithOverlap() {
        // Repeat a sentence many times to exceed maxTokens
        String sentence = "Revenue grew strongly across all business segments driven by cloud computing and e-commerce. ";
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 100; i++) body.append(sentence);
        RawSectionVO sec = RawSectionVO.builder().title("MD&A").content(body.toString()).build();
        List<FilingChunk> out = chunker.chunk(meta(), "x.html", List.of(sec));
        assertTrue(out.size() > 1, "Expected multiple chunks but got " + out.size());
        // All chunks should have chunkId and content
        for (FilingChunk c : out) {
            assertNotNull(c.getChunkId());
            assertNotNull(c.getContent());
            assertTrue(c.getContent().startsWith("Section: MD&A\n"));
            int est = OverlapWindowChunker.estimateTokens(c.getContent());
            assertTrue(est <= 800, "chunk exceeds max tokens: " + est);
        }
    }

    @Test
    void chunkIdsAreDeterministic() {
        RawSectionVO sec = RawSectionVO.builder().title("S").content("Deterministic content").build();
        List<FilingChunk> a = chunker.chunk(meta(), "x.html", List.of(sec));
        List<FilingChunk> b = chunker.chunk(meta(), "x.html", List.of(sec));
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) assertEquals(a.get(i).getChunkId(), b.get(i).getChunkId());
    }

    @Test
    void chunkIdsAre16HexChars() {
        RawSectionVO sec = RawSectionVO.builder().title("S").content("Content").build();
        List<FilingChunk> out = chunker.chunk(meta(), "x.html", List.of(sec));
        for (FilingChunk c : out) {
            assertTrue(c.getChunkId().matches("[0-9a-f]{16}"), "chunkId not 16 hex: " + c.getChunkId());
        }
    }

    @Test
    void chunkIdsUniqueAcrossSections() {
        RawSectionVO s1 = RawSectionVO.builder().title("S1").content("alpha").build();
        RawSectionVO s2 = RawSectionVO.builder().title("S2").content("beta").build();
        List<FilingChunk> out = chunker.chunk(meta(), "x.html", List.of(s1, s2));
        Set<String> ids = new HashSet<>();
        for (FilingChunk c : out) ids.add(c.getChunkId());
        assertEquals(out.size(), ids.size(), "chunkIds not unique");
    }

    @Test
    void chineseTextEstimation() {
        // Chinese text: roughly 1.5 chars per token → 30 chars ≈ 20 tokens
        String zh = "公司收入增长强劲，主要由云计算和核心商业板块驱动，管理层对未来展望乐观。";
        int est = OverlapWindowChunker.estimateTokens(zh);
        assertTrue(est > 10 && est < 50, "Expected ~20 tokens, got " + est);
    }

    private FilingDocumentMeta meta() {
        return FilingDocumentMeta.builder()
                .ticker("BABA").documentId("doc1")
                .formType("FY").fiscalYear(2024).fiscalPeriod("FY")
                .filingDate("2025-05-15")
                .build();
    }
}
