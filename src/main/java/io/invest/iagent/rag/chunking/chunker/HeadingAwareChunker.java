package io.invest.iagent.rag.chunking.chunker;

import io.invest.iagent.rag.chunking.dto.ParsedChunk;
import io.invest.iagent.rag.model.ChunkingConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

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

        // 按标题拆分为 sections
        List<Section> sections = splitByHeadings(content);

        List<ParsedChunk> allChunks = new ArrayList<>();
        for (Section section : sections) {
            if (section.content.isBlank()) continue;

            // 生成 parent chunk（整个 section）
            String parentId = null;
            if (parentChild) {
                parentId = deterministicId(section.breadcrumb + "::parent::" + section.startOffset);
                ParsedChunk parent = new ParsedChunk();
                parent.setId(parentId);
                parent.setContent(truncate(section.content, config.getParentChunkSize()));
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
                    section.breadcrumb, section.startOffset, parentId);
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
                                             String breadcrumb, int baseOffset, String parentId) {
        List<ParsedChunk> chunks = new ArrayList<>();
        if (text.length() <= windowSize) {
            ParsedChunk chunk = new ParsedChunk();
            chunk.setId(deterministicId(breadcrumb + "::" + baseOffset));
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
            chunk.setId(deterministicId(breadcrumb + "::" + (baseOffset + start)));
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

    private String deterministicId(String seed) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(seed.hashCode());
        }
    }

    private record Section(String breadcrumb, String content, int startOffset) {}
}
