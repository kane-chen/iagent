package io.invest.iagent.service.kb.search;

import io.invest.iagent.service.kb.search.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SearchEngine.
 */
class SearchEngineTest {

    private SearchEngine searchEngine;
    private BM25FScorer bm25FScorer;

    @BeforeEach
    void setUp() {
        bm25FScorer = new BM25FScorer();
        searchEngine = new SearchEngine(bm25FScorer);
    }

    // =====================================================================
    // Search mode tests
    // =====================================================================

    @Test
    void resolveSearchMode_shouldReturnAutoForNull() {
        assertEquals(SearchConstants.SEARCH_MODE_AUTO, searchEngine.resolveSearchMode(null));
    }

    @Test
    void resolveSearchMode_shouldReturnAutoForEmpty() {
        assertEquals(SearchConstants.SEARCH_MODE_AUTO, searchEngine.resolveSearchMode(""));
    }

    @Test
    void resolveSearchMode_shouldNormalizeCase() {
        assertEquals(SearchConstants.SEARCH_MODE_EXACT, searchEngine.resolveSearchMode("EXACT"));
    }

    @Test
    void resolveSearchMode_shouldThrowForInvalidMode() {
        assertThrows(IllegalArgumentException.class, () -> searchEngine.resolveSearchMode("invalid_mode"));
    }

    // =====================================================================
    // Semantic bucket tests
    // =====================================================================

    @Test
    void resolveSemanticBucket_shouldUseDirectTopicMapping() {
        assertEquals("business", searchEngine.resolveSemanticBucket("business", "", "", ""));
        assertEquals("risk", searchEngine.resolveSemanticBucket("risk_factors", "", "", ""));
        assertEquals("financial", searchEngine.resolveSemanticBucket("mda", "", "", ""));
        assertEquals("governance", searchEngine.resolveSemanticBucket("directors", "", "", ""));
    }

    @Test
    void resolveSemanticBucket_shouldFallbackToKeywordScoring() {
        assertEquals("business", searchEngine.resolveSemanticBucket("", "Market Overview", "Product Strategy", ""));
        assertEquals("risk", searchEngine.resolveSemanticBucket("", "", "Risk Management and Cybersecurity", ""));
        assertEquals("financial", searchEngine.resolveSemanticBucket("", "", "Financial Discussion and Analysis", ""));
    }

    @Test
    void resolveSemanticBucket_shouldReturnOtherForNoMatch() {
        assertEquals("other", searchEngine.resolveSemanticBucket("", "", "", ""));
    }

    // =====================================================================
    // Query diagnosis tests
    // =====================================================================

    @Test
    void diagnoseSearchQuery_shouldHandleEmptyQuery() {
        QueryDiagnosis diagnosis = searchEngine.diagnoseSearchQuery(
                "",
                Map.of(),
                100,
                SearchConstants.SEARCH_MODE_AUTO
        );

        assertEquals("", diagnosis.query());
        assertEquals(0, diagnosis.tokenCount());
        assertEquals(0.0, diagnosis.ambiguityScore());
        assertFalse(diagnosis.isHighAmbiguity());
        assertEquals("general", diagnosis.intent());
        assertTrue(diagnosis.allowDirectTokenFallback());
    }

    @Test
    void diagnoseSearchQuery_shouldClassifyFinancialIntent() {
        QueryDiagnosis diagnosis = searchEngine.diagnoseSearchQuery(
                "revenue growth analysis",
                Map.of("revenue", 50, "growth", 30, "analysis", 20),
                100,
                SearchConstants.SEARCH_MODE_AUTO
        );

        assertEquals("financial", diagnosis.intent());
    }

    @Test
    void diagnoseSearchQuery_shouldClassifyRiskIntent() {
        QueryDiagnosis diagnosis = searchEngine.diagnoseSearchQuery(
                "risk factors cybersecurity threats",
                Map.of("risk", 80, "threats", 40),
                100,
                SearchConstants.SEARCH_MODE_AUTO
        );

        assertEquals("risk", diagnosis.intent());
    }

