package io.invest.iagent.rag.retrieve.dto;

import io.invest.iagent.rag.config.RagProperties;
import io.invest.iagent.rag.model.RetrieveRequest;
import io.invest.iagent.rag.model.TagFilter;
import io.invest.iagent.rag.retrieve.enums.RetrieveMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * Pipeline 请求参数（不可变）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineRequest {
    public String sessionId;
    public String userId;
    public String query;
    public List<String> knowledgeBaseIds;
    public List<String> knowledgeIds;
    public double vectorThreshold;
    public double keywordThreshold;
    public int embeddingTopK;
    public String vectorDatabase;
    public int rerankTopK;
    public double rerankThreshold;
    public String chatModelId;
    public String language;
    public String fallbackStrategy;
    public String fallbackResponse;
    public String fallbackPrompt;
    public boolean enableRewrite;
    public boolean webSearchEnabled;
    public String rewritePrompt;
    public String queryExpansionPrompt;
    /** 标签过滤（来自请求；运行时可被 state.tagFilter 覆盖） */
    public TagFilter tagFilter;
    /** 业务域标识，用于应用层 handler 开关 */
    public String domain;
    private RetrieveMode retrieveMode = RetrieveMode.HYBRID ;

    public PipelineRequest(String sessionId, String userId, String query,
                           List<String> knowledgeBaseIds, List<String> knowledgeIds,
                           double vectorThreshold, double keywordThreshold, int embeddingTopK, String vectorDatabase,
                           int rerankTopK, double rerankThreshold,
                           String chatModelId, String language,
                           String fallbackStrategy, String fallbackResponse, String fallbackPrompt,
                           boolean enableRewrite, boolean webSearchEnabled,
                           String rewritePrompt, String queryExpansionPrompt,
                           TagFilter tagFilter, String domain) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.query = query;
        this.knowledgeBaseIds = knowledgeBaseIds == null ? Collections.emptyList() : Collections.unmodifiableList(knowledgeBaseIds);
        this.knowledgeIds = knowledgeIds == null ? Collections.emptyList() : Collections.unmodifiableList(knowledgeIds);
        this.vectorThreshold = vectorThreshold;
        this.keywordThreshold = keywordThreshold;
        this.embeddingTopK = embeddingTopK;
        this.vectorDatabase = vectorDatabase;
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
        this.tagFilter = tagFilter;
        this.domain = domain;
    }

    /**
     * 从外部 RetrieveRequest 和 RagConfig 构建内部 PipelineRequest
     */
    public static PipelineRequest from(RetrieveRequest req, RagProperties config) {
        RagProperties.Search search = config.getSearch();
        PipelineRequest request = new PipelineRequest(
                req.sessionId, req.userId, req.query,
                req.knowledgeBaseIds, req.knowledgeIds,
                req.vectorThreshold > 0 ? req.vectorThreshold : search.getVectorThreshold(),
                req.keywordThreshold > 0 ? req.keywordThreshold : search.getKeywordThreshold(),
                req.embeddingTopK > 0 ? req.embeddingTopK : search.getVectorTopK(),
                req.vectorDatabase,
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
                req.queryExpansionPrompt,
                req.tagFilter,
                req.domain
        );
        request.setRetrieveMode(req.retrieveMode);
        return request ;
    }
}
