package io.invest.iagent.service.kb.chunk;

import org.apache.commons.lang3.StringUtils;

public final class FilingChunkingStrategyFactory {

    private FilingChunkingStrategyFactory() {}

    public static FilingChunkingStrategy fromEnv() {
        return fromName(System.getenv("KB_CHUNKING_STRATEGY"));
    }

    public static FilingChunkingStrategy fromName(String name) {
        FilingChunkFactory factory = new FilingChunkFactory();
        String mode = StringUtils.defaultIfBlank(name, "fixed_window").trim().toLowerCase();
        return switch (mode) {
            case "fixed_window", "fixed-window", "fixed", "window" -> new FilingChunkingStrategyByFixedWindow(factory);
            case "paragraph" -> new FilingChunkingStrategyByParagraph(factory);
            case "semantic" -> new FilingChunkingStrategyBySemantic(factory);
            default -> throw new IllegalArgumentException("Unsupported KB_CHUNKING_STRATEGY: " + name);
        };
    }
}
