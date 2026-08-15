package io.invest.iagent.rag.model;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 检索结果
 */
@Data
public class RetrieveResultItem {
    public String id;
    public String knowledgeId;
    public String chunkType; // "text", "faq", "image_ocr", "image_caption", "direct_load"
    public double score;
    public String content;
    public Map<String, String> metadata = new HashMap<>();
    public String parentId; // parent chunk id，用于parent-child回填
    public int chunkIndex; // 用于排序与合并
    // getters/setters 略
}