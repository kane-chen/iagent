package io.invest.iagent.rag.chunking.dto;

import lombok.Data;

/**
 * 分块后的片段（对应 Chunk）
 */
@Data
public class ParsedChunk {
    private String id;
    private String knowledgeId;
    private String knowledgeBaseId;
    private String content;          // 原文内容（不包含标题前缀）
    private String contextHeader;    // 标题面包屑，用于 embedding 增强，但保持原文位置
    private int start;
    private int end;                 // 对应文档字符区间 [start, end)
    private String type;             // text, image_ocr, image_caption, parent_text 等
    private String preChunkId;
    private String nextChunkId;
    private String parentChunkId;    // 若为 child，指向 parent
}
