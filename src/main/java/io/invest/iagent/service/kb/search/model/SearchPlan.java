package io.invest.iagent.service.kb.search.model;

import java.util.List;
import java.util.Map;

/**
 * Query execution plan.
 *
 * Defines the search strategy phases to execute for a query, including
 * whether to run exact matching and what expansion phases to apply.
 */
public record SearchPlan(
        boolean runExact,
        List<List<Map<String, String>>> expansionPhases,
        boolean fallbackGated,
        boolean scopedBeforeToken
) {
}
