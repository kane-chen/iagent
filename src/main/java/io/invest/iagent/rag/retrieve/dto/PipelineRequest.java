package io.invest.iagent.rag.retrieve.dto;

import io.invest.iagent.rag.config.RagConfig;
import io.invest.iagent.rag.model.RetrieveRequest;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * Pipeline 请求参数（不可变）
 */
@Data
public final class PipelineRequest {
    public final String sessionId;
    public final String userId;
    public final String query;
    public final List<String> knowledgeBaseIds;
    public final List<String> knowledgeIds;
    public final double vectorThreshold;
    public final double keywordThreshold;
    public final int embeddingTopK;
    public final String vectorDatabase;
    public final String rerankModelId;
    public final int rerankTopK;
    public final double rerankThreshold;
    public final String chatModelId;
    public final String language;
    public final String fallbackStrategy;
    public final String fallbackResponse;
    public final String fallbackPrompt;
    public final boolean enableRewrite;
    public final boolean webSearchEnabled;
    public final String rewritePrompt;
    public final String queryExpansionPrompt;

    public PipelineRequest(String sessionId, String userId, String query,
                           List<String> knowledgeBaseIds, List<String> knowledgeIds,
                           double vectorThreshold, double keywordThreshold, int embeddingTopK, String vectorDatabase,
                           String rerankModelId, int rerankTopK, double rerankThreshold,
                           String chatModelId, String language,
                           String fallbackStrategy, String fallbackResponse, String fallbackPrompt,
                           boolean enableRewrite, boolean webSearchEnabled,
                           String rewritePrompt, String queryExpansionPrompt) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.query = query;
        this.knowledgeBaseIds = knowledgeBaseIds == null ? Collections.emptyList() : Collections.unmodifiableList(knowledgeBaseIds);
        this.knowledgeIds = knowledgeIds == null ? Collections.emptyList() : Collections.unmodifiableList(knowledgeIds);
        this.vectorThreshold = vectorThreshold;
        this.keywordThreshold = keywordThreshold;
        this.embeddingTopK = embeddingTopK;
        this.vectorDatabase = vectorDatabase;
        this.rerankModelId = rerankModelId;
        this.rerankTopK = rerankTopK;
        this.rerankThreshold = rerankThreshold;
        this.chatModelId = chatModelId;
        this.language = language;
        this.fallbackStrategy = fallbackStrategy;
        this.fallbackResponse = fallbackResponse;
        this.fallbackPrompt = fallbackPrompt;
        this.enableRewrite = enableRewrite;
        this.webSearchEnabled = webSearchEnabled;
        this.rewritePrompt = rewritePrompt;
        this.queryExpansionPrompt = queryExpansionPrompt;
    }

    /**
     * 从外部 RetrieveRequest 和 RagConfig 构建内部 PipelineRequest
     */
    public static PipelineRequest from(RetrieveRequest req, RagConfig config) {
        RagConfig.Search search = config.getSearch();
        return new PipelineRequest(
                req.sessionId, req.userId, req.query,
                req.knowledgeBaseIds, req.knowledgeIds,
                req.vectorThreshold > 0 ? req.vectorThreshold : search.getVectorThreshold(),
                req.keywordThreshold > 0 ? req.keywordThreshold : search.getKeywordThreshold(),
                req.embeddingTopK > 0 ? req.embeddingTopK : search.getVectorTopK(),
                req.vectorDatabase,
                req.rerankModelId,
                req.rerankTopK > 0 ? req.rerankTopK : search.getRerankTopK(),
                req.rerankThreshold,
                req.chatModelId,
                req.language != null ? req.language : "zh",
                req.fallbackStrategy,
                req.fallbackResponse,
                req.fallbackPrompt,
                req.enableRewrite,
                req.webSearchEnabled,
                req.rewritePrompt,
                req.queryExpansionPrompt
        );
    }
}
