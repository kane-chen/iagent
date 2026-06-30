package io.invest.iagent.service.kb;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.service.kb.util.FilingSourceSelector;
import io.invest.iagent.service.kb.model.KnowledgeBaseChunkDTO;
import io.invest.iagent.service.filing.util.WorkspacePaths;
import io.invest.iagent.service.kb.category.FilingContentCategory;
import io.invest.iagent.service.kb.model.FilingChunkingContext;
import io.invest.iagent.service.kb.chunk.FilingChunkingStrategy;
import io.invest.iagent.service.kb.chunk.FilingChunkingStrategyFactory;
import io.invest.iagent.service.kb.summary.FilingChunkSummarizationService;
import io.invest.iagent.service.kb.summary.NoopFilingChunkSummarizationService;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FilingPreprocessService {

    private final Path workspace;
    private final FilingSourceSelector sourceSelector;
    private final FilingChunkingStrategy chunkingStrategy;
    private final FilingChunkSummarizationService summarizationService;

    public FilingPreprocessService(Path workspace) {
        this(workspace, new FilingSourceSelector());
    }

    public FilingPreprocessService(Path workspace, FilingSourceSelector sourceSelector) {
        this(workspace, sourceSelector, FilingChunkingStrategyFactory.fromEnv());
    }

    public FilingPreprocessService(Path workspace, FilingSourceSelector sourceSelector, FilingChunkingStrategy chunkingStrategy) {
        this(workspace, sourceSelector, chunkingStrategy, new NoopFilingChunkSummarizationService());
    }

    public FilingPreprocessService(Path workspace, FilingSourceSelector sourceSelector, FilingChunkingStrategy chunkingStrategy,
                                   FilingChunkSummarizationService summarizationService) {
        this.workspace = workspace;
        this.sourceSelector = sourceSelector;
        this.chunkingStrategy = chunkingStrategy;
        this.summarizationService = summarizationService;
    }

    public List<KnowledgeBaseChunkDTO> preprocess(String ticker, boolean force) throws IOException {
        return preprocess(ticker, null, force);
    }

    public List<KnowledgeBaseChunkDTO> preprocess(String ticker, String documentId, boolean force) throws IOException {
        String normalizedTicker = StringUtils.upperCase(ticker);
        Path filingsDir = WorkspacePaths.filingsDir(workspace, normalizedTicker);
        if(!Files.isDirectory(filingsDir)){
            return List.of();
        }
        List<KnowledgeBaseChunkDTO> chunks = new ArrayList<>();
        try(var stream = Files.list(filingsDir)){
            for(Path filingDir : stream.filter(Files::isDirectory).toList()){
                if(StringUtils.isNotBlank(documentId) && !StringUtils.equals(documentId, filingDir.getFileName().toString())){
                    continue;
                }
                chunks.addAll(preprocessFiling(normalizedTicker, filingDir, force));
            }
        }
        return chunks;
    }

    public List<KnowledgeBaseChunkDTO> preprocessFiling(String ticker, Path filingDir, boolean force) throws IOException {
        Path metaFile = filingDir.resolve("meta.json");
        if(!Files.exists(metaFile)){
            return List.of();
        }
        JSONObject meta = JSON.parseObject(Files.readString(metaFile));
        if(Boolean.FALSE.equals(meta.getBoolean("ingest_complete")) || meta.getBooleanValue("is_deleted")){
            return List.of();
        }
        String documentId = StringUtils.defaultIfBlank(meta.getString("document_id"), filingDir.getFileName().toString());
        Path processedDir = WorkspacePaths.processedDir(workspace, ticker, documentId);
        Path chunksFile = processedDir.resolve("kb_chunks.jsonl");
        if(!force && Files.exists(chunksFile)){
            return readChunks(chunksFile);
        }
        List<KnowledgeBaseChunkDTO> chunks = new ArrayList<>();
        for(Path sourceFile : sourceSelector.selectSourceFiles(filingDir, meta)){
            chunks.addAll(preprocessSourceFile(ticker, documentId, sourceFile, meta));
        }
        enrichSummaries(chunks);
        Files.createDirectories(processedDir);
        List<String> lines = chunks.stream().map(JSON::toJSONString).toList();
        Files.write(processedDir.resolve("kb_chunks.jsonl"), lines);
        Map<String,Object> kbMeta = new LinkedHashMap<>();
        kbMeta.put("ticker", ticker);
        kbMeta.put("document_id", documentId);
        kbMeta.put("chunk_count", chunks.size());
        kbMeta.put("source_fingerprint", meta.getString("source_fingerprint"));
        kbMeta.put("processed_at", Instant.now().toString());
        Files.writeString(processedDir.resolve("kb_meta.json"), JSON.toJSONString(kbMeta));
        return chunks;
    }

    private List<KnowledgeBaseChunkDTO> preprocessSourceFile(String ticker, String documentId, Path sourceFile, JSONObject meta) throws IOException {
        String lower = StringUtils.lowerCase(sourceFile.getFileName().toString());
        String text;
        String chunkType;
        if(StringUtils.endsWithAny(lower, ".htm", ".html")){
            text = htmlText(sourceFile);
            chunkType = StringUtils.containsAny(lower, "ex99", "ex-99", "exhibit") ? "exhibit" : "section";
        }else if(StringUtils.endsWith(lower, ".pdf")){
            text = pdfText(sourceFile);
            chunkType = "page";
        }else{
            return List.of();
        }
        FilingChunkingContext context = FilingChunkingContext.builder()
                .ticker(ticker)
                .documentId(documentId)
                .sourceFile(sourceFile)
                .meta(meta)
                .chunkType(chunkType)
                .build();
        return chunkingStrategy.chunk(context, text);
    }

    private String htmlText(Path file) throws IOException {
        Document document = Jsoup.parse(Files.readString(file));
        document.select("script,style,noscript,svg,img").remove();
        for(Element table : document.select("table")){
            table.before("\n[TABLE]\n" + table.text() + "\n[/TABLE]\n");
        }
        return normalize(document.text());
    }

    private String pdfText(Path file) throws IOException {
        try(PDDocument document = Loader.loadPDF(file.toFile())){
            PDFTextStripper stripper = new PDFTextStripper();
            return normalize(stripper.getText(document));
        }
    }

    private List<KnowledgeBaseChunkDTO> readChunks(Path chunksFile) throws IOException {
        List<KnowledgeBaseChunkDTO> chunks = new ArrayList<>();
        for(String line : Files.readAllLines(chunksFile)){
            if(StringUtils.isNotBlank(line)){
                chunks.add(normalizeCategory(JSON.parseObject(line, KnowledgeBaseChunkDTO.class)));
            }
        }
        return chunks;
    }

    private void enrichSummaries(List<KnowledgeBaseChunkDTO> chunks) {
        for(KnowledgeBaseChunkDTO chunk : chunks){
            try{
                summarizationService.summarize(chunk)
                        .map(StringUtils::trimToNull)
                        .ifPresent(summary -> putSummary(chunk, summary));
            }catch (Exception ignored){
                // Summary generation is an optional enrichment; keep preprocessing fail-open.
            }
        }
    }

    private void putSummary(KnowledgeBaseChunkDTO chunk, String summary) {
        Map<String, Object> metadata = chunk.getMetadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(chunk.getMetadata());
        metadata.put("chunk_summary", summary);
        metadata.put("chunk_summary_model", summarizationService.model());
        metadata.put("chunk_summary_generated_at", Instant.now().toString());
        metadata.put("chunk_summary_source_hash", metadata.getOrDefault("text_hash", String.valueOf(chunk.getText().hashCode())));
        chunk.setMetadata(metadata);
    }

    private KnowledgeBaseChunkDTO normalizeCategory(KnowledgeBaseChunkDTO chunk) {
        Map<String, Object> metadata = chunk.getMetadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(chunk.getMetadata());
        Object metadataCategory = metadata.get("content_category");
        String category = StringUtils.firstNonBlank(
                FilingContentCategory.normalizeCode(chunk.getCategory()),
                FilingContentCategory.normalizeCode(metadataCategory == null ? null : String.valueOf(metadataCategory)),
                FilingContentCategory.OTHER.code());
        chunk.setCategory(category);
        metadata.put("content_category", category);
        chunk.setMetadata(metadata);
        return chunk;
    }

    private String normalize(String text){
        return StringUtils.defaultString(text).replace(' ',' ').replaceAll("\\s+", " ").trim();
    }

}
