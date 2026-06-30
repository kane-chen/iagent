package io.invest.iagent.service.kb.search.model;

import java.util.Map;
import java.util.Optional;

/**
 * Raw search hit from document processor.
 *
 * Represents a single search result before normalization and ranking.
 * Supports both snippet mode and evidence mode.
 */
public record SearchHit(
        String sectionRef,
        String sectionTitle,
        Integer pageNo,
        String snippet,
        Map<String, Object> evidence,
        boolean tokenFallback
) {

    public Optional<Integer> pageNoOptional() {
        return Optional.ofNullable(pageNo);
    }

    public Optional<String> snippetOptional() {
        return Optional.ofNullable(snippet);
    }

    public Optional<Map<String, Object>> evidenceOptional() {
        return Optional.ofNullable(evidence);
    }
}
