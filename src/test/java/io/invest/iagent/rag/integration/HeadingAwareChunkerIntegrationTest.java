package io.invest.iagent.rag.integration;

import io.invest.iagent.rag.chunking.dto.ParsedChunk;
import io.invest.iagent.rag.chunking.plugins.HeadingAwareChunker;
import io.invest.iagent.rag.model.ChunkingConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HeadingAwareChunker 集成测试：验证标题识别、面包屑、父子分块、滑动窗口。
 * 不依赖外部服务。
 */
class HeadingAwareChunkerIntegrationTest {

    private final HeadingAwareChunker chunker = new HeadingAwareChunker();

    @Test
    void test_markdown_headings_produce_breadcrumbs() {
        String markdown = """
                # PART I
                This is the introduction paragraph describing the business.

                ## Item 1. Business
                The company designs and sells consumer electronics.
                It operates in multiple segments.

                ### Products
                The product portfolio includes phones, laptops and wearables.

                ## Item 1A. Risk Factors
                Supply chain concentration is a major risk.
                """;

        ChunkingConfig config = new ChunkingConfig();
        config.setEnableParentChild(false);
        config.setChildChunkSize(512);

        List<ParsedChunk> chunks = chunker.split(markdown, config);

        assertThat(chunks).isNotEmpty();
        // 至少出现面包屑 "Item 1. Business > Products"
        assertThat(chunks)
                .anyMatch(c -> c.getContextHeader() != null
                        && c.getContextHeader().contains("Products")
                        && c.getContextHeader().contains("Item 1. Business"));
    }

    @Test
    void test_parent_child_chunks_linked() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Section A\n");
        for (int i = 0; i < 50; i++) {
            sb.append("This is sentence number ").append(i)
                    .append(" in section A, describing the business operations.\n");
        }
        sb.append("\n# Section B\n");
        for (int i = 0; i < 50; i++) {
            sb.append("Risk factor line ").append(i)
                    .append(" explaining potential adverse outcomes.\n");
        }

        ChunkingConfig config = new ChunkingConfig();
        config.setEnableParentChild(true);
        config.setChildSize(200);
        config.setParentSize(1024);
        config.setChunkOverlap(40);

        List<ParsedChunk> chunks = chunker.split(sb.toString(), config);

        List<ParsedChunk> parents = chunks.stream()
                .filter(c -> "parent_text".equals(c.getType())).toList();
        List<ParsedChunk> children = chunks.stream()
                .filter(c -> "text".equals(c.getType())).toList();

        assertThat(parents).isNotEmpty();
        assertThat(children).isNotEmpty();
        // 每个 child 都有 parentChunkId，且指向一个存在的 parent
        for (ParsedChunk child : children) {
            assertThat(child.getParentChunkId()).isNotBlank();
            assertThat(parents).anyMatch(p -> p.getId().equals(child.getParentChunkId()));
        }
        // ID 确定性：同样输入同样 ID
        List<ParsedChunk> again = chunker.split(sb.toString(), config);
        assertThat(again).extracting(ParsedChunk::getId)
                .containsExactlyElementsOf(chunks.stream().map(ParsedChunk::getId).toList());
    }

    @Test
    void test_empty_input_returns_empty() {
        ChunkingConfig config = new ChunkingConfig();
        assertThat(chunker.split("", config)).isEmpty();
        assertThat(chunker.split(null, config)).isEmpty();
    }
}
