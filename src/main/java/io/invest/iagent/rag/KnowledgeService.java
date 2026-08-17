package io.invest.iagent.rag;

import io.invest.iagent.rag.model.ChunkingConfig;
import io.invest.iagent.rag.model.Document;
import io.invest.iagent.rag.model.RetrieveRequest;
import io.invest.iagent.rag.model.RetrieveResultItem;

import java.util.List;

public interface KnowledgeService {

    /** @return 切分并写入的 chunk 数量 */
    int save(Document doc, ChunkingConfig chunkingConfig) ;

    /**
     * 删除指定知识库下某业务文档的全部 chunk（标签随级联删除）。
     * 用于幂等重建。
     */
    void deleteByKnowledgeId(String knowledgeBaseId, String knowledgeId);

    List<RetrieveResultItem> retrieve(RetrieveRequest request) ;

}
