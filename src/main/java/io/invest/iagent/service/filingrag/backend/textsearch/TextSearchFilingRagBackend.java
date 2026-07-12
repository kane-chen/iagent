package io.invest.iagent.service.filingrag.backend.textsearch;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.service.filingrag.backend.FilingRagBackend;
import io.invest.iagent.service.filingrag.config.FilingRagConfig;
import io.invest.iagent.service.filingrag.model.FilingChunk;
import io.invest.iagent.service.filingrag.model.FilingQuery;
import io.invest.iagent.service.filingrag.model.FilingQueryResult;
import io.invest.iagent.service.filingrag.util.LlmClient;
import io.invest.iagent.utils.WorkspacePaths;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 基于文本关键词检索的{@link FilingRagBackend}实现。
 * 不依赖向量数据库和embedding计算，通过词典扩展+LLM关键词提取进行初筛，
 * 再由LLM对候选chunks进行语义相关性重排序。
 *
 * 存储：chunks以JSON格式持久化到workspace/portfolio/&lt;TICKER&gt;/processed/&lt;documentId&gt;/chunks.json。
 */
@Slf4j
public class TextSearchFilingRagBackend implements FilingRagBackend {

    private final Path workspace;
    private final TextSearchChunkStore chunkStore;
    private final QueryRewriter queryRewriter;
    private final KeywordScorer keywordScorer;
    private final SemanticReranker reranker;
    private final FilingRagConfig.TextSearch config;

    public TextSearchFilingRagBackend(Path workspace, FilingRagConfig.TextSearch config,
                                      LlmClient llmClient) {
        this.workspace = workspace;
        this.config = config;
        this.chunkStore = new TextSearchChunkStore(workspace);
        this.keywordScorer = new KeywordScorer();
        this.queryRewriter = new QueryRewriter(llmClient, true);
        this.reranker = new SemanticReranker(llmClient, true);
    }

    @Override
    public String name() {
        return "textsearch";
    }

    @Override
    public boolean requiresEmbeddings() {
        return false;
    }

