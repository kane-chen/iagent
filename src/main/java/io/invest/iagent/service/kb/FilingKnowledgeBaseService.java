package io.invest.iagent.service.kb;

import io.invest.iagent.config.ApplicationProperties;
import io.invest.iagent.model.KnowledgeBaseDocumentDTO;
import io.invest.iagent.model.KnowledgeBaseRetrieveResult;
import io.invest.iagent.service.kb.backend.KnowledgeBaseBackend;
import io.invest.iagent.service.kb.backend.MilvusKnowledgeBaseBackend;
import io.invest.iagent.service.kb.category.FilingQueryCategoryResolver;
import io.invest.iagent.service.kb.embedding.EmbeddingService;
import io.invest.iagent.service.kb.model.KnowledgeBaseOperationResult;
import io.invest.iagent.service.kb.vector.VectorStoreService;

import java.util.List;

/**
 * 财报知识库门面服务。
 * <p>
 * 该类是 Agent Tool 层（{@code FilingKnowledgeBaseTool}）唯一的调用入口。
 * 所有具体实现（本地 Milvus、外部 RAGFlow、内存后端等）通过 {@link KnowledgeBaseBackend} 接口注入，
 * 便于按配置项 {@code app.kb.backend} 切换。为保持向后兼容，旧构造器仍可用，
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

    public FilingKnowledgeBaseService(FilingPreprocessService preprocessService,
                                      EmbeddingService embeddingService,
                                      VectorStoreService vectorStoreService,
                                      FilingQueryCategoryResolver queryCategoryResolver,
                                      ApplicationProperties applicationProperties) {
        this(new MilvusKnowledgeBaseBackend(preprocessService, embeddingService, vectorStoreService,
                queryCategoryResolver, applicationProperties));
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

    public KnowledgeBaseRetrieveResult retrieve(String query, String ticker, Integer topK,
                                                String fiscalYear, String formType) {
        return retrieve(query, ticker, topK, fiscalYear, formType, null);
    }

    public KnowledgeBaseRetrieveResult retrieve(String query, String ticker, Integer topK,
                                                String fiscalYear, String formType, String category) {
        return retrieve(query, ticker, topK, fiscalYear, formType, category, false);
    }

    public KnowledgeBaseRetrieveResult retrieve(String query, String ticker, Integer topK,
                                                String fiscalYear, String formType,
                                                String category, Boolean useSummaryCandidates) {
        return backend.retrieve(query, ticker, topK, fiscalYear, formType, category, useSummaryCandidates);
    }

    public KnowledgeBaseOperationResult delete(String ticker, String documentId) {
        return backend.delete(ticker, documentId);
    }

    public List<KnowledgeBaseDocumentDTO> list(String ticker) {
        return backend.list(ticker);
    }

    public KnowledgeBaseOperationResult sync(String ticker, boolean force) {
        return build(ticker, null, force);
    }
}
