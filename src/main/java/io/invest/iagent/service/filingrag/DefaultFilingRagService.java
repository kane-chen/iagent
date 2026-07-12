package io.invest.iagent.service.filingrag;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.service.filingrag.answer.AnswerSynthesizer;
import io.invest.iagent.service.filingrag.backend.FilingRagBackend;
import io.invest.iagent.service.filingrag.chunker.FilingChunker;
import io.invest.iagent.service.filingrag.chunker.FilingTextExtractor;
import io.invest.iagent.service.filingrag.chunker.RawSectionVO;
import io.invest.iagent.service.filingrag.config.FilingRagConfig;
import io.invest.iagent.service.filingrag.embed.EmbeddingProvider;
import io.invest.iagent.service.filingrag.model.FilingAnswer;
import io.invest.iagent.service.filingrag.model.FilingBuildReport;
import io.invest.iagent.service.filingrag.model.FilingChunk;
import io.invest.iagent.service.filingrag.model.FilingDocumentMeta;
import io.invest.iagent.service.filingrag.model.FilingQuery;
import io.invest.iagent.service.filingrag.model.FilingQueryResult;
import io.invest.iagent.utils.WorkspacePaths;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation of {@link FilingRagService}. Orchestrates extraction → chunking → embedding → backend upsert,
 * and embedding → backend search → (optional) answer synthesis.
 */
@Slf4j
public class DefaultFilingRagService implements FilingRagService {

    private final Path workspace;
    private final FilingRagConfig config;
    private final FilingRagBackend backend;
    private final EmbeddingProvider embeddingProvider;
    private final AnswerSynthesizer answerSynthesizer;
    private final FilingChunker chunker;
    private final List<FilingTextExtractor> extractors;

    public DefaultFilingRagService(Path workspace, FilingRagConfig config,
                                   FilingRagBackend backend, EmbeddingProvider embeddingProvider,
                                   AnswerSynthesizer answerSynthesizer, FilingChunker chunker,
                                   List<FilingTextExtractor> extractors) {
        this.workspace = workspace;
        this.config = config;
        this.backend = backend;
        this.embeddingProvider = embeddingProvider;
        this.answerSynthesizer = answerSynthesizer;
        this.chunker = chunker;
        this.extractors = extractors;
    }

