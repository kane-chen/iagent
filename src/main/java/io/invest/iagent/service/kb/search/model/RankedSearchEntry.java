package io.invest.iagent.service.kb.search.model;

import java.util.Map;

/**
 * Ranked search entry with strategy and scoring metadata.
 *
 * Contains the search result along with ranking metadata used for sorting
 * and deduplication.
 */
public class RankedSearchEntry {
    private final String sectionRef;
    private final String sectionTitle;
    private final Integer pageNo;
    private final String snippet;
    private final Map<String, Object> evidence;
    private final String strategy;
    private final int priority;
    private final String query;
    private double bm25fScore;
    private double intentAlignmentScore;
    private double contextNoisePenalty;

    public RankedSearchEntry(
            String sectionRef,
            String sectionTitle,
            Integer pageNo,
            String snippet,
            Map<String, Object> evidence,
            String strategy,
            int priority,
            String query
    ) {
        this.sectionRef = sectionRef;
        this.sectionTitle = sectionTitle;
        this.pageNo = pageNo;
        this.snippet = snippet;
        this.evidence = evidence;
        this.strategy = strategy;
        this.priority = priority;
        this.query = query;
        this.bm25fScore = 0.0;
        this.intentAlignmentScore = 0.0;
        this.contextNoisePenalty = 0.0;
    }

    // Getters
    public String getSectionRef() { return sectionRef; }
    public String getSectionTitle() { return sectionTitle; }
    public Integer getPageNo() { return pageNo; }
    public String getSnippet() { return snippet; }
    public Map<String, Object> getEvidence() { return evidence; }
    public String getStrategy() { return strategy; }
    public int getPriority() { return priority; }
    public String getQuery() { return query; }
    public double getBm25fScore() { return bm25fScore; }
    public double getIntentAlignmentScore() { return intentAlignmentScore; }
    public double getContextNoisePenalty() { return contextNoisePenalty; }

    // Setters for scoring
    public void setBm25fScore(double bm25fScore) { this.bm25fScore = bm25fScore; }
    public void setIntentAlignmentScore(double intentAlignmentScore) { this.intentAlignmentScore = intentAlignmentScore; }
    public void setContextNoisePenalty(double contextNoisePenalty) { this.contextNoisePenalty = contextNoisePenalty; }
}
