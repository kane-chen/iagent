package io.invest.iagent.rag.chunking.plugins;

import io.invest.iagent.rag.chunking.Chunker;
import io.invest.iagent.rag.chunking.dto.ParsedChunk;
import io.invest.iagent.rag.model.ChunkingConfig;

import java.util.ArrayList;
import java.util.List;

public class FixedSizeChunker implements Chunker {

    @Override
    public String type() {
        return "FIXED_SIZE";
    }

    @Override
    public List<ParsedChunk> split(String markdown, ChunkingConfig config) {
        // 示例实现：按 cfg.separators 递归切分，并为每个 chunk 生成 ContextHeader（从标题层级提取）
        List<ParsedChunk> chunks = new ArrayList<>();
        // 简化：先按换行切，再按 chunkSize 合并/截断，真实实现应按分隔符优先级递归
        String[] paragraphs = markdown.split("\\R+");
        StringBuilder cur = new StringBuilder();
        int start = 0;
        for (String p : paragraphs) {
            if (cur.length() + p.length() > config.getChunkSize() && !cur.isEmpty()) {
                ParsedChunk ck = new ParsedChunk();
                ck.setContent(cur.toString());
                ck.setStart(start);
                ck.setEnd(start + cur.length());
                ck.setContextHeader(extractContextHeader(markdown, start)); // 提取标题面包屑
                chunks.add(ck);
                start += cur.length();
                cur = new StringBuilder();
            }
            cur.append(p).append("\n");
        }
        if (!cur.isEmpty()) {
            ParsedChunk ck = new ParsedChunk();
            ck.setContent(cur.toString());
            ck.setStart(start);
            ck.setEnd(start + cur.length());
            ck.setContextHeader(extractContextHeader(markdown, start));
            chunks.add(ck);
        }
        // 设置前后关系
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) chunks.get(i).setPreChunkId(chunks.get(i - 1).getId());
            if (i < chunks.size() - 1) chunks.get(i).setNextChunkId(chunks.get(i + 1).getId());
        }
        return chunks;
    }

    // 简化：向上查找最近的 #/##/### 行作为标题路径
    private String extractContextHeader(String markdown, int pos) {
        // 实际应解析 Markdown AST，这里仅作示意 TODO
        return "[ContextHeader from document structure]";
    }
}
