package io.invest.iagent.rag.repository;

import java.util.List;

public interface ChunkRepository {

    void save(ChunkDO entity);

    void batchSave(List<ChunkDO> entities);

    List<ChunkRetrieveResult> keywordSearch(ChunkRetrieveParams params);

    List<ChunkRetrieveResult> vectorSearch(ChunkRetrieveParams params);

    List<ChunkRetrieveResult> rrfFuse(
            List<ChunkRetrieveResult> keywordResults,
            List<ChunkRetrieveResult> vectorResults,
            ChunkRetrieveParams params);

    /**
     * 按 chunkId 批量查询（用于 parent 回填）
     */
    List<ChunkRetrieveResult> findByChunkIds(List<String> chunkIds);
}
