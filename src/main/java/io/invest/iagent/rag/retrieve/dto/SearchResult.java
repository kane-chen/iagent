package io.invest.iagent.rag.retrieve.dto;

import io.invest.iagent.rag.model.RetrieveResultItem;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Pipeline 内部检索结果
 */
@Data
public class SearchResult {
    public String id;
    public String knowledgeId;
    public String knowledgeBaseId;
    public String chunkType;
    public double score;
    public String content;
    public String contextHeader;
    public Map<String, String> metadata = new HashMap<>();
    public String parentId;
    public int chunkIndex;

    public SearchResult() {}

    /** 深拷贝构造 */
    public SearchResult(SearchResult other) {
        this.id = other.id;
        this.knowledgeId = other.knowledgeId;
        this.knowledgeBaseId = other.knowledgeBaseId;
        this.chunkType = other.chunkType;
        this.score = other.score;
        this.content = other.content;
        this.contextHeader = other.contextHeader;
        this.metadata = new HashMap<>(other.metadata);
        this.parentId = other.parentId;
        this.chunkIndex = other.chunkIndex;
    }

    public RetrieveResultItem toRetrieveResultItem() {
        RetrieveResultItem item = new RetrieveResultItem();
        item.id = this.id;
        item.knowledgeId = this.knowledgeId;
        item.chunkType = this.chunkType;
        item.score = this.score;
        item.content = this.content;
        item.metadata = new HashMap<>(this.metadata);
        item.parentId = this.parentId;
        item.chunkIndex = this.chunkIndex;
        return item;
    }
}
