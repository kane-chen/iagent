package io.invest.iagent.rag.chunking;

import io.invest.iagent.rag.chunking.dto.ParsedChunk;
import io.invest.iagent.rag.model.ChunkingConfig;

import java.util.List;

public interface Chunker {

    /**
     * 拆分类型
     * @return 类型
     */
    String type() ;

    /**
     * 普通分块（支持 ContextHeader）
     */
    List<ParsedChunk> split(String markdown, ChunkingConfig config);

}
