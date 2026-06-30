package io.invest.iagent.service.kb.search.model;

import java.util.Map;

/**
 * Evidence-based search match result.
 *
 * Final structured search result containing evidence metadata and semantic
 * annotations for the matched section.
 */
public record EvidenceMatch(
        SectionInfo section,
        String matchedQuery,
        boolean isExactPhrase,
        Map<String, Object> evidence,
        Integer pageNo
) {

    public record SectionInfo(
            String ref,
            String title,
            String item,
            String topic
    ) {
    }
}
