package io.invest.iagent.rag.chunking.chunker;

import io.invest.iagent.rag.chunking.dto.ParsedChunk;
import io.invest.iagent.rag.model.ChunkingConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 标题感知分块器：识别 Markdown ATX 标题和 SEC 文档标题模式，
 * 构建面包屑上下文，在 section 内做滑动窗口切分，支持父子分块。
 */
@Service
@Slf4j
public class HeadingAwareChunker implements Chunker {

    @Override
    public ChunkStrategy type() {
        return ChunkStrategy.HEADING ;
    }

    @Override
    public List<ParsedChunk> split(String content, ChunkingConfig config) {
        if (content == null || content.isBlank()) return List.of();

        int childSize = config.getChildChunkSize() > 0 ? config.getChildChunkSize() : 384;
        int overlap = config.getChunkOverlap();
        boolean parentChild = config.isEnableParentChild();

        // 批次内 id 去重：同一份文档切分过程中保证 id 唯一，
        // 跨文档/重复导入由 embeddings(knowledge_base_id, chunk_id) 的 upsert 兜底。
        Set<String> usedIds = new HashSet<>();

        // 按标题拆分为 sections
        List<Section> sections = splitByHeadings(content);

        List<ParsedChunk> allChunks = new ArrayList<>();
        for (Section section : sections) {
            if (section.content.isBlank()) continue;

            // 生成 parent chunk（整个 section）
            String parentId = null;
            if (parentChild) {
                String parentContent = truncate(section.content, config.getParentChunkSize());
                parentId = uniqueId(usedIds, "parent", section.breadcrumb,
                        section.startOffset, parentContent);
                ParsedChunk parent = new ParsedChunk();
                parent.setId(parentId);
                parent.setContent(parentContent);
                parent.setContextHeader(section.breadcrumb);
                parent.setType("parent_text");
                parent.setStart(section.startOffset);
                parent.setEnd(section.startOffset + Math.min(section.content.length(), config.getParentChunkSize()));
                // 标题面包屑作为通用 heading 标签
                if (section.breadcrumb != null && !section.breadcrumb.isBlank()) {
                    parent.getTags().put("heading", section.breadcrumb);
                }
                allChunks.add(parent);
            }

            // 在 section 内做滑动窗口切分 child chunks
            List<ParsedChunk> children = slidingWindow(
                    section.content, childSize, overlap,
                    section.breadcrumb, section.startOffset, parentId, usedIds);
            allChunks.addAll(children);
        }

        // 设置前后关系
        for (int i = 0; i < allChunks.size(); i++) {
            if (i > 0) allChunks.get(i).setPreChunkId(allChunks.get(i - 1).getId());
            if (i < allChunks.size() - 1) allChunks.get(i).setNextChunkId(allChunks.get(i + 1).getId());
        }

        return allChunks;
    }

    private List<Section> splitByHeadings(String content) {
        List<Section> sections = new ArrayList<>();
        String[] lines = content.split("\\R");

        List<String> hierarchy = new ArrayList<>();
        StringBuilder currentContent = new StringBuilder();
        String currentBreadcrumb = "";
        int currentStart = 0;
        int offset = 0;

        for (String line : lines) {
            int level = headingLevel(line);
            if (level > 0) {
                // 保存前一个 section
                if (!currentContent.isEmpty()) {
                    sections.add(new Section(currentBreadcrumb, currentContent.toString().trim(), currentStart));
                }
                // 更新标题层级
                String title = line.replaceFirst("^#+\\s*", "").trim();
                while (hierarchy.size() >= level) hierarchy.remove(hierarchy.size() - 1);
                hierarchy.add(title);
                currentBreadcrumb = String.join(" > ", hierarchy);
                currentContent = new StringBuilder();
                currentStart = offset;
            } else {
                currentContent.append(line).append("\n");
            }
            offset += line.length() + 1;
        }
        if (!currentContent.isEmpty()) {
            sections.add(new Section(currentBreadcrumb, currentContent.toString().trim(), currentStart));
        }
        if (sections.isEmpty()) {
            sections.add(new Section("", content.trim(), 0));
        }
        return sections;
    }

