package io.invest.iagent.rag.model;

import io.invest.iagent.rag.retrieve.enums.RetrieveMode;
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
    public String sessionId;
    public String userId;
    public String query;

    public RetrieveMode retrieveMode = RetrieveMode.HYBRID ;

    // 知识库范围
    public List<String> knowledgeBaseIds;
    public List<String> knowledgeIds;
    

    // 检索配置
    public double vectorThreshold;
    public double keywordThreshold;
    public int embeddingTopK;
    public String vectorDatabase;

    // 重排配置
    public String rerankModelId;
    public int rerankTopK;
    public double rerankThreshold;

    // 模型与生成配置
    public String chatModelId;
    public String language;

    // Fallback 策略
    public String fallbackStrategy; // "fixed" | "model"
    public String fallbackResponse;
    public String fallbackPrompt;

    // 开关与特性
    public boolean enableRewrite;
    public boolean webSearchEnabled;
    public String rewritePrompt; // Query改写提示词
    public String queryExpansionPrompt; // 查询扩展提示词

    // 标签过滤（EQ/IN + 跨 key AND）
    public TagFilter tagFilter;

    // 业务域标识：用于应用层 handler 开关（如 filingkb 设为 "filing"）
    public String domain;
}