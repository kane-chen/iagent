package io.invest.iagent.rag.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 建设请求
 */
@Data
@Builder
public class RetrieveRequest {
    // 会话与用户
    public final String sessionId;
    public final String userId;
    public final String query;

    // 知识库范围
    public final List<String> knowledgeBaseIds;
    public final List<String> knowledgeIds;

    // 检索配置
    public final double vectorThreshold;
    public final double keywordThreshold;
    public final int embeddingTopK;
    public final String vectorDatabase;

    // 重排配置
    public final String rerankModelId;
    public final int rerankTopK;
    public final double rerankThreshold;

    // 模型与生成配置
    public final String chatModelId;
    public final String language;

    // Fallback 策略
    public final String fallbackStrategy; // "fixed" | "model"
    public final String fallbackResponse;
    public final String fallbackPrompt;

    // 开关与特性
    public final boolean enableRewrite;
    public final boolean webSearchEnabled;
    public final String rewritePrompt; // Query改写提示词
    public final String queryExpansionPrompt; // 查询扩展提示词
}