    private int headingLevel(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("####")) return 4;
        if (trimmed.startsWith("###")) return 3;
        if (trimmed.startsWith("##")) return 2;
        if (trimmed.startsWith("#")) return 1;
        return 0;
    }

    private List<ParsedChunk> slidingWindow(String text, int windowSize, int overlap,
                                             String breadcrumb, int baseOffset, String parentId,
                                             Set<String> usedIds) {
        List<ParsedChunk> chunks = new ArrayList<>();
        if (text.length() <= windowSize) {
            ParsedChunk chunk = new ParsedChunk();
            chunk.setId(uniqueId(usedIds, "child", breadcrumb, baseOffset, text));
            chunk.setContent(text);
            chunk.setContextHeader(breadcrumb);
            chunk.setType("text");
            chunk.setStart(baseOffset);
            chunk.setEnd(baseOffset + text.length());
            chunk.setParentChunkId(parentId);
            if (breadcrumb != null && !breadcrumb.isBlank()) {
                chunk.getTags().put("heading", breadcrumb);
            }
            chunks.add(chunk);
            return chunks;
        }

        int step = Math.max(1, windowSize - overlap);
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(start + windowSize, text.length());
            // 尝试在句子/段落边界切分
            if (end < text.length()) {
                int boundary = findBoundary(text, end);
                if (boundary > start) end = boundary;
            }
            String windowText = text.substring(start, end).trim();
            if (windowText.isEmpty()) continue;

            ParsedChunk chunk = new ParsedChunk();
            chunk.setId(uniqueId(usedIds, "child", breadcrumb, baseOffset + start, windowText));
            chunk.setContent(windowText);
            chunk.setContextHeader(breadcrumb);
            chunk.setType("text");
            chunk.setStart(baseOffset + start);
            chunk.setEnd(baseOffset + end);
            chunk.setParentChunkId(parentId);
            if (breadcrumb != null && !breadcrumb.isBlank()) {
                chunk.getTags().put("heading", breadcrumb);
            }
            chunks.add(chunk);

            if (end >= text.length()) break;
        }
        return chunks;
    }

    private int findBoundary(String text, int preferred) {
        int searchStart = Math.max(preferred - 50, 0);
        int searchEnd = Math.min(preferred + 100, text.length());
        String segment = text.substring(searchStart, searchEnd);

        // 优先段落边界
        int idx = segment.lastIndexOf("\n\n");
        if (idx >= 0) return searchStart + idx + 2;
        // 句号
        idx = lastIndexOfAny(segment, '。', '！', '？', '!', '?', ';', '；');
        if (idx >= 0) return searchStart + idx + 1;
        // 换行
        idx = segment.lastIndexOf('\n');
        if (idx >= 0) return searchStart + idx + 1;
        return preferred;
    }

    private int lastIndexOfAny(String s, char... chars) {
        for (int i = s.length() - 1; i >= 0; i--) {
            for (char c : chars) {
                if (s.charAt(i) == c) return i;
            }
        }
        return -1;
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    /**
     * 生成批次内唯一的稳定 chunk id。
     * <p>种子包含 kind + 标题面包屑 + 偏移 + chunk 内容，避免不同文档/不同内容仅因标题与位置相近而撞 id；
     * 若哈希后仍与已分配 id 冲突（同内容重复出现或截断碰撞），追加 {@code -n} 后缀保证唯一。
     */
    private String uniqueId(Set<String> usedIds, String kind, String breadcrumb, int offset, String body) {
        String seed = kind + "::" + safe(breadcrumb) + "::" + offset + "::" + safe(body);
        String base = hash16(seed);
        String id = base;
        int seq = 2;
        while (!usedIds.add(id)) {
            id = base + "-" + seq;
            seq++;
        }
        return id;
    }

    /** SHA-256 截断为 16 个 hex 字符（64 bit），碰撞概率极低；失败时回退到 hashCode。 */
    private String hash16(String seed) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(seed.hashCode());
        }
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private record Section(String breadcrumb, String content, int startOffset) {}
}
