package io.invest.iagent.service.kb;

import io.invest.iagent.service.kb.model.KnowledgeBaseDocumentDTO;
import io.invest.iagent.service.kb.backend.KnowledgeBaseBackend;
import io.invest.iagent.service.kb.backend.MilvusKnowledgeBaseBackend;
import io.invest.iagent.service.kb.embedding.EmbeddingService;
import io.invest.iagent.service.kb.model.KnowledgeBaseOperationResult;
import io.invest.iagent.service.kb.vector.VectorStoreService;

import java.util.List;

/**
 * 财报知识库门面服务。
 * <p>
 * 该服务对外暴露 preprocess / build / list / delete 四个"运维型"操作，
 * 不再承担 Agent 主流程中的 retrieve 语义（retrieve 已迁移到
 * {@code workspace/skills/financial-filing-retrieve} Python skill）。
 * <p>
 * 具体实现通过 {@link KnowledgeBaseBackend} 接口注入（本地 Milvus / 外部 RAGFlow / 内存后端），
 * 按配置项 {@code app.kb.backend} 切换。为保持向后兼容，旧构造器仍可用，
 * 内部会自动装配一个 {@link MilvusKnowledgeBaseBackend}。
 */
public class FilingKnowledgeBaseService {

    private final KnowledgeBaseBackend backend;

    public FilingKnowledgeBaseService(KnowledgeBaseBackend backend) {
        this.backend = backend;
    }

    public FilingKnowledgeBaseService(FilingPreprocessService preprocessService,
                                      EmbeddingService embeddingService,
                                      VectorStoreService vectorStoreService) {
        this(new MilvusKnowledgeBaseBackend(preprocessService, embeddingService, vectorStoreService));
    }

    public String backendName() {
        return backend.name();
    }

    public KnowledgeBaseOperationResult preprocess(String ticker, String documentId, boolean force) {
        return backend.preprocess(ticker, documentId, force);
    }

    public KnowledgeBaseOperationResult build(String ticker, String documentId, boolean force) {
        return backend.build(ticker, documentId, force);
    }

    public KnowledgeBaseOperationResult delete(String ticker, String documentId) {
        return backend.delete(ticker, documentId);
    }

    public List<KnowledgeBaseDocumentDTO> list(String ticker) {
        return backend.list(ticker);
    }

}