    @Override
    public void healthCheck() {
        try {
            Path processedRoot = WorkspacePaths.companiesDir(workspace);
            if (!Files.isDirectory(processedRoot)) {
                Files.createDirectories(processedRoot);
            }
            if (!Files.isWritable(processedRoot)) {
                throw new RuntimeException("Processed directory not writable: " + processedRoot);
            }
        } catch (IOException e) {
            throw new RuntimeException("TextSearch backend health check failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void upsertDocument(String ticker, String documentId,
                               List<FilingChunk> chunks, List<List<Float>> embeddings) {
        // textsearch后端不使用embeddings，忽略
        try {
            // 先删除旧数据
            chunkStore.deleteChunks(ticker, documentId);
            chunkStore.saveChunks(ticker, documentId, chunks);
            log.info("TextSearch upserted {} chunks for ticker={} document={}", chunks.size(), ticker, documentId);
        } catch (IOException e) {
            throw new RuntimeException("TextSearch upsert failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int delete(String ticker, String documentId) {
        return chunkStore.deleteChunks(ticker, documentId);
    }

    @Override
    public FilingQueryResult search(FilingQuery query, List<Float> queryEmbedding) {
        // queryEmbedding在textsearch中不使用
        long start = System.currentTimeMillis();
        String ticker = StringUtils.upperCase(query.getTicker());

        try {
            // 1. 发现匹配的文档
            List<DocumentMeta> matchedDocs = discoverMatchingDocuments(query);
            if (matchedDocs.isEmpty()) {
                return emptyResult(query, start);
            }

            // 2. 加载所有匹配文档的chunks
            List<FilingChunk> allChunks = new ArrayList<>();
            for (DocumentMeta dm : matchedDocs) {
                List<FilingChunk> docChunks = chunkStore.loadChunks(dm.ticker, dm.documentId);
                // chunk级别二次过滤（年份、formType等）
                for (FilingChunk c : docChunks) {
                    if (passesChunkFilters(c, query)) {
                        allChunks.add(c);
                    }
                }
            }
            if (allChunks.isEmpty()) {
                return emptyResult(query, start);
            }

            // 3. 查询改写 → 关键词
            QueryRewriter.RewriteResult rewrite = queryRewriter.rewrite(query);
            Set<String> keywords = rewrite.keywords();
            log.debug("TextSearch keywords for '{}': {}", query.getQuestion(), keywords);

            // 4. 关键词评分
            int topK = query.getTopK() != null ? query.getTopK() : 5;
            int recallN = Math.max(config.getRerankTopN(), topK * 3);
            List<KeywordScorer.ScoredChunk> topScored = keywordScorer.score(
                    allChunks, keywords, recallN, config.getMinKeywordScore());

            List<FilingChunk> topChunks = topScored.stream()
                    .map(KeywordScorer.ScoredChunk::chunk)
                    .collect(Collectors.toList());

            // 5. LLM语义重排序
            List<FilingChunk> finalChunks;
            if (!topChunks.isEmpty()) {
                finalChunks = reranker.rerank(query.getQuestion(), topChunks);
            } else {
                finalChunks = new ArrayList<>();
            }

            // 6. 截取topK
            if (finalChunks.size() > topK) {
                finalChunks = new ArrayList<>(finalChunks.subList(0, topK));
            }

            return FilingQueryResult.builder()
                    .queryId(UUID.randomUUID().toString())
                    .question(query.getQuestion())
                    .ticker(ticker)
                    .backend(name())
                    .elapsedMs(System.currentTimeMillis() - start)
                    .chunks(finalChunks)
                    .build();

        } catch (Exception e) {
            throw new RuntimeException("TextSearch search failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 文档发现
    // ------------------------------------------------------------------

    /**
     * 从filings目录发现匹配查询条件的文档。
     */
    private List<DocumentMeta> discoverMatchingDocuments(FilingQuery query) throws IOException {
        List<DocumentMeta> result = new ArrayList<>();
        String ticker = StringUtils.upperCase(query.getTicker());
        Path filingsDir = WorkspacePaths.filingsDir(workspace, ticker);
        if (!Files.isDirectory(filingsDir)) {
            return result;
        }

        try (var stream = Files.list(filingsDir)) {
            List<Path> docDirs = stream.filter(Files::isDirectory).toList();
            for (Path docDir : docDirs) {
                String documentId = docDir.getFileName().toString();
                DocumentMeta meta = loadDocumentMeta(docDir, ticker, documentId);
                if (matchesDocumentFilters(meta, query)) {
                    result.add(meta);
                }
            }
        }
        return result;
    }

    /**
     * 加载文档元数据（从meta.json或documentId解析）。
     */
    private DocumentMeta loadDocumentMeta(Path docDir, String ticker, String documentId) {
        Path metaFile = docDir.resolve("meta.json");
        String formType = null;
        Integer fiscalYear = null;
        String fiscalPeriod = null;
        if (Files.exists(metaFile)) {
            try {
                JSONObject json = JSON.parseObject(Files.readString(metaFile));
                formType = json.getString("formType");
                fiscalYear = json.getInteger("fiscalYear");
                fiscalPeriod = json.getString("fiscalPeriod");
                if (StringUtils.isBlank(fiscalPeriod)) {
                    fiscalPeriod = deriveFiscalPeriodFromDocumentId(documentId);
                }
            } catch (Exception e) {
                log.debug("Failed to read meta.json from {}: {}", docDir, e.getMessage());
            }
        }
        if (StringUtils.isBlank(fiscalPeriod)) {
            fiscalPeriod = deriveFiscalPeriodFromDocumentId(documentId);
        }
        return new DocumentMeta(ticker, documentId, formType, fiscalYear, fiscalPeriod);
    }

    private String deriveFiscalPeriodFromDocumentId(String documentId) {
        String upper = StringUtils.upperCase(documentId);
        if (upper.endsWith("_FY")) return "FY";
        if (upper.contains("Q1")) return "Q1";
        if (upper.contains("Q2")) return "Q2";
        if (upper.contains("Q3")) return "Q3";
        if (upper.contains("Q4")) return "Q4";
        if (upper.contains("_H1")) return "H1";
        if (upper.contains("_H2")) return "H2";
        return null;
    }

    // ------------------------------------------------------------------
    // 过滤
    // ------------------------------------------------------------------

    private boolean matchesDocumentFilters(DocumentMeta meta, FilingQuery query) {
        // formType
        if (StringUtils.isNotBlank(query.getFormType())
                && !StringUtils.equalsIgnoreCase(meta.formType, query.getFormType())) {
            return false;
        }
        // fiscalPeriod
        if (StringUtils.isNotBlank(query.getFiscalPeriod())
                && !StringUtils.equalsIgnoreCase(meta.fiscalPeriod, query.getFiscalPeriod())) {
            return false;
        }
        // fiscalYear range
        if (meta.fiscalYear != null) {
            if (query.getFromFiscalYear() != null && meta.fiscalYear < query.getFromFiscalYear()) {
                return false;
            }
            if (query.getToFiscalYear() != null && meta.fiscalYear > query.getToFiscalYear()) {
                return false;
            }
        }
        return true;
    }

    private boolean passesChunkFilters(FilingChunk c, FilingQuery query) {
        // fiscalYear
        if (c.getFiscalYear() != null) {
            if (query.getFromFiscalYear() != null && c.getFiscalYear() < query.getFromFiscalYear()) {
                return false;
            }
            if (query.getToFiscalYear() != null && c.getFiscalYear() > query.getToFiscalYear()) {
                return false;
            }
        }
        // formType
        if (StringUtils.isNotBlank(query.getFormType())
                && !StringUtils.equalsIgnoreCase(c.getFormType(), query.getFormType())) {
            return false;
        }
        // fiscalPeriod
        if (StringUtils.isNotBlank(query.getFiscalPeriod())
                && !StringUtils.equalsIgnoreCase(c.getFiscalPeriod(), query.getFiscalPeriod())) {
            return false;
        }
        return true;
    }

    private FilingQueryResult emptyResult(FilingQuery query, long start) {
        return FilingQueryResult.builder()
                .queryId(UUID.randomUUID().toString())
                .question(query.getQuestion())
                .ticker(StringUtils.upperCase(query.getTicker()))
                .backend(name())
                .elapsedMs(System.currentTimeMillis() - start)
                .chunks(new ArrayList<>())
                .build();
    }

    // ------------------------------------------------------------------
    // 内部数据结构
    // ------------------------------------------------------------------

    record DocumentMeta(String ticker, String documentId, String formType,
                        Integer fiscalYear, String fiscalPeriod) {}
}