    @Test
    void diagnoseSearchQuery_shouldDetectHighAmbiguity() {
        // Query with many generic ambiguous terms
        QueryDiagnosis diagnosis = searchEngine.diagnoseSearchQuery(
                "market business strategy risk",
                Map.of("market", 90, "business", 95, "strategy", 85, "risk", 88),
                100,
                SearchConstants.SEARCH_MODE_AUTO
        );

        assertTrue(diagnosis.isHighAmbiguity());
        assertFalse(diagnosis.allowDirectTokenFallback());
    }

    // =====================================================================
    // Query expansion tests
    // =====================================================================

    @Test
    void buildSearchQueryExpansions_shouldGenerateAllExpansionsInAutoMode() {
        List<Map<String, String>> expansions = searchEngine.buildSearchQueryExpansions(
                "revenue",
                SearchConstants.SEARCH_MODE_AUTO
        );

        assertTrue(expansions.size() > 0);

        // Check that strategy is correctly assigned
        Set<String> strategies = new HashSet<>();
        for (Map<String, String> e : expansions) {
            strategies.add(e.get("strategy"));
        }

        assertTrue(strategies.contains(SearchConstants.SEARCH_STRATEGY_PHRASE_VARIANT) ||
                strategies.contains(SearchConstants.SEARCH_STRATEGY_SYNONYM) ||
                strategies.contains(SearchConstants.SEARCH_STRATEGY_TOKEN));
    }

    @Test
    void buildSearchQueryExpansions_shouldOnlyGenerateTokenInKeywordMode() {
        List<Map<String, String>> expansions = searchEngine.buildSearchQueryExpansions(
                "revenue growth",
                SearchConstants.SEARCH_MODE_KEYWORD
        );

        for (Map<String, String> e : expansions) {
            assertEquals(SearchConstants.SEARCH_STRATEGY_TOKEN, e.get("strategy"));
        }
    }

    @Test
    void buildPhraseVariantQueries_shouldHandleHyphenAndSlash() {
        List<String> variants = searchEngine.buildPhraseVariantQueries("state-of-the-art");
        assertTrue(variants.contains("state of the art") || variants.contains("state of art"));
    }

    @Test
    void buildSynonymQueries_shouldFindSynonyms() {
        List<String> synonyms = searchEngine.buildSynonymQueries("revenue");
        assertTrue(synonyms.contains("sales") || synonyms.contains("income"));
    }

    @Test
    void buildTokenQueries_shouldFilterShortTokensAndStopWords() {
        List<String> tokens = searchEngine.buildTokenQueries("the company revenue and sales data");
        assertFalse(tokens.contains("the"));
        assertFalse(tokens.contains("and"));
        assertTrue(tokens.contains("company"));
        assertTrue(tokens.contains("revenue"));
        assertTrue(tokens.contains("sales"));
        assertTrue(tokens.contains("data"));
    }

    @Test
    void expandAsciiTokenInflections_shouldGeneratePluralsAndSingles() {
        List<String> inflections = searchEngine.expandAsciiTokenInflections("companies");
        assertTrue(inflections.contains("company"));

        inflections = searchEngine.expandAsciiTokenInflections("revenue");
        assertTrue(inflections.contains("revenues"));
    }

    // =====================================================================
    // Search plan tests
    // =====================================================================

    @Test
    void buildAdaptiveSearchPlan_shouldRunWithExactInExactMode() {
        QueryDiagnosis diagnosis = new QueryDiagnosis(
                "test",
                List.of("test"),
                1,
                0.0,
                false,
                "general",
                true
        );

        SearchPlan plan = searchEngine.buildAdaptiveSearchPlan(
                "test",
                SearchConstants.SEARCH_MODE_EXACT,
                diagnosis
        );

        assertTrue(plan.runExact());
        assertTrue(plan.expansionPhases().isEmpty());
    }

    @Test
    void buildAdaptiveSearchPlan_shouldHaveGatedFallbackForHighAmbiguity() {
        QueryDiagnosis diagnosis = new QueryDiagnosis(
                "market business strategy",
                List.of("market", "business", "strategy"),
                3,
                0.8,
                true,
                "business_competition",
                false
        );

        SearchPlan plan = searchEngine.buildAdaptiveSearchPlan(
                "market business strategy",
                SearchConstants.SEARCH_MODE_AUTO,
                diagnosis
        );

        assertTrue(plan.runExact());
        assertTrue(plan.fallbackGated());
        assertTrue(plan.scopedBeforeToken());
        assertTrue(plan.expansionPhases().size() > 1); // Non-token and token phases
    }

