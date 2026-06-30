package io.invest.iagent.service.kb;

import io.invest.iagent.config.ApplicationProperties;
import io.invest.iagent.service.kb.category.FilingContentCategory;
import io.invest.iagent.service.kb.category.FilingQueryCategoryResolver;
import io.invest.iagent.service.kb.embedding.EmbeddingService;
import io.invest.iagent.service.kb.model.KnowledgeBaseSearchFilter;
import io.invest.iagent.service.kb.search.BM25FScorer;
import io.invest.iagent.service.kb.search.SearchEngine;
import io.invest.iagent.service.kb.search.SearchConstants;
import io.invest.iagent.service.kb.search.model.EvidenceMatch;
import io.invest.iagent.service.kb.search.model.QueryDiagnosis;
import io.invest.iagent.service.kb.search.model.RankedSearchEntry;
import io.invest.iagent.service.kb.search.model.SectionSemanticProfile;
import io.invest.iagent.service.kb.vector.VectorStoreService;
import io.invest.iagent.service.kb.model.KnowledgeBaseChunkDTO;
import io.invest.iagent.model.KnowledgeBaseDocumentDTO;
import io.invest.iagent.service.kb.model.KnowledgeBaseOperationResult;
import io.invest.iagent.model.KnowledgeBaseRetrieveResult;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

public class FilingKnowledgeBaseService {

    private final FilingPreprocessService preprocessService;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final FilingQueryCategoryResolver queryCategoryResolver;
    private final SearchEngine searchEngine;
    private final BM25FScorer bm25FScorer;
    private final ApplicationProperties applicationProperties;

    public FilingKnowledgeBaseService(FilingPreprocessService preprocessService, EmbeddingService embeddingService, VectorStoreService vectorStoreService) {
        this(preprocessService, embeddingService, vectorStoreService, new FilingQueryCategoryResolver(), null);
    }

    public FilingKnowledgeBaseService(FilingPreprocessService preprocessService, EmbeddingService embeddingService,
                                      VectorStoreService vectorStoreService, FilingQueryCategoryResolver queryCategoryResolver,
                                      ApplicationProperties applicationProperties) {
        this.preprocessService = preprocessService;
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
        this.queryCategoryResolver = queryCategoryResolver;
        this.applicationProperties = applicationProperties;
        this.bm25FScorer = new BM25FScorer();
        this.searchEngine = new SearchEngine(bm25FScorer);
    }

    public KnowledgeBaseOperationResult preprocess(String ticker, String documentId, boolean force) {
        try{
            List<KnowledgeBaseChunkDTO> chunks = preprocessService.preprocess(ticker, documentId, force);
            return KnowledgeBaseOperationResult.builder()
                    .success(true)
                    .operation("preprocess")
                    .ticker(StringUtils.upperCase(ticker))
                    .documentId(documentId)
                    .knowledgeBaseId(knowledgeBaseId(ticker))
                    .chunkCount(chunks.size())
                    .documentIds(documentIds(chunks))
                    .message("预处理完成，生成 " + chunks.size() + " 个chunk")
                    .metadata(Map.of("embedding_model", embeddingService.model()))
                    .build();
        }catch (Exception e){
            return error("preprocess", ticker, documentId, e);
        }
    }

    public KnowledgeBaseOperationResult build(String ticker, String documentId, boolean force) {
        try{
            List<KnowledgeBaseChunkDTO> chunks = preprocessService.preprocess(ticker, documentId, force);
            List<List<Float>> embeddings = new ArrayList<>();
            for(KnowledgeBaseChunkDTO chunk : chunks){
                embeddings.add(embeddingService.embed(chunk.getText()));
            }
            upsertInBatches(chunks, embeddings, 512);
            int summaryCandidateCount = upsertSummaryCandidates(chunks);
            return KnowledgeBaseOperationResult.builder()
                    .success(true)
                    .operation("build")
                    .ticker(StringUtils.upperCase(ticker))
                    .documentId(documentId)
                    .knowledgeBaseId(knowledgeBaseId(ticker))
                    .chunkCount(chunks.size())
                    .documentIds(documentIds(chunks))
                    .message("知识库构建完成，写入 " + chunks.size() + " 个chunk")
                    .metadata(Map.of(
                            "embedding_model", embeddingService.model(),
                            "embedding_dimension", embeddingService.dimension(),
                            "summary_candidate_count", summaryCandidateCount,
                            "summary_enabled", summaryCandidateCount > 0))
                    .build();
        }catch (Exception e){
            return error("build", ticker, documentId, e);
        }
    }

