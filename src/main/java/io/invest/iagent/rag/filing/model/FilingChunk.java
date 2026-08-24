package io.invest.iagent.rag.filing.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 检索返回的片段（带标签与引用）。
 */
@Data
@Builder
public class FilingChunk {
    private String chunkId;
    private double score;
    private String content;
    private Map<String, String> tags;
    /** 引用字符串，如 [C1] 00700 2026 Q1 管理层讨论与分析 > 收入 */
    private String citation;
}
