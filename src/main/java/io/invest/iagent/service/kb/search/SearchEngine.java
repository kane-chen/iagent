package io.invest.iagent.service.kb.search;

import io.invest.iagent.service.kb.search.model.*;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Search engine core logic.
 *
 * This class implements the complete document search pipeline:
 * - Query diagnosis (ambiguity, intent classification)
 * - Adaptive search plan generation
 * - Query expansion (phrase variants, synonyms, token fallback)
 * - Intent filtering and semantic bucket matching
 * - Ranking (strategy priority → intent consistency → noise penalty → BM25F → proximity)
 * - Deduplication and evidence structure building
 */
public class SearchEngine {

    private final BM25FScorer bm25FScorer;

    public SearchEngine() {
        this.bm25FScorer = new BM25FScorer();
    }

    public SearchEngine(BM25FScorer bm25FScorer) {
        this.bm25FScorer = bm25FScorer;
    }

    // =====================================================================
    // Search match normalization
    // =====================================================================

    /**
     * Normalize search hit structure.
     *
     * Supports two hit modes:
     * - Traditional snippet mode: hits contain snippet field.
     * - Evidence mode: hits contain evidence field.
     *
     * @param hits Processor raw hit list.
     * @return Normalized hit list.
     */
    public List<Map<String, Object>> normalizeSearchMatches(List<SearchHit> hits) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (SearchHit hit : hits) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("section_ref", hit.sectionRef());
            entry.put("section_title", hit.sectionTitle());
            entry.put("page_no", hit.pageNo());

            if (hit.tokenFallback()) {
                entry.put("_token_fallback", true);
            }

            if (hit.evidence() != null) {
                entry.put("evidence", hit.evidence());
            } else {
                entry.put("snippet", hit.snippet() != null ? hit.snippet() : "");
            }