    public KnowledgeBaseRetrieveResult retrieve(String query, String ticker, Integer topK, String fiscalYear, String formType) {
        return retrieve(query, ticker, topK, fiscalYear, formType, null);
    }

    public KnowledgeBaseRetrieveResult retrieve(String query, String ticker, Integer topK, String fiscalYear, String formType, String category) {
        return retrieve(query, ticker, topK, fiscalYear, formType, category, false);
    }

    public KnowledgeBaseRetrieveResult retrieve(String query, String ticker, Integer topK, String fiscalYear, String formType,
                                                String category, Boolean useSummaryCandidates) {
        // Use configuration values with fallbacks
        int defaultTopK = applicationProperties != null
                ? applicationProperties.getKb().getResultTopK()
                : 5;
        int limit = Objects.nonNull(topK) && topK > 0 ? topK : defaultTopK;

        int vectorTopKMultiplier = applicationProperties != null
                ? applicationProperties.getKb().getSummaryCandidateMultiplier()
                : 4;
        int defaultVectorTopK = applicationProperties != null
                ? applicationProperties.getKb().getVectorTopK()
                : 50;
        int vectorTopK = Math.max(limit * vectorTopKMultiplier, defaultVectorTopK);

        String normalizedCategory = FilingContentCategory.normalizeCode(category);
        String inferredCategory = StringUtils.isBlank(normalizedCategory) ? queryCategoryResolver.resolve(query) : null;
        String effectiveCategory = StringUtils.firstNonBlank(normalizedCategory, inferredCategory);

        // Step 1: Retrieve candidate chunks from vector store (wider recall)
        List<Float> embedding = embeddingService.embed(query);
        KnowledgeBaseSearchFilter filter = KnowledgeBaseSearchFilter.builder()
                .ticker(ticker)
                .fiscalYear(fiscalYear)
                .formType(formType)
                .category(effectiveCategory)
                .topK(vectorTopK)
                .build();

        List<KnowledgeBaseChunkDTO> vectorCandidates = Boolean.TRUE.equals(useSummaryCandidates)
                ? retrieveWithSummaryCandidates(query, embedding, filter, vectorTopK)
                : vectorStoreService.search(query, embedding, filter);

        if (vectorCandidates.isEmpty()) {
            return KnowledgeBaseRetrieveResult.builder()
                    .query(query)
                    .ticker(StringUtils.upperCase(ticker))
                    .topK(limit)
                    .category(effectiveCategory)
                    .inferredCategory(inferredCategory)
                    .results(List.of())
                    .message("未检索到相关内容")
                    .build();
        }

        // Step 2: Convert candidates to sections for SearchEngine processing
        List<Map<String, Object>> sections = convertChunksToSections(vectorCandidates);

        // Step 3: Build semantic profiles and term frequency
        Map.Entry<Map<String, SectionSemanticProfile>, Map<String, Integer>> profileResult =
                searchEngine.buildSectionSemanticProfiles(sections);
        Map<String, SectionSemanticProfile> semanticProfiles = profileResult.getKey();
        Map<String, Integer> termDf = profileResult.getValue();

        // Step 4: Diagnose query
        QueryDiagnosis diagnosis = searchEngine.diagnoseSearchQuery(query, termDf, sections.size(), SearchConstants.SEARCH_MODE_AUTO);

        // Step 5: Build BM25F index for keyword ranking
        BM25FScorer.BM25FSectionIndex bm25fIndex = bm25FScorer.buildSectionIndex(sections);

        // Step 6: Convert vector candidates to ranked search entries
        List<RankedSearchEntry> rankedEntries = convertChunksToRankedEntries(vectorCandidates, query);

        // Step 7: Apply multi-axis ranking (intent alignment + BM25F + vector score)
        List<RankedSearchEntry> sortedEntries = searchEngine.sortRankedSearchEntries(
                rankedEntries,
                bm25fIndex,
                diagnosis,
                semanticProfiles
        );

        // Step 8: Apply exact priority capping and limit to topK
        List<RankedSearchEntry> cappedEntries = searchEngine.capEntriesWithExactPriority(sortedEntries, limit);

        // Step 9: Build evidence matches and convert back to KnowledgeBaseChunkDTO
        List<EvidenceMatch> evidenceMatches = searchEngine.buildEvidenceMatches(
                cappedEntries.size() > limit ? cappedEntries.subList(0, limit) : cappedEntries,
                formType,
                null
        );

        List<KnowledgeBaseChunkDTO> results = convertEvidenceMatchesToChunks(evidenceMatches, vectorCandidates);

        return KnowledgeBaseRetrieveResult.builder()
                .query(query)
                .ticker(StringUtils.upperCase(ticker))
                .topK(limit)
                .category(effectiveCategory)
                .inferredCategory(inferredCategory)
                .results(results)
                .message(results.isEmpty() ? "未检索到相关内容" : "检索到 " + results.size() + " 条相关内容")
                .metadata(Map.of(
                        "query_intent", diagnosis.intent(),
                        "ambiguity_score", diagnosis.ambiguityScore(),
                        "is_high_ambiguity", diagnosis.isHighAmbiguity(),
                        "vector_candidates", vectorCandidates.size()
                ))
                .build();
    }

