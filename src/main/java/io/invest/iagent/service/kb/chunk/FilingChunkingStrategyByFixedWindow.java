package io.invest.iagent.service.kb.chunk;

import io.invest.iagent.service.kb.model.FilingChunkingContext;
import io.invest.iagent.service.kb.model.KnowledgeBaseChunkDTO;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class FilingChunkingStrategyByFixedWindow implements FilingChunkingStrategy {

    private static final int DEFAULT_MAX_CHARS = 1200;
    private static final int DEFAULT_OVERLAP_CHARS = 120;
    private static final int DEFAULT_MIN_BOUNDARY_CHARS = 300;

    private final FilingChunkFactory chunkFactory;
    private final int maxChars;
    private final int overlapChars;
    private final int minBoundaryChars;

    public FilingChunkingStrategyByFixedWindow() {
        this(new FilingChunkFactory());
    }

    public FilingChunkingStrategyByFixedWindow(FilingChunkFactory chunkFactory) {
        this(chunkFactory, DEFAULT_MAX_CHARS, DEFAULT_OVERLAP_CHARS, DEFAULT_MIN_BOUNDARY_CHARS);
    }

    FilingChunkingStrategyByFixedWindow(FilingChunkFactory chunkFactory, int maxChars, int overlapChars, int minBoundaryChars) {
        this.chunkFactory = chunkFactory;
        this.maxChars = maxChars;
        this.overlapChars = overlapChars;
        this.minBoundaryChars = minBoundaryChars;
    }

    @Override
    public String name() {
        return "fixed_window";
    }

    @Override
    public List<KnowledgeBaseChunkDTO> chunk(FilingChunkingContext context, String text) {
        if (StringUtils.isBlank(text)) {
            return List.of();
        }
        List<KnowledgeBaseChunkDTO> chunks = new ArrayList<>();
        int index = 0;
        int start = nextContentStart(text, 0);
        while (start < text.length()) {
            int end = chooseEnd(text, start);
            String chunkText = text.substring(start, end).trim();
            if (StringUtils.isNotBlank(chunkText)) {
                chunks.add(chunkFactory.create(context, index, chunkText));
            }
            if (end >= text.length()) {
                break;
            }
            int nextStart = chooseNextStart(text, start, end);
            if (nextStart <= start) {
                nextStart = nextContentStart(text, end);
            }
            start = nextStart;
            index++;
        }
        return chunks;
    }

    private int chooseEnd(String text, int start) {
        int limit = Math.min(text.length(), start + maxChars);
        if (limit >= text.length()) {
            return text.length();
        }
        int min = Math.min(limit, start + minBoundaryChars);
        int paragraph = lastParagraphBoundary(text, start, min, limit);
        if (paragraph > start) {
            return paragraph;
        }
        int sentence = lastSentenceBoundary(text, min, limit);
        if (sentence > start) {
            return sentence;
        }
        int word = lastWordBoundary(text, min, limit);
        if (word > start) {
            return word;
        }
        return limit;
    }

    private int chooseNextStart(String text, int currentStart, int currentEnd) {
        int target = Math.max(currentStart + 1, currentEnd - overlapChars);
        int paragraph = firstParagraphStart(text, target, currentEnd);
        if (paragraph > currentStart) {
            return nextContentStart(text, paragraph);
        }
        int sentence = firstSentenceStart(text, target, currentEnd);
        if (sentence > currentStart) {
            return nextContentStart(text, sentence);
        }
        int word = firstWordStart(text, target, currentEnd);
        if (word > currentStart) {
            return nextContentStart(text, word);
        }
        return nextContentStart(text, target);
    }

    private int lastParagraphBoundary(String text, int start, int min, int limit) {
        for (int i = limit - 1; i >= min; i--) {
            if (isLineBreak(text.charAt(i)) && hasLineBreakBefore(text, start, i)) {
                return i + 1;
            }
        }
        return -1;
    }

    private int lastSentenceBoundary(String text, int min, int limit) {
        for (int i = limit - 1; i >= min; i--) {
            if (isSentenceEnd(text.charAt(i))) {
                return i + 1;
            }
        }
        return -1;
    }

    private int lastWordBoundary(String text, int min, int limit) {
        for (int i = limit - 1; i >= min; i--) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                return i;
            }
            if (isWordSeparator(c)) {
                return i + 1;
            }
        }
        return -1;
    }

    private int firstParagraphStart(String text, int min, int limit) {
        for (int i = Math.max(0, min); i < Math.min(limit, text.length()); i++) {
            if (isLineBreak(text.charAt(i)) && hasLineBreakBefore(text, min, i)) {
                return i + 1;
            }
        }
        return -1;
    }

    private int firstSentenceStart(String text, int min, int limit) {
        for (int i = Math.max(0, min); i < Math.min(limit, text.length()); i++) {
            if (isSentenceEnd(text.charAt(i))) {
                return i + 1;
            }
        }
        return -1;
    }

    private int firstWordStart(String text, int min, int limit) {
        for (int i = Math.max(0, min); i < Math.min(limit, text.length()); i++) {
            if (Character.isWhitespace(text.charAt(i)) || isWordSeparator(text.charAt(i))) {
                return i + 1;
            }
        }
        return -1;
    }

    private int nextContentStart(String text, int index) {
        int result = Math.max(0, index);
        while (result < text.length() && Character.isWhitespace(text.charAt(result))) {
            result++;
        }
        return result;
    }

    private boolean hasLineBreakBefore(String text, int start, int index) {
        for (int i = index - 1; i >= start; i--) {
            char c = text.charAt(i);
            if (isLineBreak(c)) {
                return true;
            }
            if (!Character.isWhitespace(c)) {
                return false;
            }
        }
        return false;
    }

    private boolean isLineBreak(char c) {
        return c == '\n' || c == '\r';
    }

    private boolean isSentenceEnd(char c) {
        return c == '.' || c == '。' || c == '!' || c == '！' || c == '?' || c == '？' || c == ';' || c == '；';
    }

    private boolean isWordSeparator(char c) {
        return c == ',' || c == '，' || c == ':' || c == '：' || c == ')' || c == '）' || c == ']' || c == '】';
    }
}
