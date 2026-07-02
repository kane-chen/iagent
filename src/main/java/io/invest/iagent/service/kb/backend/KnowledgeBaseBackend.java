package io.invest.iagent.service.kb.backend;

import io.invest.iagent.model.KnowledgeBaseDocumentDTO;
import io.invest.iagent.model.KnowledgeBaseRetrieveResult;
import io.invest.iagent.service.kb.model.KnowledgeBaseOperationResult;

import java.util.List;

/**
 * 财报知识库后端抽象。
 * <p>
 * 该接口把"财报知识库"的核心操作（预处理、构建、检索、删除、列出）抽象出来，
 * 便于在本地 Milvus 后端与外部 RAGFlow 后端之间切换。上层 {@code FilingKnowledgeBaseService}
 * 通过该接口做门面，不再直接依赖具体存储/检索实现。
 */
public interface KnowledgeBaseBackend {

    /**
     * 后端名称，用于日志、追踪与配置切换。
     */
    String name();

    /**
     * 预处理财报文件（解析、切分、可选摘要），不写入向量存储。
     *
     * @param ticker     股票代码
     * @param documentId 可选，指定财报文档 id；为空时处理该公司全部已下载财报
     * @param force      是否强制重新预处理
     */
    KnowledgeBaseOperationResult preprocess(String ticker, String documentId, boolean force);

    /**
     * 构建知识库：预处理 -> 向量化 -> 写入后端存储。
     */
    KnowledgeBaseOperationResult build(String ticker, String documentId, boolean force);

    /**
     * 检索与问题相关的财报片段。
     */
    KnowledgeBaseRetrieveResult retrieve(String query, String ticker, Integer topK,
                                         String fiscalYear, String formType,
                                         String category, Boolean useSummaryCandidates);

    /**
     * 删除指定公司或指定文档在后端的知识库内容。
     */
    KnowledgeBaseOperationResult delete(String ticker, String documentId);

    /**
     * 列出指定公司已构建的知识库文档。
     */
    List<KnowledgeBaseDocumentDTO> list(String ticker);
}