    /**
     * Convert KnowledgeBaseChunkDTO list to sections format for SearchEngine.
     */
    private List<Map<String, Object>> convertChunksToSections(List<KnowledgeBaseChunkDTO> chunks) {
        return chunks.stream()
                .map(chunk -> {
                    Map<String, Object> section = new HashMap<>();
                    section.put("ref", chunk.getChunkId() != null ? chunk.getChunkId() : "");
                    section.put("title", chunk.getSectionTitle() != null ? chunk.getSectionTitle() : "");
                    section.put("topic", chunk.getCategory() != null ? chunk.getCategory() : "");
                    section.put("path", chunk.getSectionTitle() != null ? chunk.getSectionTitle() : "");
                    section.put("item", chunk.getCategory() != null ? chunk.getCategory() : "");
                    section.put("preview", chunk.getText() != null ? chunk.getText() : "");
                    return section;
                })
                .collect(Collectors.toList());
    }

    /**
     * Convert KnowledgeBaseChunkDTO to RankedSearchEntry for SearchEngine ranking.
     */
    private List<RankedSearchEntry> convertChunksToRankedEntries(List<KnowledgeBaseChunkDTO> chunks, String query) {
        return chunks.stream()
                .map(chunk -> {
                    // Determine strategy based on vector score threshold
                    String strategy = chunk.getScore() != null && chunk.getScore() > 0.8
                            ? SearchConstants.SEARCH_STRATEGY_EXACT
                            : SearchConstants.SEARCH_STRATEGY_TOKEN;
                    int priority = SearchConstants.SEARCH_STRATEGY_PRIORITY.getOrDefault(strategy, 3);

                    Map<String, Object> evidence = new HashMap<>();
                    evidence.put("context", chunk.getText());
                    evidence.put("matched_text", chunk.getText());
                    evidence.put("vector_score", chunk.getScore());

                    return new RankedSearchEntry(
                            chunk.getChunkId(),
                            chunk.getSectionTitle(),
                            null, // pageNo not available in chunks
                            chunk.getText(),
                            evidence,
                            strategy,
                            priority,
                            query
                    );
                })
                .collect(Collectors.toList());
    }

