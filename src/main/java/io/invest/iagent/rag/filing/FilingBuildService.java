package io.invest.iagent.rag.filing;

import io.invest.iagent.rag.filing.config.FilingKbProperties;
import io.invest.iagent.rag.filing.ingest.FilingMetaLoader;
import io.invest.iagent.rag.filing.ingest.FilingMetaLoader.FilingMeta;
import io.invest.iagent.rag.filing.ingest.PeriodParser;
import io.invest.iagent.rag.filing.model.FilingBuildReport;
import io.invest.iagent.rag.filing.model.FiscalPeriod;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
        return buildTicker(ticker, force, null, null, null);
    }

    /**
     * 构建单个 ticker 在指定财报周期范围内、且属于指定表单类型的财报文档。
     * <p>周期比较基于规范化 fiscalPeriod（YYYYQn / YYYYHn / FYyyyy），按
     * {@link FiscalPeriod#sortKey()} 排序后做闭区间过滤；无法解析周期的文档会被跳过。
     *
     * @param ticker    股票代码
     * @param force     是否强制重建
     * @param fromPeriod 起始周期（含），可空，如 "2025Q1"、"FY2024"
     * @param toPeriod   结束周期（含），可空，如 "2026Q2"
     * @param formTypes  表单类型白名单（如 FY/Q1/Q2/H1），可空表示不限制
     */
    public FilingBuildReport buildTicker(String ticker, boolean force,
                                         String fromPeriod, String toPeriod,
                                         Collection<String> formTypes) {
        String normTicker = normalize(ticker);
        FilingBuildReport report = new FilingBuildReport();
        report.setTicker(normTicker);

        Path filingsDir = WorkspacePaths.filingsDir(workspace, normTicker);
        if (!Files.isDirectory(filingsDir)) {
            report.addError("filings dir not found: " + filingsDir);
            return report;
        }

        FiscalPeriod from = parsePeriodOrNull(fromPeriod);
        FiscalPeriod to = parsePeriodOrNull(toPeriod);
        Set<String> typeSet = normalizeFormTypes(formTypes);

        List<Path> docDirs = new ArrayList<>();
        try (Stream<Path> s = Files.list(filingsDir)) {
            s.filter(Files::isDirectory).forEach(docDirs::add);
        } catch (IOException e) {
            report.addError("list filings dir failed: " + e.getMessage());
            return report;
        }
        for (Path docDir : docDirs) {
            String documentId = docDir.getFileName().toString();
            // 预读 meta 做周期/类型过滤，避免无谓的切分入库
            FilingMeta preview = metaLoader.load(docDir, normTicker, documentId);
            if (!matchesRange(preview, from, to, typeSet)) {
                log.debug("Skip filing out of range/type: ticker={}, doc={}, period={}, formType={}",
                        normTicker, documentId, preview.getFiscalPeriod(), preview.getFormType());
                continue;
            }
            try {
                int chunks = buildDocument(normTicker, documentId, force);
                report.incrementDocs();
                report.addChunks(chunks);
            } catch (Exception e) {
                log.warn("Build document failed: ticker={}, doc={}", normTicker, documentId, e);
                report.addError(documentId + ": " + e.getMessage());
            }
        }
        log.info("FilingKB build done: ticker={}, docs={}, chunks={}, errors={}, from={}, to={}, formTypes={}",
                normTicker, report.getDocuments(), report.getChunks(), report.getErrors().size(),
                fromPeriod, toPeriod, typeSet);
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

        List<Path> files = listDocumentFiles(docDir, meta);
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
        if (fp != null && !fp.isBlank()) {
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
        }
        // 美股等来源的 meta.json 可能没有 fiscalPeriod，仅有 formType+fiscalYear，按其推导
        if (meta.getFiscalYear() != null && meta.getFormType() != null && !meta.getFormType().isBlank()) {
            String ft = meta.getFormType().trim().toUpperCase(Locale.ROOT);
            if (ft.equals("FY")) return "FY" + meta.getFiscalYear();
            if (ft.matches("Q[1-4]") || ft.matches("H[12]")) {
                return meta.getFiscalYear() + ft;
            }
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

    /**
     * 列出 document 目录下需要切分入库的财报正文文件。
     * <p>优先级：
     * <ol>
     *   <li>meta.json 的 {@code primaryFile.name} 指向的文件（美股 20-F/6-K 目录用它定位
     *       {@code baba-yyyymmdd.htm} / {@code *_ex99-1.htm} 正文，跳过 SEC index 等附属页）；</li>
     *   <li>否则扫描目录下所有 pdf/html，并排除 {@code *-index.html}、{@code *-index-headers.html}
     *       这类 SEC EDGAR 索引导航页。</li>
     * </ol>
     */
    private List<Path> listDocumentFiles(Path docDir, FilingMeta meta) {
        // 1. 优先使用 primaryFile 声明的正文文件
        String primary = meta == null ? null : meta.getPrimaryFileName();
        if (primary != null && !primary.isBlank()) {
            Path p = docDir.resolve(primary);
            if (Files.isRegularFile(p) && hasDocExtension(primary)) {
                return List.of(p);
            }
            log.warn("primaryFile declared but not found or not a doc: {} under {}", primary, docDir);
        }

        // 2. 回退：扫描目录，剔除 SEC EDGAR 索引页
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(docDir)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return hasDocExtension(name) && !isSecIndexFile(name);
                    })
                    .forEach(files::add);
        } catch (IOException e) {
            throw new IllegalStateException("list files failed: " + docDir, e);
        }
        return files;
    }

    private static boolean hasDocExtension(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return DOC_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    /** SEC EDGAR 目录下的索引导航页（非财报正文），如 0000xxxxxx-index.html / -index-headers.html。 */
    private static boolean isSecIndexFile(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith("-index.html") || lower.endsWith("-index-headers.html");
    }

    /**
     * 判断文档是否落在给定周期区间且表单类型匹配。
     * <p>meta 未带可解析周期时：若调用方限定了区间则跳过，否则按类型判断保留。
     */
    private boolean matchesRange(FilingMeta meta, FiscalPeriod from, FiscalPeriod to, Set<String> formTypes) {
        if (formTypes != null && !formTypes.isEmpty()) {
            String ft = meta.getFormType();
            if (ft == null || !formTypes.contains(ft.toUpperCase(Locale.ROOT))) {
                return false;
            }
        }
        if (from == null && to == null) {
            return true;
        }
        // 用与入库标签一致的规范化逻辑推导周期（兼容仅含 formType+fiscalYear 的美股 meta）
        FiscalPeriod p = PeriodParser.parseFiscal(canonicalPeriod(meta));
        if (p == null) {
            // 无法判断周期，保守跳过以严格遵守时间区间
            log.debug("Cannot parse fiscalPeriod for doc={}, value={}", meta.getDocumentId(), meta.getFiscalPeriod());
            return false;
        }
        if (from != null && p.compareTo(from) < 0) return false;
        if (to != null && p.compareTo(to) > 0) return false;
        return true;
    }

    private static FiscalPeriod parsePeriodOrNull(String text) {
        if (text == null || text.isBlank()) return null;
        FiscalPeriod p = PeriodParser.parseFiscal(text);
        if (p == null) {
            throw new IllegalArgumentException("unrecognized fiscal period: " + text
                    + " (expect forms like 2025Q1 / 2025H1 / FY2025)");
        }
        return p;
    }

    private static Set<String> normalizeFormTypes(Collection<String> formTypes) {
        if (formTypes == null || formTypes.isEmpty()) return null;
        Set<String> result = new HashSet<>();
        for (String ft : formTypes) {
            if (ft != null && !ft.isBlank()) {
                result.add(ft.trim().toUpperCase(Locale.ROOT));
            }
        }
        return result.isEmpty() ? null : result;
    }

    private static String normalize(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("ticker is required");
        }
        return ticker.trim().toUpperCase(Locale.ROOT);
    }
}
