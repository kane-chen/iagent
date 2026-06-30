package io.invest.iagent.service.kb.chunk;

import io.invest.iagent.service.kb.model.FilingChunkingContext;
import io.invest.iagent.service.kb.model.KnowledgeBaseChunkDTO;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FilingChunkingStrategyByParagraph implements FilingChunkingStrategy {

    private static final int MAX_CHARS = 1200;
    private static final int SOFT_TARGET_CHARS = 1000;
    private static final int MIN_CHUNK_CHARS = 300;
    private static final int OVERLAP_CHARS = 120;
    private static final Pattern UNIT_PATTERN = Pattern.compile("\\[TABLE\\].*?\\[/TABLE\\]|[^。.!?！？；;]+[。.!?！？；;]?", Pattern.CASE_INSENSITIVE);

    private final FilingChunkFactory chunkFactory;
    private final FilingChunkingStrategyByFixedWindow fallback;

    public FilingChunkingStrategyByParagraph() {
        this(new FilingChunkFactory());
    }

    public FilingChunkingStrategyByParagraph(FilingChunkFactory chunkFactory) {
        this.chunkFactory = chunkFactory;
        this.fallback = new FilingChunkingStrategyByFixedWindow(chunkFactory);
    }

    @Override
    public String name() {
        return "paragraph";
    }

    @Override
    public List<KnowledgeBaseChunkDTO> chunk(FilingChunkingContext context, String text) {
        if (StringUtils.isBlank(text)) {
            return List.of();
        }
        List<String> units = splitUnits(text);
        if (units.isEmpty()) {
            return fallback.chunk(context, text);
        }

        List<KnowledgeBaseChunkDTO> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String previousTail = "";
        int index = 0;
        for (String unit : units) {
            if (unit.length() > MAX_CHARS) {
                if (!current.isEmpty()) {
                    addChunk(context, chunks, current.toString(), index++);
                    previousTail = tail(current.toString());
                    current.setLength(0);
                }
                List<KnowledgeBaseChunkDTO> split = fallback.chunk(context, unit);
                for (KnowledgeBaseChunkDTO ignored : split) {
                    chunks.add(chunkFactory.create(context, index++, ignored.getText()));
                }
                previousTail = split.isEmpty() ? "" : tail(split.get(split.size() - 1).getText());
                continue;
            }

            int projected = current.isEmpty() ? unit.length() : current.length() + 1 + unit.length();
            if (!current.isEmpty() && projected > MAX_CHARS && current.length() >= MIN_CHUNK_CHARS) {
                addChunk(context, chunks, current.toString(), index++);
                previousTail = tail(current.toString());
                current.setLength(0);
                if (StringUtils.isNotBlank(previousTail)) {
                    current.append(previousTail);
                }
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(unit);
            if (current.length() >= SOFT_TARGET_CHARS) {
                addChunk(context, chunks, current.toString(), index++);
                previousTail = tail(current.toString());
                current.setLength(0);
                if (StringUtils.isNotBlank(previousTail)) {
                    current.append(previousTail);
                }
            }
        }
        if (StringUtils.isNotBlank(current)) {
            addChunk(context, chunks, current.toString(), index);
        }
        return chunks;
    }

    private void addChunk(FilingChunkingContext context, List<KnowledgeBaseChunkDTO> chunks, String text, int index) {
        String chunkText = StringUtils.trimToEmpty(text);
        if (StringUtils.isNotBlank(chunkText)) {
            chunks.add(chunkFactory.create(context, index, chunkText));
        }
    }

    private List<String> splitUnits(String text) {
        List<String> units = new ArrayList<>();
        Matcher matcher = UNIT_PATTERN.matcher(text);
        while (matcher.find()) {
            String unit = matcher.group().trim();
            if (StringUtils.isNotBlank(unit)) {
                units.add(unit);
            }
        }
        return units;
    }

    private String tail(String text) {
        String trimmed = StringUtils.trimToEmpty(text);
        if (trimmed.length() <= OVERLAP_CHARS) {
            return trimmed;
        }
        return trimmed.substring(trimmed.length() - OVERLAP_CHARS).trim();
    }
}
