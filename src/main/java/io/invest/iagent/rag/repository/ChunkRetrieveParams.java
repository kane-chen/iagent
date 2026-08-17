package io.invest.iagent.rag.repository;

import io.invest.iagent.rag.model.TagFilter;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ChunkRetrieveParams {
    private String query;
    private float[] queryEmbedding;
    private List<String> knowledgeBaseIds;
    private int topK = 30;
    private double vectorThreshold = 0.2;
    private double keywordThreshold = 0.3;
    private double rrfVectorWeight = 0.7;
    private double rrfKeywordWeight = 0.3;
    private int rrfK = 60;
    /** 标签过滤（EQ/IN + 跨 key AND），null 表示不过滤 */
    private TagFilter tagFilter;
}