    // =====================================================================
    // Intent filtering tests
    // =====================================================================

    @Test
    void filterMatchesByIntent_shouldFilterByIntentBucket() {
        QueryDiagnosis diagnosis = new QueryDiagnosis(
                "revenue",
                List.of("revenue"),
                1,
                0.0,
                false,
                "financial",
                true
        );

        Map<String, SectionSemanticProfile> profiles = Map.of(
                "sec1", new SectionSemanticProfile("sec1", "mda", "", "", "", "financial", List.of()),
                "sec2", new SectionSemanticProfile("sec2", "legal", "", "", "", "legal", List.of()),
                "sec3", new SectionSemanticProfile("sec3", "business", "", "", "", "business", List.of())
        );

        List<Map<String, Object>> matches = new ArrayList<>();
        Map<String, Object> m1 = new HashMap<>();
        m1.put("section_ref", "sec1");
        matches.add(m1);
        Map<String, Object> m2 = new HashMap<>();
        m2.put("section_ref", "sec2");
        matches.add(m2);
        Map<String, Object> m3 = new HashMap<>();
        m3.put("section_ref", "sec3");
        matches.add(m3);

        List<Map<String, Object>> filtered = searchEngine.filterMatchesByIntent(matches, diagnosis, profiles);

        assertEquals(2, filtered.size()); // sec1 (financial) and sec3 (business)
    }

    // =====================================================================
    // Deduplication tests
    // =====================================================================

    @Test
    void deduplicateRankedSearchEntries_shouldKeepHigherPriority() {
        List<RankedSearchEntry> entries = new ArrayList<>();

        // Same section but different strategies (lower number = higher priority)
        entries.add(new RankedSearchEntry(
                "sec1",
                "Title",
                1,
                "Revenue increased by 10%",
                null,
                SearchConstants.SEARCH_STRATEGY_TOKEN,
                3, // Lower priority
                "revenue"
        ));

        entries.add(new RankedSearchEntry(
                "sec1",
                "Title",
                1,
                "Revenue increased by 10%",
                null,
                SearchConstants.SEARCH_STRATEGY_EXACT,
                0, // Higher priority
                "revenue"
        ));

        List<RankedSearchEntry> deduped = searchEngine.deduplicateRankedSearchEntries(entries);

        assertEquals(1, deduped.size());
        assertEquals(SearchConstants.SEARCH_STRATEGY_EXACT, deduped.get(0).getStrategy());
    }

    // =====================================================================
    // Ranking tests
    // =====================================================================

    @Test
    void sortRankedSearchEntries_shouldPrioritizeStrategyFirst() {
        List<RankedSearchEntry> entries = new ArrayList<>();

        entries.add(new RankedSearchEntry(
                "sec1", "Title A", 1, "Text A", null,
                SearchConstants.SEARCH_STRATEGY_TOKEN, 3, "query"
        ));
        entries.add(new RankedSearchEntry(
                "sec2", "Title B", 2, "Text B", null,
                SearchConstants.SEARCH_STRATEGY_EXACT, 0, "query"
        ));
        entries.add(new RankedSearchEntry(
                "sec3", "Title C", 3, "Text C", null,
                SearchConstants.SEARCH_STRATEGY_PHRASE_VARIANT, 1, "query"
        ));

        List<RankedSearchEntry> sorted = searchEngine.sortRankedSearchEntries(
                entries,
                null,
                null,
                null
        );

        assertEquals(3, sorted.size());
        assertEquals(SearchConstants.SEARCH_STRATEGY_EXACT, sorted.get(0).getStrategy());
        assertEquals(SearchConstants.SEARCH_STRATEGY_PHRASE_VARIANT, sorted.get(1).getStrategy());
        assertEquals(SearchConstants.SEARCH_STRATEGY_TOKEN, sorted.get(2).getStrategy());
    }

