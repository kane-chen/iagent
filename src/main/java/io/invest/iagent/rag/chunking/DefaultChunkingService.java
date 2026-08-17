package io.invest.iagent.rag.chunking;

import io.invest.iagent.rag.chunking.chunker.Chunkers;
import io.invest.iagent.rag.chunking.dto.ParsedChunk;
import io.invest.iagent.rag.chunking.reader.DocumentReaders;
import io.invest.iagent.rag.model.Chunk;
import io.invest.iagent.rag.model.ChunkingConfig;
import io.invest.iagent.rag.model.Document;
import io.invest.iagent.rag.service.ChunkingService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认分块服务：读取文档 → 选择策略 → 分块 → 映射为 Chunk
 */
@Slf4j
@Service
public class DefaultChunkingService implements ChunkingService {

    @Autowired
    private DocumentReaders readers;
    @Autowired
    private Chunkers chunkers;

    @Override
    public List<Chunk> chunk(Document doc) {
        // reader
        String content = readers.read(doc);
        if (StringUtils.isBlank(content)) {
            throw new IllegalArgumentException("Document content is blank: " + doc.getFilePath());
        }
        // chunker
        ChunkingConfig config = ObjectUtils.firstNonNull(doc.getChunkingConfig(),new ChunkingConfig());
        List<ParsedChunk> chunks = chunkers.split(content,config);
        // parse
        if (CollectionUtils.isEmpty(chunks)) {
            return List.of();
        }
        return chunks.stream().map(pc -> parse(pc, doc)).toList();
    }

    private Chunk parse(ParsedChunk pc, Document doc) {
        // 合并标签：文档级标签打底，chunker 的 per-chunk 标签覆盖
        Map<String, String> tags = new HashMap<>();
        if (doc.getTags() != null) {
            tags.putAll(doc.getTags());
        }
        if (pc.getTags() != null) {
            tags.putAll(pc.getTags());
        }
        return Chunk.builder()
                .chunkId(pc.getId())
                .knowledgeId(doc.getKnowledgeId())
                .knowledgeBaseId(doc.getKnowledgeBaseId())
                .chunkType(pc.getType())
                .content(pc.getContent())
                .contextHeader(pc.getContextHeader())
                .parentChunkId(pc.getParentChunkId())
                .tags(tags.isEmpty() ? null : tags)
                .build();
    }
}
