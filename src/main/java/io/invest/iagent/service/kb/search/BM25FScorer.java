package io.invest.iagent.service.kb.search;

import io.invest.iagent.service.kb.search.model.RankedSearchEntry;

import java.util.*;
import java.util.regex.Pattern;

/**
 * BM25F-style section retrieval scoring module.
 *
 * This class provides low-intrusive multi-field lexical ranking capability for search_document:
 * - Builds document-level lexical index based on section summary fields.
 * - Calculates BM25F-style scores for individual search hits.
 * - Only enhances ranking, not responsible for recall.
 */
public class BM25FScorer {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-z0-9]+");

    private static final Map<String, Double> FIELD_WEIGHTS = Map.of(
            "title", 3.0,
            "item", 2.0,
            "topic", 2.0,
            "path", 2.0,
            "preview", 1.0,
            "content", 1.0
    );

    private static final Map<String, Double> FIELD_B = Map.of(
            "title", 0.35,
            "item", 0.2,
            "topic", 0.2,
            "path", 0.35,
            "preview", 0.75,
            "content", 0.75
    );

    private static final double K1 = 1.2;

    /**
     * Lexical field profile for a single section.
     */
    public record BM25FSectionProfile(
            String sectionRef,
            Map<String, List<String>> fieldTokens
    ) {
    }

    /**
     * BM25F-style section index.
     */
    public record BM25FSectionIndex(
            Map<String, BM25FSectionProfile> profiles,
            Map<String, Integer> documentFrequency,
            Map<String, Double> avgFieldLengths,
            double avgContentLength,
            int documentCount
    ) {
    }

    /**
     * Build BM25F index based on enhanced section summaries.
     *
     * @param sections Section summaries already containing title/item/topic/path/preview fields.
     * @return BM25FSectionIndex instance.
     */
    public BM25FSectionIndex buildSectionIndex(List<Map<String, Object>> sections) {
        Map<String, BM25FSectionProfile> profiles = new HashMap<>();
        Map<String, Integer> documentFrequency = new HashMap<>();
        Map<String, Integer> totalFieldLengths = new HashMap<>();

        for (Map<String, Object> section : sections) {
            String sectionRef = normalizeText(section.get("ref")).strip();
            if (sectionRef.isEmpty()) {
                continue;
            }

            Map<String, List<String>> fieldTokens = new HashMap<>();
            Map<String, String> fieldTexts = Map.of(
                    "title", normalizeText(section.get("title")),
                    "item", normalizeText(section.get("item")),
                    "topic", normalizeText(section.get("topic")),
                    "path", normalizeText(section.get("path")),
                    "preview", normalizeText(section.get("preview"))
            );

            for (Map.Entry<String, String> entry : fieldTexts.entrySet()) {
                List<String> tokens = tokenize(entry.getValue());
                fieldTokens.put(entry.getKey(), tokens);
            }

            profiles.put(sectionRef, new BM25FSectionProfile(sectionRef, fieldTokens));

            Set<String> seenTerms = new HashSet<>();
            for (Map.Entry<String, List<String>> entry : fieldTokens.entrySet()) {
                totalFieldLengths.merge(entry.getKey(), entry.getValue().size(), Integer::sum);
                seenTerms.addAll(entry.getValue());
            }

            for (String term : seenTerms) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }

        int documentCount = profiles.size();
        Map<String, Double> avgFieldLengths = new HashMap<>();
        for (String fieldName : List.of("title", "item", "topic", "path", "preview")) {
            int total = totalFieldLengths.getOrDefault(fieldName, 0);
            avgFieldLengths.put(fieldName, documentCount > 0 ? (double) total / documentCount : 0.0);
        }

        return new BM25FSectionIndex(
                profiles,
                documentFrequency,
                avgFieldLengths,
                avgFieldLengths.getOrDefault("preview", 0.0),
                documentCount
        );
    }

    /**
     * Calculate BM25F-style score for a single search hit.
     *
     * @param entry Search hit entry.
     * @param query Original query term.
     * @param index Pre-built BM25F index.
     * @return BM25F-style score; returns 0.0 when calculation is not possible.
     */
    public double scoreSearchEntry(
            RankedSearchEntry entry,
            String query,
            BM25FSectionIndex index
    ) {
        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty() || index.documentCount() <= 0) {
            return 0.0;
        }

        String sectionRef = entry.getSectionRef();
        if (sectionRef == null || sectionRef.isEmpty()) {
            return 0.0;
        }

        BM25FSectionProfile profile = index.profiles().get(sectionRef);
        if (profile == null) {
            return 0.0;
        }

        List<String> contentTokens = tokenize(extractEntryContentText(entry));

        Map<String, Map<String, Integer>> fieldCounters = new HashMap<>();
        for (Map.Entry<String, List<String>> entryField : profile.fieldTokens().entrySet()) {
            fieldCounters.put(entryField.getKey(), countTokens(entryField.getValue()));
        }
        fieldCounters.put("content", countTokens(contentTokens));

        Map<String, Double> avgFieldLengths = new HashMap<>(index.avgFieldLengths());
        avgFieldLengths.put("content", index.avgContentLength());

        double score = 0.0;
        for (String term : queryTerms) {
            int termDf = index.documentFrequency().getOrDefault(term, 0);
            if (termDf <= 0) {
                continue;
            }

            double idf = Math.log(1.0 + ((index.documentCount() - termDf + 0.5) / (termDf + 0.5)));
            double weightedTf = 0.0;

            for (Map.Entry<String, Double> weightEntry : FIELD_WEIGHTS.entrySet()) {
                String fieldName = weightEntry.getKey();
                double weight = weightEntry.getValue();

                Map<String, Integer> counter = fieldCounters.get(fieldName);
                if (counter == null) {
                    continue;
                }

                int tf = counter.getOrDefault(term, 0);
                if (tf <= 0) {
                    continue;
                }

                int fieldLength = counter.values().stream().mapToInt(Integer::intValue).sum();
                double avgLength = avgFieldLengths.getOrDefault(fieldName, 0.0);
                double normalizedTf = normalizeTf(
                        tf,
                        fieldLength,
                        avgLength,
                        FIELD_B.get(fieldName)
                );

                weightedTf += weight * normalizedTf;
            }

            if (weightedTf <= 0) {
                continue;
            }

            score += idf * (((K1 + 1.0) * weightedTf) / (K1 + weightedTf));
        }

        return Math.round(score * 1_000_000) / 1_000_000.0;
    }

    /**
     * Normalize term frequency according to BM25F formula.
     */
    private double normalizeTf(int tf, int fieldLength, double avgFieldLength, double b) {
        if (tf <= 0) {
            return 0.0;
        }
        if (fieldLength <= 0 || avgFieldLength <= 0) {
            return tf;
        }
        double denominator = 1.0 - b + b * (fieldLength / avgFieldLength);
        if (denominator <= 0) {
            return tf;
        }
        return tf / denominator;
    }

    /**
     * Extract body corpus field from search hit.
     * Prioritizes evidence.context, then matched_text/snippet.
     */
    private String extractEntryContentText(RankedSearchEntry entry) {
        Map<String, Object> evidence = entry.getEvidence();
        if (evidence != null) {
            String context = normalizeText(evidence.get("context"));
            if (!context.isEmpty()) {
                return context;
            }
            String matchedText = normalizeText(evidence.get("matched_text"));
            if (!matchedText.isEmpty()) {
                return matchedText;
            }
        }
        return normalizeText(entry.getSnippet());
    }

    /**
     * Normalize any input to tokenizable text.
     */
    private String normalizeText(Object value) {
        String text = String.valueOf(value != null ? value : "").strip().toLowerCase();
        return String.join(" ", text.split("\\s+"));
    }

    /**
     * Extract ASCII tokens.
     */
    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        var matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    /**
     * Count token frequencies.
     */
    private Map<String, Integer> countTokens(List<String> tokens) {
        Map<String, Integer> counter = new HashMap<>();
        for (String token : tokens) {
            counter.merge(token, 1, Integer::sum);
        }
        return counter;
    }
}