    /**
     * Convert EvidenceMatch back to KnowledgeBaseChunkDTO with enriched evidence.
     */
    private List<KnowledgeBaseChunkDTO> convertEvidenceMatchesToChunks(
            List<EvidenceMatch> matches,
            List<KnowledgeBaseChunkDTO> originalChunks
    ) {
        Map<String, KnowledgeBaseChunkDTO> chunkMap = originalChunks.stream()
                .filter(c -> c.getChunkId() != null)
                .collect(Collectors.toMap(KnowledgeBaseChunkDTO::getChunkId, c -> c, (a, b) -> a));

        return matches.stream()
                .map(match -> {
                    String sectionRef = match.section().ref();
                    KnowledgeBaseChunkDTO original = chunkMap.get(sectionRef);

                    if (original == null) {
                        // Fallback: create new DTO from match
                        return KnowledgeBaseChunkDTO.builder()
                                .chunkId(sectionRef)
                                .sectionTitle(match.section().title())
                                .text(String.valueOf(match.evidence().get("context")))
                                .category(match.section().topic())
                                .metadata(Map.of(
                                        "matched_query", match.matchedQuery(),
                                        "is_exact_phrase", match.isExactPhrase(),
                                        "matched_text", match.evidence().get("matched_text"),
                                        "section_topic", match.section().topic() != null ? match.section().topic() : ""
                                ))
                                .build();
                    }

                    // Enrich original chunk with search evidence
                    Map<String, Object> enrichedMetadata = new HashMap<>();
                    if (original.getMetadata() != null) {
                        enrichedMetadata.putAll(original.getMetadata());
                    }
                    enrichedMetadata.put("matched_query", match.matchedQuery());
                    enrichedMetadata.put("is_exact_phrase", match.isExactPhrase());
                    enrichedMetadata.put("matched_text", match.evidence().get("matched_text"));
                    enrichedMetadata.put("section_topic", match.section().topic() != null ? match.section().topic() : "");

                    return KnowledgeBaseChunkDTO.builder()
                            .chunkId(original.getChunkId())
                            .score(original.getScore())
                            .text(original.getText())
                            .ticker(original.getTicker())
                            .documentId(original.getDocumentId())
                            .formType(original.getFormType())
                            .fiscalYear(original.getFiscalYear())
                            .fiscalPeriod(original.getFiscalPeriod())
                            .filingDate(original.getFilingDate())
                            .sourceFileName(original.getSourceFileName())
                            .sectionTitle(original.getSectionTitle())
                            .chunkType(original.getChunkType())
                            .category(original.getCategory())
                            .citation(original.getCitation())
                            .metadata(enrichedMetadata)
                            .build();
                })
                .collect(Collectors.toList());
    }

    private int upsertSummaryCandidates(List<KnowledgeBaseChunkDTO> chunks) {
        List<KnowledgeBaseChunkDTO> summaryChunks = chunks.stream()
                .filter(chunk -> StringUtils.isNotBlank(summaryOf(chunk)))
                .toList();
        if(summaryChunks.isEmpty()){
            return 0;
        }
        List<List<Float>> summaryEmbeddings = new ArrayList<>();
        for(KnowledgeBaseChunkDTO chunk : summaryChunks){
            summaryEmbeddings.add(embeddingService.embed(summaryOf(chunk)));
        }
        upsertSummaryCandidatesInBatches(summaryChunks, summaryEmbeddings, 512);
        return summaryChunks.size();
    }

    private void upsertInBatches(List<KnowledgeBaseChunkDTO> chunks, List<List<Float>> embeddings, int batchSize) {
        int totalSize = chunks.size();
        for (int i = 0; i < totalSize; i += batchSize) {
            int end = Math.min(i + batchSize, totalSize);
            List<KnowledgeBaseChunkDTO> chunkBatch = chunks.subList(i, end);
            List<List<Float>> embeddingBatch = embeddings.subList(i, end);
            vectorStoreService.upsert(chunkBatch, embeddingBatch);
        }
    }

    private void upsertSummaryCandidatesInBatches(List<KnowledgeBaseChunkDTO> chunks, List<List<Float>> embeddings, int batchSize) {
        int totalSize = chunks.size();
        for (int i = 0; i < totalSize; i += batchSize) {
            int end = Math.min(i + batchSize, totalSize);
            List<KnowledgeBaseChunkDTO> chunkBatch = chunks.subList(i, end);
            List<List<Float>> embeddingBatch = embeddings.subList(i, end);
            vectorStoreService.upsertSummaryCandidates(chunkBatch, embeddingBatch);
        }
    }