    @Test
    void computeIntentAlignmentScore_shouldScoreCorrectly() {
        QueryDiagnosis diagnosis = new QueryDiagnosis(
                "revenue",
                List.of("revenue"),
                1,
                0.0,
                false,
                "financial",
                true
        );

        Map<String, SectionSemanticProfile> profiles = Map.of(
                "sec1", new SectionSemanticProfile("sec1", "mda", "", "", "", "financial", List.of()),
                "sec2", new SectionSemanticProfile("sec2", "legal", "", "", "", "legal", List.of())
        );

        RankedSearchEntry entry1 = new RankedSearchEntry(
                "sec1", "", null, "", null, "", 0, ""
        );
        RankedSearchEntry entry2 = new RankedSearchEntry(
                "sec2", "", null, "", null, "", 0, ""
        );

        assertEquals(1.0, searchEngine.computeIntentAlignmentScore(entry1, diagnosis, profiles));
        assertEquals(0.0, searchEngine.computeIntentAlignmentScore(entry2, diagnosis, profiles));
    }

    @Test
    void computeContextNoisePenalty_shouldCalculatePenalty() {
        QueryDiagnosis diagnosis = new QueryDiagnosis(
                "market",
                List.of("market"),
                1,
                0.0,
                false,
                "business_competition",
                true
        );

        Map<String, Object> evidenceWithNoise = new HashMap<>();
        evidenceWithNoise.put("context", "antitrust compliance and ethics issues");

        RankedSearchEntry entryWithNoise = new RankedSearchEntry(
                "sec1", "", null, null, evidenceWithNoise, "", 0, ""
        );

        assertTrue(searchEngine.computeContextNoisePenalty(entryWithNoise, diagnosis) > 0);

        // With support terms, penalty should be reduced
        Map<String, Object> evidenceWithSupport = new HashMap<>();
        evidenceWithSupport.put("context", "antitrust compliance in market industry customer product");

        RankedSearchEntry entryWithSupport = new RankedSearchEntry(
                "sec1", "", null, null, evidenceWithSupport, "", 0, ""
        );

        assertEquals(0.8, searchEngine.computeContextNoisePenalty(entryWithSupport, diagnosis));
    }

    // =====================================================================
    // Exact capping tests
    // =====================================================================

    @Test
    void capEntriesWithExactPriority_shouldCapExpansionWhenExactExists() {
        List<RankedSearchEntry> entries = new ArrayList<>();

        // 5 exact entries
        for (int i = 0; i < 5; i++) {
            entries.add(new RankedSearchEntry(
                    "sec" + i, "", i, "", null,
                    SearchConstants.SEARCH_STRATEGY_EXACT, 0, "query"
            ));
        }

        // 15 expansion entries
        for (int i = 5; i < 20; i++) {
            entries.add(new RankedSearchEntry(
                    "sec" + i, "", i, "", null,
                    SearchConstants.SEARCH_STRATEGY_TOKEN, 3, "query"
            ));
        }

        List<RankedSearchEntry> capped = searchEngine.capEntriesWithExactPriority(entries, null);

        assertTrue(capped.size() < entries.size());
        // All exact entries should be preserved
        long exactCount = capped.stream()
                .filter(e -> SearchConstants.SEARCH_STRATEGY_EXACT.equals(e.getStrategy()))
                .count();
        assertEquals(5, exactCount);
    }

    @Test
    void capEntriesWithExactPriority_shouldNotCapWhenNoExact() {
        List<RankedSearchEntry> entries = new ArrayList<>();

        // Only expansion entries
        for (int i = 0; i < 20; i++) {
            entries.add(new RankedSearchEntry(
                    "sec" + i, "", i, "", null,
                    SearchConstants.SEARCH_STRATEGY_TOKEN, 3, "query"
            ));
        }

        List<RankedSearchEntry> capped = searchEngine.capEntriesWithExactPriority(entries, null);

        assertEquals(20, capped.size());
    }

    // =====================================================================
    // Evidence building tests
    // =====================================================================

    @Test
    void centerMatchedText_shouldCenterAroundQuery() {
        String snippet = "This is a very long snippet that contains the query term revenue somewhere in the middle";
        String result = searchEngine.centerMatchedText(snippet, "revenue", 30);

        assertTrue(result.contains("revenue"));
        assertTrue(result.length() <= 30);
    }

    @Test
    void centerMatchedText_shouldFallbackToStartWhenQueryNotFound() {
        String snippet = "This is a very long snippet without the query";
        String result = searchEngine.centerMatchedText(snippet, "notfound", 20);

        assertEquals(snippet.substring(0, 20), result);
    }

