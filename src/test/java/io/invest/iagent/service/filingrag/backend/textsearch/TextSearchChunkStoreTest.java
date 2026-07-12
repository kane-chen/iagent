package io.invest.iagent.service.filingrag.backend.textsearch;

import io.invest.iagent.service.filingrag.model.FilingChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextSearchChunkStoreTest {

    @TempDir
    Path tempWorkspace;

    private TextSearchChunkStore store;

    @BeforeEach
    void setUp() {
        store = new TextSearchChunkStore(tempWorkspace);
    }

    @Test
    void saveAndLoadRoundTrip() throws Exception {
        List<FilingChunk> chunks = List.of(
                FilingChunk.builder()
                        .chunkId("c1").ticker("TEST").documentId("doc1")
                        .formType("FY").fiscalYear(2024).fiscalPeriod("FY")
                        .sectionTitle("收入")
                        .content("营业收入1000亿")
                        .pageNumber(10)
                        .metadata(new HashMap<>())
                        .build()
        );
        store.saveChunks("TEST", "doc1", chunks);

        List<FilingChunk> loaded = store.loadChunks("TEST", "doc1");
        assertEquals(1, loaded.size());
        assertEquals("c1", loaded.get(0).getChunkId());
        assertEquals("收入", loaded.get(0).getSectionTitle());
        assertEquals("营业收入1000亿", loaded.get(0).getContent());
        assertEquals(2024, loaded.get(0).getFiscalYear());
    }

    @Test
    void loadNonExistentReturnsEmpty() {
        List<FilingChunk> loaded = store.loadChunks("NOPE", "nothing");
        assertTrue(loaded.isEmpty());
    }

    @Test
    void deleteRemovesFile() throws Exception {
        List<FilingChunk> chunks = List.of(
                FilingChunk.builder().chunkId("d1").content("test").build()
        );
        store.saveChunks("TEST", "docdel", chunks);
        assertTrue(store.hasChunks("TEST", "docdel"));

        int deleted = store.deleteChunks("TEST", "docdel");
        assertEquals(1, deleted);
        assertFalse(store.hasChunks("TEST", "docdel"));
    }

    @Test
    void deleteNonExistentReturnsZero() {
        int deleted = store.deleteChunks("NOPE", "nothing");
        assertEquals(0, deleted);
    }

    @Test
    void saveChunksOverwritesPrevious() throws Exception {
        List<FilingChunk> first = List.of(
                FilingChunk.builder().chunkId("a").content("first").build()
        );
        List<FilingChunk> second = List.of(
                FilingChunk.builder().chunkId("b").content("second").build(),
                FilingChunk.builder().chunkId("c").content("third").build()
        );
        store.saveChunks("TEST", "overwrite", first);
        store.saveChunks("TEST", "overwrite", second);

        List<FilingChunk> loaded = store.loadChunks("TEST", "overwrite");
        assertEquals(2, loaded.size());
        assertEquals("b", loaded.get(0).getChunkId());
    }
}
