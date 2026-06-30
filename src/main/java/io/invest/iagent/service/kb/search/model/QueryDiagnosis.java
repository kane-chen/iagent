package io.invest.iagent.service.kb.search.model;

import java.util.List;

/**
 * Search query diagnosis result.
 *
 * Contains query ambiguity score, intent classification, and other metadata
 * used for adaptive retrieval strategy selection.
 */
public record QueryDiagnosis(
        String query,
        List<String> tokens,
        int tokenCount,
        double ambiguityScore,
        boolean isHighAmbiguity,
        String intent,
        boolean allowDirectTokenFallback
) {
}