            normalized.add(entry);
        }
        return normalized;
    }

    // =====================================================================
    // Search mode validation
    // =====================================================================

    /**
     * Validate and normalize search mode parameter.
     *
     * @param mode Raw mode parameter, defaults to "auto" when null.
     * @return Normalized search mode string.
     * @throws IllegalArgumentException If mode value is invalid.
     */
    public String resolveSearchMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return SearchConstants.SEARCH_MODE_AUTO;
        }
        String normalized = mode.strip().toLowerCase();
        if (!SearchConstants.VALID_SEARCH_MODES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "Invalid search mode: " + mode + ". Allowed values: " + SearchConstants.VALID_SEARCH_MODES
            );
        }
        return normalized;
    }

    // =====================================================================
    // Section semantic profile building
    // =====================================================================

    /**
     * Build section semantic profiles and query term document frequency.
     *
     * @param sections Semantically enhanced section list.
     * @return Pair of (semanticProfiles, termDocumentFrequency).
     */
    public Map.Entry<Map<String, SectionSemanticProfile>, Map<String, Integer>> buildSectionSemanticProfiles(
            List<Map<String, Object>> sections
    ) {
        Map<String, SectionSemanticProfile> profiles = new HashMap<>();
        Map<String, Integer> termDf = new HashMap<>();

        for (Map<String, Object> section : sections) {
            String sectionRef = String.valueOf(section.getOrDefault("ref", "")).strip();
            if (sectionRef.isEmpty()) {
                continue;
            }

            String topic = String.valueOf(section.getOrDefault("topic", "")).strip().toLowerCase();
            String path = String.valueOf(section.getOrDefault("path", "")).strip();
            String title = String.valueOf(section.getOrDefault("title", "")).strip();
            String item = String.valueOf(section.getOrDefault("item", "")).strip();
            String preview = String.valueOf(section.getOrDefault("preview", "")).strip();

            String bucket = resolveSemanticBucket(topic, path, title, item);
            String lexicalText = String.join(" ", title, item, topic, path, preview).toLowerCase();
            List<String> lexicalTokens = extractAsciiTokens(lexicalText);

            profiles.put(sectionRef, new SectionSemanticProfile(
                    sectionRef,
                    topic,
                    path,
                    title,
                    item,
                    bucket,
                    lexicalTokens
            ));

            for (String token : new HashSet<>(lexicalTokens)) {
                termDf.merge(token, 1, Integer::sum);
            }
        }

        return Map.entry(profiles, termDf);
    }

    /**
     * Normalize semantic bucket from section semantic fields (adaptive solution).
     *
     * Uses two-level decision:
     * 1. Topic direct mapping (first level): topic directly returned if in TOPIC_TO_BUCKET.
     * 2. Keyword scoring (second level fallback): when topic misses, score based on keyword
     *    intersection of path/title/item with BUCKET_KEYWORD_SIGNALS.
     *
     * @param topic Section topic.
     * @param path Section hierarchy path.
     * @param title Section title.
     * @param item Section item number.
     * @return Semantic bucket name (business / risk / financial / governance / people / legal / other).
     */
    public String resolveSemanticBucket(String topic, String path, String title, String item) {
        // Level 1: topic direct mapping (most reliable signal, O(1) lookup)
        String bucket = SearchConstants.TOPIC_TO_BUCKET.get(topic.toLowerCase().strip());
        if (bucket != null) {
            return bucket;
        }

        // Level 2: keyword scoring fallback (for sections without topic or custom topic)
        String text = String.join(" ", path, title, item).toLowerCase();
        Set<String> words = new HashSet<>(extractAsciiTokens(text));

        String bestBucket = "other";
        int bestScore = 0;

        for (Map.Entry<String, Set<String>> entry : SearchConstants.BUCKET_KEYWORD_SIGNALS.entrySet()) {
            String candidate = entry.getKey();
            Set<String> keywords = entry.getValue();
            int score = 0;
            for (String word : words) {
                if (keywords.contains(word)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestBucket = candidate;
            }
        }

        return bestBucket;
    }

    // =====================================================================
    // Query diagnosis and intent classification
    // =====================================================================

    /**
     * Diagnose query ambiguity and intent for adaptive retrieval.
     *
     * @param query Normalized query term.
     * @param termDocumentFrequency Term document frequency.
     * @param documentCount Number of document sections.
     * @param mode Search mode.
     * @return QueryDiagnosis structure.
     */
    public QueryDiagnosis diagnoseSearchQuery(
            String query,
            Map<String, Integer> termDocumentFrequency,
            int documentCount,
            String mode
    ) {
        List<String> tokens = extractAsciiTokens(query.toLowerCase());
        int tokenCount = tokens.size();

        if (tokenCount == 0) {
            return new QueryDiagnosis(
                    query,
                    tokens,
                    0,
                    0.0,
                    false,
                    "general",
                    true
            );
        }

        long genericHits = tokens.stream()
                .filter(SearchConstants.GENERIC_AMBIGUOUS_TOKENS::contains)
                .count();
        double genericRatio = (double) genericHits / tokenCount;

        double dfRatio = 0.0;
        for (String token : tokens) {
            int tokenDf = termDocumentFrequency.getOrDefault(token, 0);
            if (documentCount > 0) {
                dfRatio += Math.min(1.0, (double) tokenDf / documentCount);
            }
        }
        dfRatio = dfRatio / tokenCount;

        double shortQueryFactor = tokenCount <= 2 ? 1.0 : 0.0;
        double ambiguityScore = Math.round(((genericRatio + dfRatio + shortQueryFactor) / 3.0) * 10000) / 10000.0;
        boolean isHighAmbiguity = ambiguityScore >= 0.62;
        String intent = classifyQueryIntent(tokens);
        boolean allowDirectTokenFallback = !(SearchConstants.SEARCH_MODE_AUTO.equals(mode) && isHighAmbiguity);

        return new QueryDiagnosis(
                query,
                tokens,
                tokenCount,
                ambiguityScore,
                isHighAmbiguity,
                intent,
                allowDirectTokenFallback
        );
    }

    /**
     * Estimate query intent from tokens.
     *
     * @param tokens Query token list.
     * @return Intent name.
     */
    public String classifyQueryIntent(List<String> tokens) {
        if (tokens.isEmpty()) {
            return "general";
        }

        String bestIntent = "general";
        int bestScore = 0;
        Set<String> tokenSet = new HashSet<>(tokens);

        for (Map.Entry<String, Set<String>> entry : SearchConstants.INTENT_KEYWORDS.entrySet()) {
            String intent = entry.getKey();
            Set<String> keywords = entry.getValue();
            int score = 0;
            for (String token : tokenSet) {
                if (keywords.contains(token)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestIntent = intent;
            }
        }

        return bestIntent;
    }

    // =====================================================================
    // Adaptive search plan
    // =====================================================================

    /**
     * Generate search execution plan based on query diagnosis.
     *
     * @param query Normalized query term.
     * @param mode Search mode.
     * @param diagnosis Query diagnosis result.
     * @return SearchPlan.
     */
    public SearchPlan buildAdaptiveSearchPlan(
            String query,
            String mode,
            QueryDiagnosis diagnosis
    ) {
        boolean runExact = SearchConstants.SEARCH_MODE_AUTO.equals(mode)
                || SearchConstants.SEARCH_MODE_EXACT.equals(mode);
        boolean runExpansion = SearchConstants.SEARCH_MODE_AUTO.equals(mode)
                || SearchConstants.SEARCH_MODE_KEYWORD.equals(mode)
                || SearchConstants.SEARCH_MODE_SEMANTIC.equals(mode);

        if (!runExpansion) {
            return new SearchPlan(
                    runExact,
                    List.of(),
                    false,
                    false
            );
        }

        List<Map<String, String>> expansions = buildSearchQueryExpansions(query, mode);

        if (SearchConstants.SEARCH_MODE_KEYWORD.equals(mode)) {
            return new SearchPlan(
                    false,
                    List.of(expansions),
                    false,
                    false
            );
        }

        if (SearchConstants.SEARCH_MODE_AUTO.equals(mode) && diagnosis.isHighAmbiguity()) {
            List<Map<String, String>> nonToken = expansions.stream()
                    .filter(e -> !SearchConstants.SEARCH_STRATEGY_TOKEN.equals(e.get("strategy")))
                    .collect(Collectors.toList());
            List<Map<String, String>> tokenOnly = expansions.stream()
                    .filter(e -> SearchConstants.SEARCH_STRATEGY_TOKEN.equals(e.get("strategy")))
                    .collect(Collectors.toList());

            List<List<Map<String, String>>> phases = new ArrayList<>();
            if (!nonToken.isEmpty()) {
                phases.add(nonToken);
            }
            if (!tokenOnly.isEmpty()) {
                phases.add(tokenOnly);
            }

            return new SearchPlan(
                    true,
                    phases,
                    true,
                    true
            );
        }

        return new SearchPlan(
                runExact,
                List.of(expansions),
                false,
                false
        );
    }

    // =====================================================================
    // Intent filtering
    // =====================================================================

    /**
     * Filter hit set by query intent.
     *
     * @param matches Original hit list.
     * @param diagnosis Query diagnosis result.
     * @param semanticProfiles Section semantic profiles.
     * @return Filtered hit list, returns empty list if no matches.
     */
    public List<Map<String, Object>> filterMatchesByIntent(
            List<Map<String, Object>> matches,
            QueryDiagnosis diagnosis,
            Map<String, SectionSemanticProfile> semanticProfiles
    ) {
        Set<String> expectedBuckets = SearchConstants.EXPECTED_BUCKETS_BY_INTENT.get(diagnosis.intent());
        if (expectedBuckets == null || expectedBuckets.isEmpty()) {
            return matches;
        }

        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> match : matches) {
            String sectionRef = String.valueOf(match.getOrDefault("section_ref", "")).strip();
            SectionSemanticProfile profile = semanticProfiles.get(sectionRef);
            if (profile == null) {
                continue;
            }
            if (expectedBuckets.contains(profile.bucket())) {
                filtered.add(match);
            }
        }

        return filtered;
    }

    // =====================================================================
    // Query expansion
    // =====================================================================

    /**
     * Build search expansion query set.
     *
     * Expansion order is fixed as:
     * 1. phrase_variant (word form and separator variants)
     * 2. synonym (synonym / terminology mapping)
     * 3. token (keyword split fallback)
     *
     * @param query Original query term.
     * @param mode Search mode, affects which expansion strategies are used.
     * @return Expansion query list, each item contains query and strategy.
     */
    public List<Map<String, String>> buildSearchQueryExpansions(String query, String mode) {
        List<Map<String, String>> expansions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(normalizeSearchQueryForKey(query));

        boolean includePhraseVariant = !SearchConstants.SEARCH_MODE_KEYWORD.equals(mode);
        boolean includeSynonym = !SearchConstants.SEARCH_MODE_KEYWORD.equals(mode);

        if (includePhraseVariant) {
            List<String> phraseVariants = buildPhraseVariantQueries(query);
            for (String variant : phraseVariants) {
                appendSearchExpansion(expansions, seen, variant, SearchConstants.SEARCH_STRATEGY_PHRASE_VARIANT);
            }
        }

        if (includeSynonym) {
            List<String> synonymQueries = buildSynonymQueries(query);
            for (String synonymQuery : synonymQueries) {
                appendSearchExpansion(expansions, seen, synonymQuery, SearchConstants.SEARCH_STRATEGY_SYNONYM);
            }
        }

        List<String> tokenQueries = buildTokenQueries(query);
        for (String tokenQuery : tokenQueries) {
            appendSearchExpansion(expansions, seen, tokenQuery, SearchConstants.SEARCH_STRATEGY_TOKEN);
        }

        return expansions;
    }

    private void appendSearchExpansion(
            List<Map<String, String>> expansions,
            Set<String> seen,
            String query,
            String strategy
    ) {
        String normalized = normalizeOptionalText(query);
        if (normalized == null) {
            return;
        }
        String key = normalizeSearchQueryForKey(normalized);
        if (seen.contains(key)) {
            return;
        }
        seen.add(key);
        Map<String, String> entry = new HashMap<>();
        entry.put("query", normalized);
        entry.put("strategy", strategy);
        expansions.add(entry);
    }

    private String normalizeSearchQueryForKey(String query) {
        String normalized = normalizeOptionalText(query);
        if (normalized == null) {
            return "";
        }
        String lowered = normalized.toLowerCase();
        return SearchConstants.SPACE_NORMALIZE_PATTERN.matcher(lowered).replaceAll(" ").strip();
    }

    private String normalizeOptionalText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }

    /**
     * Generate phrase variant queries.
     *
     * @param query Original query term.
     * @return Variant query list.
     */
    public List<String> buildPhraseVariantQueries(String query) {
        String normalized = normalizeOptionalText(query);
        if (normalized == null) {
            return List.of();
        }

        Set<String> variants = new HashSet<>();
        if (normalized.contains("-")) {
            variants.add(normalized.replace("-", " "));
        }
        if (normalized.contains("/")) {
            variants.add(normalized.replace("/", " "));
        }

        String lowered = normalized.toLowerCase();
        List<String> asciiTokens = extractAsciiTokens(lowered);
        if (!asciiTokens.isEmpty()) {
            for (int i = 0; i < asciiTokens.size(); i++) {
                String token = asciiTokens.get(i);
                List<String> inflections = expandAsciiTokenInflections(token);
                for (String inflection : inflections) {
                    List<String> replaced = new ArrayList<>(asciiTokens);
                    replaced.set(i, inflection);
                    variants.add(String.join(" ", replaced));
                }
            }
        }

        String normalizedKey = normalizeSearchQueryForKey(normalized);
        List<String> orderedVariants = new ArrayList<>();
        for (String candidate : variants) {
            String candidateKey = normalizeSearchQueryForKey(candidate);
            if (candidateKey.isEmpty() || candidateKey.equals(normalizedKey)) {
                continue;
            }
            orderedVariants.add(candidate);
        }
        Collections.sort(orderedVariants);

        return orderedVariants;
    }

    /**
     * Generate synonym expansion queries.
     *
     * @param query Original query term.
     * @return Synonym query list.
     */
    public List<String> buildSynonymQueries(String query) {
        String normalizedKey = normalizeSearchQueryForKey(query);
        if (normalizedKey.isEmpty()) {
            return List.of();
        }

        Set<String> synonyms = new HashSet<>();
        for (String[] group : SearchConstants.SEARCH_SYNONYM_GROUPS) {
            Map<String, String> groupKeys = new HashMap<>();
            for (String item : group) {
                groupKeys.put(normalizeSearchQueryForKey(item), item);
            }
            if (!groupKeys.containsKey(normalizedKey)) {
                continue;
            }
            for (Map.Entry<String, String> entry : groupKeys.entrySet()) {
                if (entry.getKey().equals(normalizedKey)) {
                    continue;
                }
                synonyms.add(entry.getValue());
            }
        }

        List<String> orderedSynonyms = new ArrayList<>(synonyms);
        orderedSynonyms.sort(Comparator.comparing(this::normalizeSearchQueryForKey));

        return orderedSynonyms;
    }

    /**
     * Generate token fallback queries.
     *
     * @param query Original query term.
     * @return Token query list.
     */
    public List<String> buildTokenQueries(String query) {
        List<String> tokens = extractAsciiTokens(query.toLowerCase());
        List<String> result = new ArrayList<>();
        for (String token : tokens) {
            if (token.length() < 3 || SearchConstants.TOKEN_STOP_WORDS.contains(token)) {
                continue;
            }
            result.add(token);
        }
        return result;
    }

    /**
     * Extract English/number tokens from query.
     *
     * @param query Original query term.
     * @return Token list.
     */
    public List<String> extractAsciiTokens(String query) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = SearchConstants.WORD_SPLIT_PATTERN.matcher(query != null ? query : "");
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    /**
     * Generate simple word form inflections for English tokens.
     *
     * @param token Original token.
     * @return Word form inflection list (excluding original token).
     */
    public List<String> expandAsciiTokenInflections(String token) {
        String normalized = token.strip().toLowerCase();
        if (normalized.length() < 3) {
            return List.of();
        }

        Set<String> variants = new HashSet<>();
        if (normalized.endsWith("ies") && normalized.length() > 4) {
            variants.add(normalized.substring(0, normalized.length() - 3) + "y");
        }
        if (normalized.endsWith("es") && normalized.length() > 3) {
            variants.add(normalized.substring(0, normalized.length() - 2));
        }
        if (normalized.endsWith("s") && normalized.length() > 3) {
            variants.add(normalized.substring(0, normalized.length() - 1));
        } else {
            variants.add(normalized + "s");
        }

        variants.remove(normalized);
        List<String> ordered = new ArrayList<>(variants);
        Collections.sort(ordered);

        return ordered;
    }

    // =====================================================================
    // Hit ranking and deduplication
    // =====================================================================

    /**
     * Build ranked search hit entries with strategy priority.
     *
     * @param matches Normalized hit list.
     * @param strategy Hit strategy name.
     * @param query Original query term that generated this hit.
     * @return Ranked hit entry list with weight.
     */
    public List<RankedSearchEntry> buildRankedSearchEntries(
            List<Map<String, Object>> matches,
            String strategy,
            String query
    ) {
        int priority = SearchConstants.SEARCH_STRATEGY_PRIORITY.getOrDefault(strategy, 999);
        List<RankedSearchEntry> result = new ArrayList<>();

        for (Map<String, Object> match : matches) {
            String sectionRef = String.valueOf(match.getOrDefault("section_ref", "")).strip();
            String sectionTitle = String.valueOf(match.getOrDefault("section_title", "")).strip();
            Integer pageNo = match.get("page_no") instanceof Integer ? (Integer) match.get("page_no") : null;
            String snippet = String.valueOf(match.getOrDefault("snippet", "")).strip();
            @SuppressWarnings("unchecked")
            Map<String, Object> evidence = match.get("evidence") instanceof Map
                    ? (Map<String, Object>) match.get("evidence")
                    : null;

            result.add(new RankedSearchEntry(
                    sectionRef,
                    sectionTitle,
                    pageNo,
                    snippet,
                    evidence,
                    strategy,
                    priority,
                    query
            ));
        }

        return result;
    }

    /**
     * Deduplicate ranked search entries and keep higher priority entries.
     *
     * @param entries Original weighted hit list.
     * @return Deduplicated hit list.
     */
    public List<RankedSearchEntry> deduplicateRankedSearchEntries(List<RankedSearchEntry> entries) {
        Map<String, RankedSearchEntry> selected = new HashMap<>();

        for (RankedSearchEntry entry : entries) {
            String sectionRef = entry.getSectionRef() != null ? entry.getSectionRef() : "";
            String sectionTitle = entry.getSectionTitle() != null ? entry.getSectionTitle() : "";
            String contentKey = "";

            if (entry.getEvidence() != null) {
                Map<String, Object> evidence = entry.getEvidence();
                contentKey = String.valueOf(evidence.getOrDefault("context",
                        evidence.getOrDefault("matched_text", "")));
            } else {
                contentKey = entry.getSnippet() != null ? entry.getSnippet() : "";
            }

            String pageNo = String.valueOf(entry.getPageNo() != null ? entry.getPageNo() : "");
            String key = String.join("|", sectionRef, sectionTitle, contentKey, pageNo);

            RankedSearchEntry current = selected.get(key);
            if (current == null || entry.getPriority() < current.getPriority()) {
                selected.put(key, entry);
            }
        }

        return new ArrayList<>(selected.values());
    }

    /**
     * Calculate keyword proximity score in hit entry.
     *
     * For multi-word queries, the closer keywords are in the text, the lower the score
     * (better ranking). Returns 0 for single-word queries or when calculation not possible.
     *
     * @param entry Search hit entry with evidence.
     * @return Proximity score (smaller is better), 0 when calculation not possible.
     */
    public int computeKeywordProximityScore(RankedSearchEntry entry) {
        Map<String, Object> evidence = entry.getEvidence();
        if (evidence == null) {
            return 0;
        }

        String context = String.valueOf(evidence.getOrDefault("context", "")).toLowerCase();
        if (context.isEmpty()) {
            return 0;
        }

        String matchedText = String.valueOf(evidence.getOrDefault("matched_text", "")).toLowerCase();
        List<String> queryTokens = extractAsciiTokens(matchedText);
        queryTokens = queryTokens.stream()
                .filter(t -> t.length() >= 3 && !SearchConstants.TOKEN_STOP_WORDS.contains(t))
                .collect(Collectors.toList());

        if (queryTokens.size() < 2) {
            return 0;
        }

        List<String> contextTokens = extractAsciiTokens(context);
        Map<String, List<Integer>> tokenPositions = new HashMap<>();

        for (int pos = 0; pos < contextTokens.size(); pos++) {
            String ct = contextTokens.get(pos);
            for (String qt : queryTokens) {
                if (ct.equals(qt) || ct.startsWith(qt) || qt.startsWith(ct)) {
                    tokenPositions.computeIfAbsent(qt, k -> new ArrayList<>()).add(pos);
                }
            }
        }

        if (tokenPositions.size() < 2) {
            return 0;
        }

        List<Integer> firstPositions = new ArrayList<>();
        for (List<Integer> positions : tokenPositions.values()) {
            if (!positions.isEmpty()) {
                firstPositions.add(positions.get(0));
            }
        }

        if (firstPositions.size() < 2) {
            return 0;
        }

        int minPos = Collections.min(firstPositions);
        int maxPos = Collections.max(firstPositions);

        return maxPos - minPos;
    }

    /**
     * Stable sorting of hit entries.
     *
     * Sorting axes: strategy priority → intent alignment (desc) → noise penalty (asc)
     * → BM25F score (desc) → keyword proximity → section ref → page number → content text.
     *
     * @param entries Deduplicated hit entry list.
     * @param bm25fIndex Optional BM25F index; skips this ranking signal when null.
     * @param diagnosis Optional query diagnosis result.
     * @param semanticProfiles Optional section semantic profile index.
     * @return Sorted hit entry list.
     */
    public List<RankedSearchEntry> sortRankedSearchEntries(
            List<RankedSearchEntry> entries,
            BM25FScorer.BM25FSectionIndex bm25fIndex,
            QueryDiagnosis diagnosis,
            Map<String, SectionSemanticProfile> semanticProfiles
    ) {
        for (RankedSearchEntry item : entries) {
            String query = item.getQuery() != null ? item.getQuery().strip() : "";
            if (bm25fIndex == null || query.isEmpty()) {
                item.setBm25fScore(0.0);
            } else {
                item.setBm25fScore(bm25FScorer.scoreSearchEntry(item, query, bm25fIndex));
            }
            item.setIntentAlignmentScore(computeIntentAlignmentScore(item, diagnosis, semanticProfiles));
            item.setContextNoisePenalty(computeContextNoisePenalty(item, diagnosis));
        }

        return entries.stream()
                .sorted(Comparator
                        .comparingInt(RankedSearchEntry::getPriority)
                        .thenComparingDouble(RankedSearchEntry::getIntentAlignmentScore).reversed()
                        .thenComparingDouble(RankedSearchEntry::getContextNoisePenalty)
                        .thenComparingDouble(RankedSearchEntry::getBm25fScore).reversed()
                        .thenComparingInt(this::computeKeywordProximityScore)
                        .thenComparing(e -> e.getSectionRef() != null ? e.getSectionRef() : "")
                        .thenComparingInt(e -> e.getPageNo() != null ? e.getPageNo() : 0)
                        .thenComparing(e -> {
                            if (e.getEvidence() != null) {
                                return String.valueOf(e.getEvidence().getOrDefault("context", ""));
                            }
                            return e.getSnippet() != null ? e.getSnippet() : "";
                        })
                )
                .collect(Collectors.toList());
    }

    /**
     * Calculate alignment score between hit and query intent.
     *
     * @param entry Search hit entry.
     * @param diagnosis Query diagnosis result.
     * @param semanticProfiles Section semantic profile index.
     * @return Alignment score, range 0~1.
     */
    public double computeIntentAlignmentScore(
            RankedSearchEntry entry,
            QueryDiagnosis diagnosis,
            Map<String, SectionSemanticProfile> semanticProfiles
    ) {
        if (diagnosis == null || semanticProfiles == null) {
            return 0.0;
        }
        if ("general".equals(diagnosis.intent())) {
            return 0.0;
        }
        String sectionRef = entry.getSectionRef() != null ? entry.getSectionRef().strip() : "";
        if (sectionRef.isEmpty()) {
            return 0.0;
        }
        SectionSemanticProfile profile = semanticProfiles.get(sectionRef);
        if (profile == null) {
            return 0.0;
        }
        Set<String> expectedBuckets = SearchConstants.EXPECTED_BUCKETS_BY_INTENT.get(diagnosis.intent());
        if (expectedBuckets == null || expectedBuckets.isEmpty()) {
            return 0.0;
        }
        return expectedBuckets.contains(profile.bucket()) ? 1.0 : 0.0;
    }

    /**
     * Calculate hit context noise penalty score.
     *
     * @param entry Search hit entry.
     * @param diagnosis Query diagnosis result.
     * @return Penalty score (larger means more noise).
     */
    public double computeContextNoisePenalty(
            RankedSearchEntry entry,
            QueryDiagnosis diagnosis
    ) {
        if (diagnosis == null) {
            return 0.0;
        }
        Set<String> noiseTerms = SearchConstants.NOISE_CONTEXT_TOKENS_BY_INTENT.get(diagnosis.intent());
        Set<String> supportTerms = SearchConstants.SUPPORT_CONTEXT_TOKENS_BY_INTENT.get(diagnosis.intent());
        if (noiseTerms == null) {
            return 0.0;
        }

        String context = "";
        Map<String, Object> evidence = entry.getEvidence();
        if (evidence != null) {
            context = String.valueOf(evidence.getOrDefault("context",
                    evidence.getOrDefault("matched_text", "")));
        }
        if (context.isEmpty()) {
            context = entry.getSnippet() != null ? entry.getSnippet() : "";
        }

        List<String> tokens = extractAsciiTokens(context.toLowerCase());
        if (tokens.isEmpty()) {
            return 0.0;
        }

        Set<String> tokenSet = new HashSet<>(tokens);
        int noiseHits = 0;
        for (String term : noiseTerms) {
            if (tokenSet.contains(term)) {
                noiseHits++;
            }
        }

        if (noiseHits <= 0) {
            return 0.0;
        }

        int supportHits = 0;
        if (supportTerms != null) {
            for (String term : supportTerms) {
                if (tokenSet.contains(term)) {
                    supportHits++;
                }
            }
        }

        // When industry support words appear in context, reduce penalty to avoid
        //误伤真实业务竞争描述
        return supportHits > 0 ? 0.8 : Math.min(2.0, 0.6 + (0.25 * noiseHits));
    }

    // =====================================================================
    // Exact priority capping
    // =====================================================================

    /**
     * Exact priority capping: when exact hits exist, compress expansion result proportion.
     *
     * All exact hits are preserved; expansion results occupy at most 30% of total capacity
     * (at least 2 entries preserved). Trimming is not triggered when total entries are
     * below CAP_MIN_TRIGGER.
     *
     * @param sortedEntries Sorted search entries (exact first).
     * @param displayBudget Optional display budget upper limit.
     * @return Trimmed entry list.
     */
    public List<RankedSearchEntry> capEntriesWithExactPriority(
            List<RankedSearchEntry> sortedEntries,
            Integer displayBudget
    ) {
        int total = sortedEntries.size();
        if (total < SearchConstants.CAP_MIN_TRIGGER) {
            return sortedEntries;
        }

        List<RankedSearchEntry> exactEntries = new ArrayList<>();
        List<RankedSearchEntry> expansionEntries = new ArrayList<>();

        for (RankedSearchEntry entry : sortedEntries) {
            if (SearchConstants.SEARCH_STRATEGY_EXACT.equals(entry.getStrategy())) {
                exactEntries.add(entry);
            } else {
                expansionEntries.add(entry);
            }
        }

        // No exact hits: don't trim expansion
        if (exactEntries.isEmpty()) {
            return sortedEntries;
        }

        // Expansion quota = total * ratio cap, preserve at least 2 entries
        int expansionCap = Math.max(2, (int) (total * SearchConstants.EXPANSION_RATIO_WHEN_EXACT_EXISTS));

        // When displayBudget exists and all exact entries can fit,
        // tighten expansion quota so total count does not exceed displayBudget
        if (displayBudget != null && exactEntries.size() <= displayBudget) {
            int budgetRemaining = displayBudget - exactEntries.size();
            expansionCap = Math.min(expansionCap, Math.max(2, budgetRemaining));
        }

        List<RankedSearchEntry> cappedExpansion = expansionEntries.size() > expansionCap
                ? expansionEntries.subList(0, expansionCap)
                : expansionEntries;

        List<RankedSearchEntry> result = new ArrayList<>(exactEntries);
        result.addAll(cappedExpansion);

        return result;
    }

    // =====================================================================
    // Evidence structure building
    // =====================================================================

    /**
     * Extract summary text around query hit position from snippet.
     *
     * Finds query position in snippet and截取 max_chars centered on that position;
     * falls back to snippet head截取 if query not found in snippet.
     *
     * @param snippet Complete snippet text.
     * @param query Query term.
     * @param maxChars Maximum characters.
     * @return Summary text around query hit position.
     */
    public String centerMatchedText(String snippet, String query, int maxChars) {
        if (snippet == null || snippet.isEmpty()) {
            return "";
        }
        if (snippet.length() <= maxChars) {
            return snippet;
        }
        String normalizedQuery = query != null ? query.strip() : "";
        if (normalizedQuery.isEmpty()) {
            return snippet.substring(0, maxChars);
        }

        Pattern pattern = Pattern.compile(Pattern.quote(normalizedQuery), Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(snippet);
        if (!matcher.find()) {
            return snippet.substring(0, maxChars);
        }

        int leftBudget = Math.max(1, maxChars / 2);
        int start = Math.max(0, matcher.start() - leftBudget);
        int end = Math.min(snippet.length(), start + maxChars);
        start = Math.max(0, end - maxChars);

        return snippet.substring(start, end);
    }

    /**
     * Convert sorted search entries to evidence-based return format.
     *
     * Each hit contains:
     * - evidence composite structure (matched_text + context + match_position)
     * - matched_query: original query term that produced this hit
     * - isExactPhrase: whether it's an exact phrase match from original text
     * - topic semantic annotation
     *
     * @param sortedEntries Sorted and deduplicated search entries.
     * @param formType Document formType (used for semantic parsing).
     * @param refToTopic Optional pre-built section_ref → topic index for child section topic fallback.
     * @return Evidence-based hit list.
     */
    public List<EvidenceMatch> buildEvidenceMatches(
            List<RankedSearchEntry> sortedEntries,
            String formType,
            Map<String, String> refToTopic
    ) {
        List<EvidenceMatch> matches = new ArrayList<>();

        for (RankedSearchEntry entry : sortedEntries) {
            String sectionRef = entry.getSectionRef();
            String sectionTitle = entry.getSectionTitle();

            // Parse section semantics (simplified - real implementation would use section semantic parser)
            String itemNumber = null; // Would be extracted by section semantic parser
            String topic = null; // Would be extracted by section semantic parser

            // Child section unable to self-parse: fallback to pre-built index
            if (topic == null && refToTopic != null && sectionRef != null) {
                topic = refToTopic.get(sectionRef);
            }

            // Query attribution and precision
            String matchedQuery = entry.getQuery() != null ? entry.getQuery() : "";
            String strategy = entry.getStrategy() != null ? entry.getStrategy() : "";
            boolean isExact = SearchConstants.SEARCH_STRATEGY_EXACT.equals(strategy);

            // Build evidence structure
            Map<String, Object> evidence = entry.getEvidence();
            if (evidence == null) {
                // Compatible with traditional snippet hits: upgrade to evidence structure,
                // matched_text centered around query
                String snippetText = entry.getSnippet() != null ? entry.getSnippet() : "";
                evidence = new HashMap<>();
                evidence.put("matched_text", centerMatchedText(snippetText, matchedQuery,
                        SearchConstants.MATCHED_TEXT_MAX_CHARS));
                evidence.put("context", snippetText);
            }

            // Section object: unified structure with get_document_sections / read_section
            String itemLabel = itemNumber != null ? "Item " + itemNumber : null;
            EvidenceMatch.SectionInfo sectionInfo = new EvidenceMatch.SectionInfo(
                    sectionRef,
                    sectionTitle,
                    itemLabel,
                    topic
            );

            matches.add(new EvidenceMatch(
                    sectionInfo,
                    matchedQuery,
                    isExact,
                    evidence,
                    entry.getPageNo()
            ));
        }

        return matches;
    }

    /**
     * Execute complete search pipeline for a single query.
     *
     * @param processor Search function that takes query and withinRef, returns list of SearchHit.
     * @param query Search query.
     * @param withinRef Optional within section reference.
     * @param mode Search mode.
     * @param sections Document sections for semantic profiling and BM25F.
     * @return List of evidence matches.
     */
    public List<EvidenceMatch> executeSearch(
            Function<Map.Entry<String, String>, List<SearchHit>> processor,
            String query,
            String withinRef,
            String mode,
            List<Map<String, Object>> sections
    ) {
        // Build semantic profiles and term frequency
        Map.Entry<Map<String, SectionSemanticProfile>, Map<String, Integer>> profileResult =
                buildSectionSemanticProfiles(sections);
        Map<String, SectionSemanticProfile> semanticProfiles = profileResult.getKey();
        Map<String, Integer> termDf = profileResult.getValue();

        // Diagnose query
        String resolvedMode = resolveSearchMode(mode);
        QueryDiagnosis diagnosis = diagnoseSearchQuery(query, termDf, sections.size(), resolvedMode);

        // Build search plan
        SearchPlan plan = buildAdaptiveSearchPlan(query, resolvedMode, diagnosis);

        // Build BM25F index
        BM25FScorer.BM25FSectionIndex bm25fIndex = bm25FScorer.buildSectionIndex(sections);

        // Execute search and collect ranked entries
        List<RankedSearchEntry> allEntries = new ArrayList<>();

        if (plan.runExact()) {
            String exactQuery = query.replace("\"", "").strip();
            String finalQuery = exactQuery.isEmpty() ? query : exactQuery;
            List<SearchHit> exactHits = processor.apply(new AbstractMap.SimpleEntry<>(finalQuery, withinRef));
            List<Map<String, Object>> normalized = normalizeSearchMatches(exactHits);

            // Separate true exact matches from processor-level token fallback matches
            List<Map<String, Object>> exactMatches = new ArrayList<>();
            List<Map<String, Object>> tokenFallbackMatches = new ArrayList<>();

            for (Map<String, Object> m : normalized) {
                if (Boolean.TRUE.equals(m.get("_token_fallback"))) {
                    tokenFallbackMatches.add(m);
                } else {
                    exactMatches.add(m);
                }
            }

            if (!exactMatches.isEmpty()) {
                allEntries.addAll(buildRankedSearchEntries(
                        exactMatches,
                        SearchConstants.SEARCH_STRATEGY_EXACT,
                        query
                ));
            }

            if (!tokenFallbackMatches.isEmpty()) {
                allEntries.addAll(buildRankedSearchEntries(
                        tokenFallbackMatches,
                        SearchConstants.SEARCH_STRATEGY_TOKEN,
                        query
                ));
            }
        }

        // Execute expansion phases
        for (List<Map<String, String>> phase : plan.expansionPhases()) {
            for (Map<String, String> expansion : phase) {
                String expandedQuery = expansion.get("query");
                String strategy = expansion.get("strategy");

                List<SearchHit> hits = processor.apply(new AbstractMap.SimpleEntry<>(expandedQuery, withinRef));
                List<Map<String, Object>> normalized = normalizeSearchMatches(hits);

                if (normalized.isEmpty()) {
                    continue;
                }

                // Apply intent filtering for token phase when scoped before token
                if (diagnosis.intent() != null && !"general".equals(diagnosis.intent()) && plan.scopedBeforeToken()) {
                    boolean isTokenPhase = SearchConstants.SEARCH_STRATEGY_TOKEN.equals(strategy);
                    boolean strictScope = isTokenPhase && plan.fallbackGated();

                    List<Map<String, Object>> filtered = filterMatchesByIntent(
                            normalized,
                            diagnosis,
                            semanticProfiles
                    );

                    if (!filtered.isEmpty()) {
                        normalized = filtered;
                    } else if (strictScope) {
                        // Mark that we had to open up token fallback
                        for (Map<String, Object> item : normalized) {
                            item.put("_token_fallback_opened", true);
                        }
                    }
                }

                if (normalized.isEmpty()) {
                    continue;
                }

                allEntries.addAll(buildRankedSearchEntries(normalized, strategy, expandedQuery));
            }
        }

        // Deduplicate, sort, cap, and build evidence matches
        List<RankedSearchEntry> deduped = deduplicateRankedSearchEntries(allEntries);
        List<RankedSearchEntry> sorted = sortRankedSearchEntries(deduped, bm25fIndex, diagnosis, semanticProfiles);
        List<RankedSearchEntry> capped = capEntriesWithExactPriority(sorted, null);

        return buildEvidenceMatches(capped, null, null);
    }
}
