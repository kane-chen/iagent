package io.invest.iagent.rag.filing;

import io.invest.iagent.rag.filing.config.FilingKbProperties;
import io.invest.iagent.rag.filing.ingest.FilingMetaLoader;
import io.invest.iagent.rag.filing.ingest.FilingMetaLoader.FilingMeta;
import io.invest.iagent.rag.filing.ingest.PeriodParser;
import io.invest.iagent.rag.filing.model.FilingBuildReport;
import io.invest.iagent.rag.filing.retrieve.FilingTagKeys;
import io.invest.iagent.rag.KnowledgeService;
import io.invest.iagent.rag.chunking.chunker.ChunkStrategy;
import io.invest.iagent.rag.model.ChunkingConfig;
import io.invest.iagent.rag.model.Document;
import io.invest.iagent.utils.WorkspacePaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 财报知识库构造服务：扫描 workspace/portfolio 下的财报文件，
 * 挂接 ticker/period/heading 等标签后写入通用 RAG 知识库。
 * <p>知识库为单一全局库 {@link FilingKbProperties#getKnowledgeBaseId()}（默认 "filing"），
 * ticker 仅作为过滤标签。幂等重建：先按 knowledgeId 删除再写入。
 */
@Service
@Slf4j
public class FilingBuildService {

    private static final List<String> DOC_EXTENSIONS = List.of(".pdf", ".html", ".htm");

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private FilingKbProperties properties;

    @Autowired
    private Path workspace;

    private final FilingMetaLoader metaLoader = new FilingMetaLoader();

    /**
     * 构建单个 ticker 的全部财报文档。
     */
    public FilingBuildReport buildTicker(String ticker, boolean force) {
        String normTicker = normalize(ticker);
        FilingBuildReport report = new FilingBuildReport();
        report.setTicker(normTicker);

        Path filingsDir = WorkspacePaths.filingsDir(workspace, normTicker);
        if (!Files.isDirectory(filingsDir)) {
            report.addError("filings dir not found: " + filingsDir);
            return report;
        }

        List<Path> docDirs = new ArrayList<>();
        try (Stream<Path> s = Files.list(filingsDir)) {
            s.filter(Files::isDirectory).forEach(docDirs::add);
        } catch (IOException e) {
            report.addError("list filings dir failed: " + e.getMessage());
            return report;
        }
        for (Path docDir : docDirs) {
            String documentId = docDir.getFileName().toString();
            try {
                int chunks = buildDocument(normTicker, documentId, force);
                report.incrementDocs();
                report.addChunks(chunks);
            } catch (Exception e) {
                log.warn("Build document failed: ticker={}, doc={}", normTicker, documentId, e);
                report.addError(documentId + ": " + e.getMessage());
            }
        }
        log.info("FilingKB build done: ticker={}, docs={}, chunks={}, errors={}",
                normTicker, report.getDocuments(), report.getChunks(), report.getErrors().size());
        return report;
    }

    /**
     * 构建单个 documentId 目录下的全部文件（PDF/HTML）。
     *
     * @return 写入的 chunk 数量
     */
    public int buildDocument(String ticker, String documentId, boolean force) {
        String normTicker = normalize(ticker);
        Path docDir = WorkspacePaths.filingsDir(workspace, normTicker, documentId);
        if (!Files.isDirectory(docDir)) {
            throw new IllegalArgumentException("document dir not found: " + docDir);
        }
        FilingMeta meta = metaLoader.load(docDir, normTicker, documentId);

        List<Path> files = listDocumentFiles(docDir);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("no pdf/html files under: " + docDir);
        }

        String knowledgeBaseId = properties.getKnowledgeBaseId();
        // 库内全局唯一：ticker + documentId
        String knowledgeId = normTicker + "_" + documentId;

        // 幂等：先删后写（标签随外键级联删除）
        knowledgeService.deleteByKnowledgeId(knowledgeBaseId, knowledgeId);

        int totalChunks = 0;
        for (Path file : files) {
            Map<String, String> tags = buildTags(meta, file.getFileName().toString());
            Document doc = Document.builder()
                    .knowledgeId(knowledgeId)
                    .knowledgeBaseId(knowledgeBaseId)
                    .filePath(file.toAbsolutePath().toString())
                    .language("zh")
                    .tags(tags)
                    .build();
            totalChunks += knowledgeService.save(doc, chunkingConfig());
        }
        return totalChunks;
    }

    private Map<String, String> buildTags(FilingMeta meta, String sourceFile) {
        Map<String, String> tags = new HashMap<>();
        tags.put(FilingTagKeys.TICKER, meta.getTicker());
        tags.put(FilingTagKeys.DOCUMENT_ID, meta.getDocumentId());
        tags.put(FilingTagKeys.SOURCE_FILE, sourceFile);
        if (meta.getFormType() != null && !meta.getFormType().isBlank()) {
            tags.put(FilingTagKeys.FORM_TYPE, meta.getFormType());
        }
        if (meta.getFiscalYear() != null) {
            tags.put(FilingTagKeys.FISCAL_YEAR, String.valueOf(meta.getFiscalYear()));
        }
        // 规范化周期：meta 中可能只有 "Q1" 之类的相对片段，结合 fiscalYear 补全
        String period = canonicalPeriod(meta);
        if (period != null) {
            tags.put(FilingTagKeys.FISCAL_PERIOD, period);
        }
        return tags;
    }

    private String canonicalPeriod(FilingMeta meta) {
        String fp = meta.getFiscalPeriod();
        if (fp == null || fp.isBlank()) return null;
        // 已是规范形式（含 4 位年份）直接解析
        PeriodParser.ParsedPeriod parsed = PeriodParser.parse(fp);
        if (parsed.period() != null) {
            return parsed.period();
        }
        // 形如 "Q1"/"H1"/"FY"，结合 fiscalYear 补全
        if (meta.getFiscalYear() != null) {
            String combined = fp.toUpperCase(Locale.ROOT).startsWith("FY")
                    ? "FY" + meta.getFiscalYear()
                    : meta.getFiscalYear() + fp.toUpperCase(Locale.ROOT);
            PeriodParser.ParsedPeriod p2 = PeriodParser.parse(combined);
            if (p2.period() != null) return p2.period();
        }
        return null;
    }

    private ChunkingConfig chunkingConfig() {
        FilingKbProperties.Chunk chunk = properties.getChunk();
        ChunkingConfig config = new ChunkingConfig();
        config.setStrategy(ChunkStrategy.valueOf(chunk.getStrategy()));
        config.setEnableParentChild(chunk.isParentChild());
        config.setParentChunkSize(chunk.getParentSize());
        config.setChildChunkSize(chunk.getChildSize());
        config.setChunkSize(chunk.getChunkSize());
        config.setChunkOverlap(chunk.getChunkOverlap());
        return config;
    }

    private List<Path> listDocumentFiles(Path docDir) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(docDir)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return DOC_EXTENSIONS.stream().anyMatch(name::endsWith);
                    })
                    .forEach(files::add);
        } catch (IOException e) {
            throw new IllegalStateException("list files failed: " + docDir, e);
        }
        return files;
    }

    private static String normalize(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("ticker is required");
        }
        return ticker.trim().toUpperCase(Locale.ROOT);
    }
}
