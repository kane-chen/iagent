package io.invest.iagent.rag.model;

import lombok.Data;

import java.util.List;

/**
 * 检索结果
 */
@Data
public class RetrieveResult {
    // 会话与用户
    private String sessionId;
    private String userId;
    private String query;
    // LLM 生成阶段产出
    private String chatResponse;
    // 相关的知识库片段
    private List<RetrieveResultItem> items ;
}