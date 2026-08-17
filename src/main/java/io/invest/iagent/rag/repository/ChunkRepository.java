package io.invest.iagent.rag.repository;

import io.invest.iagent.rag.model.TagFilter;

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

    /**
     * 删除某个 knowledgeId 下的全部 chunk（标签随外键级联删除），用于幂等重建。
     */
    void deleteByKnowledgeId(String knowledgeBaseId, String knowledgeId);

    /**
     * 查询知识库内某 tagKey 的去重值（用于财报周期归一化取“最新期间”等）。
     *
     * @param scope 额外的标签过滤（如限定 ticker），可为 null
     */
    List<String> findTagValues(String knowledgeBaseId, String tagKey, TagFilter scope);
}
