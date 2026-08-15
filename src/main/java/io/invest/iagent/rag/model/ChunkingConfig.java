package io.invest.iagent.rag.model;

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
    private Strategy strategy = Strategy.AUTO;
    private boolean enableParentChild;
    private int parentChunkSize = 4096;
    private int childChunkSize = 384;

    public enum Strategy { AUTO, HEADING, HEURISTIC, RECURSIVE, LEGACY }
}


