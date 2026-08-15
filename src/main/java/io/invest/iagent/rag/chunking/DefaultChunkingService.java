package io.invest.iagent.rag.chunking;

import io.invest.iagent.rag.chunking.dto.ParsedChunk;
import io.invest.iagent.rag.chunking.reader.DocumentReader;
import io.invest.iagent.rag.model.Chunk;
import io.invest.iagent.rag.model.ChunkingConfig;
import io.invest.iagent.rag.model.Document;
import io.invest.iagent.rag.service.ChunkingService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Map;

/**
 * 默认分块服务：读取文档 → 选择策略 → 分块 → 映射为 Chunk
 */
@Slf4j
public class DefaultChunkingService implements ChunkingService {

    private final DocumentReader reader;
    private final Map<String, Chunker> chunkers;

    public DefaultChunkingService(DocumentReader reader, Map<String, Chunker> chunkers) {
        this.reader = reader;
        this.chunkers = chunkers;
    }

    @Override
    public List<Chunk> chunk(Document doc) {
        String content = reader.read(doc);
        if (StringUtils.isBlank(content)) {
            throw new IllegalArgumentException("Document content is blank: " + doc.getFilePath());
        }

        ChunkingConfig config = doc.getChunkingConfig() != null
                ? doc.getChunkingConfig() : new ChunkingConfig();

        List<ParsedChunk> chunks = getChunker(config).split(content, config);
        if (CollectionUtils.isEmpty(chunks)) {
            return List.of();
        }

        return chunks.stream().map(pc -> parse(pc, doc)).toList();
    }

    private Chunker getChunker(ChunkingConfig config) {
        ChunkingConfig.Strategy strategy = config.getStrategy();
        if (strategy == ChunkingConfig.Strategy.HEADING) {
            Chunker c = chunkers.get("headingAwareChunker");
            if (c != null) return c;
        }
        if (strategy == ChunkingConfig.Strategy.AUTO) {
            Chunker c = chunkers.get("headingAwareChunker");
            if (c != null) return c;
        }
        return chunkers.get("fixedSizeChunker");
    }

    private Chunk parse(ParsedChunk pc, Document doc) {
        return Chunk.builder()
                .chunkId(pc.getId())
                .knowledgeId(doc.getKnowledgeId())
                .knowledgeBaseId(doc.getKnowledgeBaseId())
                .chunkType(pc.getType())
                .content(pc.getContent())
                .contextHeader(pc.getContextHeader())
                .parentChunkId(pc.getParentChunkId())
                .build();
    }
}