    @Test
    void buildEvidenceMatches_shouldConvertFromSnippetToEvidence() {
        List<RankedSearchEntry> entries = new ArrayList<>();
        entries.add(new RankedSearchEntry(
                "sec1",
                "Section Title",
                1,
                "This is the snippet with revenue data",
                null,
                SearchConstants.SEARCH_STRATEGY_EXACT,
                0,
                "revenue"
        ));

        List<EvidenceMatch> matches = searchEngine.buildEvidenceMatches(entries, null, null);

        assertEquals(1, matches.size());
        EvidenceMatch match = matches.get(0);
        assertEquals("sec1", match.section().ref());
        assertEquals("Section Title", match.section().title());
        assertEquals("revenue", match.matchedQuery());
        assertTrue(match.isExactPhrase());
        assertNotNull(match.evidence());
        assertNotNull(match.evidence().get("matched_text"));
        assertNotNull(match.evidence().get("context"));
        assertEquals(1, match.pageNo());
    }

    // =====================================================================
    // BM25F tests
    // =====================================================================

    @Test
    void buildSectionIndex_shouldCreateValidIndex() {
        List<Map<String, Object>> sections = new ArrayList<>();
        Map<String, Object> section1 = new HashMap<>();
        section1.put("ref", "sec1");
        section1.put("title", "Financial Statements");
        section1.put("topic", "mda");
        section1.put("preview", "Revenue increased significantly this year");
        sections.add(section1);

        BM25FScorer.BM25FSectionIndex index = bm25FScorer.buildSectionIndex(sections);

        assertEquals(1, index.documentCount());
        assertTrue(index.profiles().containsKey("sec1"));
        assertTrue(index.documentFrequency().containsKey("revenue"));
        assertTrue(index.avgFieldLengths().containsKey("title"));
    }

    @Test
    void scoreSearchEntry_shouldCalculateScore() {
        List<Map<String, Object>> sections = new ArrayList<>();
        Map<String, Object> section1 = new HashMap<>();
        section1.put("ref", "sec1");
        section1.put("title", "Financial Statements");
        section1.put("topic", "mda");
        section1.put("preview", "Revenue increased significantly this year");
        sections.add(section1);

        BM25FScorer.BM25FSectionIndex index = bm25FScorer.buildSectionIndex(sections);

        RankedSearchEntry entry = new RankedSearchEntry(
                "sec1",
                "Financial Statements",
                1,
                "Revenue increased significantly",
                null,
                SearchConstants.SEARCH_STRATEGY_EXACT,
                0,
                "revenue"
        );

        double score = bm25FScorer.scoreSearchEntry(entry, "revenue", index);

        assertTrue(score > 0);
    }

    // =====================================================================
    // Full pipeline test
    // =====================================================================

    @Test
    void executeSearch_shouldReturnResults() {
        List<Map<String, Object>> sections = new ArrayList<>();

        Map<String, Object> section1 = new HashMap<>();
        section1.put("ref", "sec1");
        section1.put("title", "Item 1. Business");
        section1.put("topic", "business");
        section1.put("preview", "We operate in a competitive market with many competitors");
        sections.add(section1);

        Map<String, Object> section2 = new HashMap<>();
        section2.put("ref", "sec2");
        section2.put("title", "Item 1A. Risk Factors");
        section2.put("topic", "risk_factors");
        section2.put("preview", "We face significant risks from competition and market changes");
        sections.add(section2);

        // Mock processor function that returns hits
        List<EvidenceMatch> results = searchEngine.executeSearch(
                queryWithRef -> {
                    String query = queryWithRef.getKey();
                    List<SearchHit> hits = new ArrayList<>();
                    if (query.contains("market") || query.contains("competition")) {
                        hits.add(new SearchHit(
                                "sec1",
                                "Business",
                                1,
                                "competitive market",
                                null,
                                false
                        ));
                        hits.add(new SearchHit(
                                "sec2",
                                "Risk Factors",
                                2,
                                "market changes risk",
                                null,
                                false
                        ));
                    }
                    return hits;
                },
                "market competition",
                null,
                SearchConstants.SEARCH_MODE_AUTO,
                sections
        );

        assertFalse(results.isEmpty());
    }
}
