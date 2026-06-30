package io.invest.iagent.service.kb.chunk;

import io.invest.iagent.service.kb.model.FilingChunkingContext;
import io.invest.iagent.service.kb.model.KnowledgeBaseChunkDTO;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FilingChunkingStrategyBySemantic implements FilingChunkingStrategy {

    private static final int MAX_CHARS = 1200;
    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "(?i)(?=(\\bItem\\s+(?:1A|1B|1|2|3|7A|7|8)\\.?\\s+[^.。]{0,120}|" +
                    "Risk\\s+Factors|Management(?:'s)?\\s+Discussion\\s+and\\s+Analysis|" +
                    "Financial\\s+Statements|Notes\\s+to\\s+Consolidated\\s+Financial\\s+Statements|" +
                    "风险因素|管理层讨论与分析|经营情况讨论与分析|财务报表|重要事项))");

    private final FilingChunkFactory chunkFactory;
    private final FilingChunkingStrategyByParagraph fallback;

    public FilingChunkingStrategyBySemantic() {
        this(new FilingChunkFactory());
    }

    public FilingChunkingStrategyBySemantic(FilingChunkFactory chunkFactory) {
        this.chunkFactory = chunkFactory;
        this.fallback = new FilingChunkingStrategyByParagraph(chunkFactory);
    }

    @Override
    public String name() {
        return "semantic";
    }

    @Override
    public List<KnowledgeBaseChunkDTO> chunk(FilingChunkingContext context, String text) {
        if (StringUtils.isBlank(text)) {
            return List.of();
        }
        List<Boundary> boundaries = boundaries(text);
        if (boundaries.isEmpty()) {
            return fallback.chunk(context, text);
        }

        List<KnowledgeBaseChunkDTO> chunks = new ArrayList<>();
        int index = 0;
        if (boundaries.get(0).offset() > 0) {
            index = addSegment(context, chunks, text.substring(0, boundaries.get(0).offset()), index, null);
        }
        for (int i = 0; i < boundaries.size(); i++) {
            Boundary boundary = boundaries.get(i);
            int end = i + 1 < boundaries.size() ? boundaries.get(i + 1).offset() : text.length();
            String segment = text.substring(boundary.offset(), end).trim();
            index = addSegment(context, chunks, segment, index, titleFor(boundary.heading()));
        }
        return chunks;
    }

    private int addSegment(FilingChunkingContext context, List<KnowledgeBaseChunkDTO> chunks,
                           String segment, int index, String sectionTitle) {
        if (StringUtils.isBlank(segment)) {
            return index;
        }
        if (segment.length() <= MAX_CHARS) {
            chunks.add(chunkFactory.create(context, index, segment.trim(), sectionTitle));
            return index + 1;
        }
        List<KnowledgeBaseChunkDTO> split = fallback.chunk(context, segment);
        for (KnowledgeBaseChunkDTO item : split) {
            chunks.add(chunkFactory.create(context, index++, item.getText(), StringUtils.defaultIfBlank(sectionTitle, item.getSectionTitle())));
        }
        return index;
    }

    private List<Boundary> boundaries(String text) {
        List<Boundary> boundaries = new ArrayList<>();
        Matcher matcher = HEADING_PATTERN.matcher(text);
        while (matcher.find()) {
            String heading = matcher.group(1);
            if (StringUtils.isNotBlank(heading)) {
                boundaries.add(new Boundary(matcher.start(1), heading));
            }
        }
        return boundaries.stream()
                .sorted(Comparator.comparingInt(Boundary::offset))
                .toList();
    }

    private String titleFor(String heading) {
        if (StringUtils.containsIgnoreCase(heading, "risk") || heading.contains("风险")) {
            return "Risk Factors";
        }
        if (StringUtils.containsIgnoreCase(heading, "management") || heading.contains("管理层") || heading.contains("经营情况")) {
            return "Management Discussion";
        }
        if (StringUtils.containsIgnoreCase(heading, "financial") || heading.contains("财务")) {
            return "Financial Information";
        }
        return null;
    }

    private record Boundary(int offset, String heading) {}
}
