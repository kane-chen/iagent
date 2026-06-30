package io.invest.iagent.service.kb.chunk;

import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.service.kb.model.FilingChunkingContext;
import io.invest.iagent.service.kb.model.KnowledgeBaseChunkDTO;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilingChunkingStrategyByFixedWindowTest {

    @Test
    void chunkPrefersParagraphBoundary() {
        FilingChunkingStrategyByFixedWindow strategy = new FilingChunkingStrategyByFixedWindow(new FilingChunkFactory(), 80, 10, 20);
        String first = "First paragraph has enough words and ends cleanly.";
        String second = "Second paragraph should start from its own sentence and remain readable.";

        List<KnowledgeBaseChunkDTO> chunks = strategy.chunk(context(), first + "\n\n" + second);

        assertFalse(chunks.isEmpty());
        assertEquals(first, chunks.get(0).getText());
        assertFalse(chunks.get(0).getText().contains("Second"));
    }

    @Test
    void chunkPrefersSentenceBoundary() {
        FilingChunkingStrategyByFixedWindow strategy = new FilingChunkingStrategyByFixedWindow(new FilingChunkFactory(), 75, 10, 20);
        String first = "Revenue increased because demand improved.";
        String second = "Operating expenses also increased during the reporting period.";

        List<KnowledgeBaseChunkDTO> chunks = strategy.chunk(context(), first + " " + second);

        assertFalse(chunks.isEmpty());
        assertEquals(first, chunks.get(0).getText());
    }

    @Test
    void chunkFallsBackToWordBoundary() {
        FilingChunkingStrategyByFixedWindow strategy = new FilingChunkingStrategyByFixedWindow(new FilingChunkFactory(), 48, 8, 20);
        String text = "abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz";

        List<KnowledgeBaseChunkDTO> chunks = strategy.chunk(context(), text);

        assertFalse(chunks.isEmpty());
        assertEquals("abcdefghijklmnopqrstuvwxyz", chunks.get(0).getText());
    }

    @Test
    void overlapStartsAtReadableBoundary() {
        FilingChunkingStrategyByFixedWindow strategy = new FilingChunkingStrategyByFixedWindow(new FilingChunkFactory(), 80, 25, 20);
        String text = "First sentence contains important context. Second sentence contains more details. Third sentence closes.";

        List<KnowledgeBaseChunkDTO> chunks = strategy.chunk(context(), text);

        assertTrue(chunks.size() > 1);
        assertFalse(Character.isLowerCase(chunks.get(1).getText().charAt(0)));
        assertFalse(chunks.get(1).getText().startsWith("etails"));
    }

    private FilingChunkingContext context() {
        JSONObject meta = new JSONObject();
        meta.put("form_type", "10-K");
        meta.put("fiscal_year", 2025);
        meta.put("filing_date", "2025-01-01");
        meta.put("source_fingerprint", "test");
        return FilingChunkingContext.builder()
                .ticker("TEST")
                .documentId("doc")
                .sourceFile(Paths.get("test.txt"))
                .meta(meta)
                .chunkType("text")
                .build();
    }
}