    @Override
    public FilingBuildReport buildIndex(String ticker, boolean force) {
        long start = System.currentTimeMillis();
        FilingBuildReport report = FilingBuildReport.builder()
                .ticker(StringUtils.upperCase(ticker))
                .backend(backend.name())
                .errors(new ArrayList<>())
                .build();
        try {
            // Load filings directory
            Path filingsDir = WorkspacePaths.filingsDir(workspace, ticker);
            if (!Files.isDirectory(filingsDir)) {
                report.getErrors().add("No filings directory for " + ticker + ": " + filingsDir);
                report.setElapsedMs(System.currentTimeMillis() - start);
                return report;
            }
            // Iterate over filings
            try (var stream = Files.list(filingsDir)) {
                List<Path> docDirs = stream.filter(Files::isDirectory).toList();
                for (Path docDir : docDirs) {
                    // build one document
                    String documentId = docDir.getFileName().toString();
                    try {
                        int n = buildOneDocument(ticker, documentId, force);
                        report.setDocumentsProcessed(report.getDocumentsProcessed() + 1);
                        report.setChunksIndexed(report.getChunksIndexed() + n);
                    } catch (Exception e) {
                        log.warn("Failed to build document {} for ticker={}: {}", documentId, ticker, e.getMessage());
                        report.getErrors().add(documentId + ": " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            report.getErrors().add("buildIndex failed: " + e.getMessage());
        }
        report.setElapsedMs(System.currentTimeMillis() - start);
        return report;
    }

    @Override
    public FilingBuildReport buildDocument(String ticker, String documentId, boolean force) {
        long start = System.currentTimeMillis();
        FilingBuildReport report = FilingBuildReport.builder()
                .ticker(StringUtils.upperCase(ticker))
                .backend(backend.name())
                .errors(new ArrayList<>())
                .build();
        try {
            int n = buildOneDocument(ticker, documentId, force);
            report.setDocumentsProcessed(1);
            report.setChunksIndexed(n);
        } catch (Exception e) {
            report.getErrors().add(documentId + ": " + e.getMessage());
        }
        report.setElapsedMs(System.currentTimeMillis() - start);
        return report;
    }

    private int buildOneDocument(String ticker, String documentId, boolean force) throws Exception {
        // document check
        String normTicker = StringUtils.upperCase(ticker);
        Path docDir = WorkspacePaths.filingsDir(workspace, normTicker, documentId);
        if (!Files.isDirectory(docDir)) {
            throw new IOException("Document directory does not exist: " + docDir);
        }
        FilingDocumentMeta meta = loadMeta(docDir, normTicker, documentId);

        // If force=false we could check if already indexed; for simplicity always index when called.
        List<Path> files = listDocumentFiles(docDir);
        if (files.isEmpty()) {
            throw new IOException("No files found in " + docDir);
        }
        // chunk
        List<FilingChunk> allChunks = new ArrayList<>();
        for (Path file : files) {
            // route
            FilingTextExtractor extractor = findExtractor(file);
            if (extractor == null) {
                log.debug("No extractor for file {}, skipping", file.getFileName());
                continue;
            }
            // extract
            List<RawSectionVO> sections = extractor.extract(file);
            String sourceFileName = file.getFileName().toString();
            // chunk
            List<FilingChunk> chunks = chunker.chunk(meta, sourceFileName, sections);
            allChunks.addAll(chunks);
        }
        if (allChunks.isEmpty()) {
            log.warn("No chunks extracted from document {} (ticker={})", documentId, normTicker);
            return 0;
        }
        // Embed in batches（如果后端需要embedding）
        List<List<Float>> embeddings;
        if (backend.requiresEmbeddings()) {
            embeddings = new ArrayList<>(allChunks.size());
            int batchSize = 16;
            for (int i = 0; i < allChunks.size(); i += batchSize) {
                int end = Math.min(i + batchSize, allChunks.size());
                List<String> texts = new ArrayList<>(end - i);
                for (int j = i; j < end; j++) {
                    texts.add(allChunks.get(j).getContent());
                }
                List<List<Float>> batch = embeddingProvider.embedBatch(texts);
                embeddings.addAll(batch);
            }
        } else {
            embeddings = List.of();
        }
        backend.upsertDocument(normTicker, documentId, allChunks, embeddings);
        return allChunks.size();
    }

    @Override
    public int delete(String ticker, String documentId) {
        return backend.delete(StringUtils.upperCase(ticker), documentId);
    }

    @Override
    public FilingQueryResult search(FilingQuery query) {
        long start = System.currentTimeMillis();
        FilingQuery normalized = applyDefaults(query);
        String q = normalized.getQuestion();
        List<Float> qEmbedding = backend.requiresEmbeddings() ? embeddingProvider.embed(q) : List.of();
        FilingQueryResult result = backend.search(normalized, qEmbedding);
        // Defaults if backend doesn't fill these
        if (result.getQuestion() == null){
            result.setQuestion(q);
        }
        if (result.getTicker() == null){
            result.setTicker(normalized.getTicker());
        }
        result.setElapsedMs(System.currentTimeMillis() - start);
        return result;
    }

    @Override
    public FilingAnswer answer(FilingQuery query) {
        FilingQueryResult res = search(query);
        if (res.getChunks() == null || res.getChunks().isEmpty()) {
            long start = System.currentTimeMillis();
            return FilingAnswer.builder()
                    .queryId(res.getQueryId())
                    .question(query.getQuestion())
                    .answer("在提供的财报片段中未找到相关信息。")
                    .backend(backend.name())
                    .model(config.getLlm().getModel())
                    .citations(List.of())
                    .elapsedMs(System.currentTimeMillis() - start)
                    .build();
        }
        return answerSynthesizer.answer(query.getQuestion(), res.getChunks(), backend.name());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private FilingQuery applyDefaults(FilingQuery q) {
        int topK = q.getTopK() == null ? config.getSearch().getTopK() : q.getTopK();
        double threshold = q.getSimilarityThreshold() == null
                ? config.getSearch().getSimilarityThreshold() : q.getSimilarityThreshold();
        return FilingQuery.builder()
                .question(q.getQuestion())
                .ticker(StringUtils.upperCase(q.getTicker()))
                .fiscalPeriod(q.getFiscalPeriod())
                .formType(q.getFormType())
                .keyword(q.getKeyword())
                .fromFiscalYear(q.getFromFiscalYear())
                .toFiscalYear(q.getToFiscalYear())
                .fromDate(q.getFromDate())
                .toDate(q.getToDate())
                .topK(topK)
                .similarityThreshold(threshold)
                .build();
    }

    private FilingDocumentMeta loadMeta(Path docDir, String ticker, String documentId) throws IOException {
        Path metaFile = docDir.resolve("meta.json");
        FilingDocumentMeta.FilingDocumentMetaBuilder b = FilingDocumentMeta.builder()
                .ticker(ticker)
                .documentId(documentId);
        if (Files.exists(metaFile)) {
            JSONObject json = JSON.parseObject(Files.readString(metaFile));
            b.formType(json.getString("formType"));
            b.fiscalYear(json.getInteger("fiscalYear"));
            // fiscalPeriod may be embedded in documentId if not in meta (e.g., fil_hk_00700_2022_FY → "FY")
            String fp = json.getString("fiscalPeriod");
            if (StringUtils.isBlank(fp)) {
                fp = deriveFiscalPeriodFromDocumentId(documentId);
            }
            b.fiscalPeriod(fp);
            b.filingDate(json.getString("filingDate") != null ? json.getString("filingDate") : json.getString("reportDate"));
        } else {
            b.fiscalPeriod(deriveFiscalPeriodFromDocumentId(documentId));
        }
        return b.build();
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

    private List<Path> listDocumentFiles(Path docDir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(docDir)) {
            stream.filter(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return !name.equals("meta.json") && Files.isRegularFile(p)
                        && (name.endsWith(".pdf") || name.endsWith(".html") || name.endsWith(".htm"));
            }).forEach(files::add);
        }
        return files;
    }

    private FilingTextExtractor findExtractor(Path file) throws IOException {
        String contentType = Files.probeContentType(file);
        for (FilingTextExtractor ext : extractors) {
            if (ext.supports(contentType, file)) {
                return ext;
            }
        }
        return null;
    }
}
