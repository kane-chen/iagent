package io.invest.iagent.service.kb.backend;

import io.invest.iagent.service.kb.model.KnowledgeBaseDocumentDTO;
import io.invest.iagent.service.kb.model.KnowledgeBaseOperationResult;

import java.util.List;

/**
 * 财报知识库后端抽象。
 * <p>
 * 该接口把"财报知识库"的核心操作（预处理、构建、删除、列出）抽象出来，
 * 便于在本地 Milvus 后端与外部 RAGFlow 后端之间切换。上层 {@code FilingKnowledgeBaseService}
 * 通过该接口做门面，不再直接依赖具体存储/检索实现。
 * <p>
 * 检索（retrieve）能力已迁移到 {@code workspace/skills/financial-filing-retrieve}
 * Python skill，Agent 主流程通过该 skill 拉取原文片段，本接口不再承担 retrieve 语义。
 * preprocess / build / list / delete 保留下来作为独立服务，可由运维流程、批处理任务
 * 或后台 API 调用，不嵌入 agent 主流程。
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
     * 删除指定公司或指定文档在后端的知识库内容。
     */
    KnowledgeBaseOperationResult delete(String ticker, String documentId);

    /**
     * 列出指定公司已构建的知识库文档。
     */
    List<KnowledgeBaseDocumentDTO> list(String ticker);
}