    private List<KnowledgeBaseChunkDTO> retrieveWithSummaryCandidates(String query, List<Float> embedding, KnowledgeBaseSearchFilter filter, int limit) {
        int candidateTopK = Integer.parseInt(StringUtils.defaultIfBlank(System.getenv("KB_SUMMARY_CANDIDATE_TOP_K"), String.valueOf(Math.max(limit * 4, 20))));
        KnowledgeBaseSearchFilter summaryFilter = KnowledgeBaseSearchFilter.builder()
                .ticker(filter.getTicker())
                .fiscalYear(filter.getFiscalYear())
                .formType(filter.getFormType())
                .category(filter.getCategory())
                .topK(candidateTopK)
                .build();
        List<KnowledgeBaseChunkDTO> candidates = vectorStoreService.searchSummaryCandidates(query, embedding, summaryFilter);
        if(candidates.isEmpty()){
            return vectorStoreService.search(query, embedding, filter);
        }
        Map<String, Double> summaryScores = candidates.stream()
                .filter(candidate -> StringUtils.isNotBlank(candidate.getChunkId()))
                .collect(Collectors.toMap(KnowledgeBaseChunkDTO::getChunkId, KnowledgeBaseChunkDTO::getScore, (left, right) -> left, LinkedHashMap::new));
        KnowledgeBaseSearchFilter detailFilter = KnowledgeBaseSearchFilter.builder()
                .ticker(filter.getTicker())
                .fiscalYear(filter.getFiscalYear())
                .formType(filter.getFormType())
                .category(filter.getCategory())
                .chunkIds(new ArrayList<>(summaryScores.keySet()))
                .topK(limit)
                .build();
        List<KnowledgeBaseChunkDTO> details = vectorStoreService.search(query, embedding, detailFilter);
        details.forEach(chunk -> attachSummaryScore(chunk, summaryScores.get(chunk.getChunkId())));
        return details.isEmpty() ? vectorStoreService.search(query, embedding, filter) : details;
    }

    private void attachSummaryScore(KnowledgeBaseChunkDTO chunk, Double score) {
        if(score == null){
            return;
        }
        Map<String, Object> metadata = chunk.getMetadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(chunk.getMetadata());
        metadata.put("summary_candidate_score", score);
        chunk.setMetadata(metadata);
    }

    private String summaryOf(KnowledgeBaseChunkDTO chunk) {
        Object summary = chunk.getMetadata() == null ? null : chunk.getMetadata().get("chunk_summary");
        return summary == null ? null : String.valueOf(summary);
    }

    public KnowledgeBaseOperationResult delete(String ticker, String documentId) {
        int deleted = vectorStoreService.delete(StringUtils.upperCase(ticker), documentId);
        return KnowledgeBaseOperationResult.builder()
                .success(true)
                .operation("delete")
                .ticker(StringUtils.upperCase(ticker))
                .documentId(documentId)
                .knowledgeBaseId(knowledgeBaseId(ticker))
                .chunkCount(deleted)
                .message("已删除 " + deleted + " 个chunk")
                .build();
    }

    public List<KnowledgeBaseDocumentDTO> list(String ticker) {
        Map<String, List<KnowledgeBaseChunkDTO>> byDocument = vectorStoreService.list(StringUtils.upperCase(ticker)).stream()
                .collect(Collectors.groupingBy(KnowledgeBaseChunkDTO::getDocumentId, LinkedHashMap::new, Collectors.toList()));
        List<KnowledgeBaseDocumentDTO> documents = new ArrayList<>();
        byDocument.forEach((documentId, chunks)->{
            KnowledgeBaseChunkDTO first = chunks.get(0);
            documents.add(KnowledgeBaseDocumentDTO.builder()
                    .ticker(first.getTicker())
                    .documentId(documentId)
                    .formType(first.getFormType())
                    .fiscalYear(first.getFiscalYear())
                    .fiscalPeriod(first.getFiscalPeriod())
                    .filingDate(first.getFilingDate())
                    .sourceFingerprint(String.valueOf(first.getMetadata().get("source_fingerprint")))
                    .chunkCount(chunks.size())
                    .status("indexed")
                    .build());
        });
        return documents;
    }

    public KnowledgeBaseOperationResult sync(String ticker, boolean force) {
        return build(ticker, null, force);
    }

    private KnowledgeBaseOperationResult error(String operation, String ticker, String documentId, Exception e){
        return KnowledgeBaseOperationResult.builder()
                .success(false)
                .operation(operation)
                .ticker(StringUtils.upperCase(ticker))
                .documentId(documentId)
                .knowledgeBaseId(knowledgeBaseId(ticker))
                .chunkCount(0)
                .message(e.getMessage())
                .errors(List.of(e.getClass().getSimpleName() + ": " + e.getMessage()))
                .build();
    }

    private List<String> documentIds(List<KnowledgeBaseChunkDTO> chunks){
        return chunks.stream().map(KnowledgeBaseChunkDTO::getDocumentId).distinct().toList();
    }

    private String knowledgeBaseId(String ticker){
        return "filing_kb_" + StringUtils.upperCase(ticker);
    }
}
