package io.invest.iagent.rag.model;

import io.invest.iagent.rag.chunking.chunker.ChunkStrategy;
import lombok.Data;

import java.util.List;

/**
 * 分块配置
 */
@Data
public class ChunkingConfig {
    private int chunkSize = 512;
    private int chunkOverlap = 80;
    private List<String> separators;
    private ChunkStrategy strategy = ChunkStrategy.AUTO;
    private boolean enableParentChild;
    private int parentChunkSize = 4096;
    private int childChunkSize = 384;

}


