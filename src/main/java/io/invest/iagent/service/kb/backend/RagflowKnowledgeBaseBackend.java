package io.invest.iagent.service.kb.backend;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import io.invest.iagent.config.ApplicationProperties;
import io.invest.iagent.config.ApplicationProperties.RagflowProperties;
import io.invest.iagent.service.kb.model.KnowledgeBaseDocumentDTO;
import io.invest.iagent.utils.WorkspacePaths;
import io.invest.iagent.service.kb.backend.ragflow.RagflowClient;
import io.invest.iagent.service.kb.backend.ragflow.RagflowClientException;
import io.invest.iagent.service.kb.model.KnowledgeBaseChunkDTO;
import io.invest.iagent.service.kb.model.KnowledgeBaseOperationResult;
import io.invest.iagent.service.kb.util.FilingSourceSelector;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于外部 RAGFlow 服务的知识库后端。
 * <p>
 * 与 {@link MilvusKnowledgeBaseBackend} 的关键差异：
 * <ul>
 *   <li>Chunk 与 Embedding 由 RAGFlow 侧完成，本项目不再走 {@code FilingPreprocessService} 的切分/向量化流程</li>
 *   <li>Dataset 按 ticker 隔离：{@code app.ragflow.dataset-prefix + ticker}</li>
 *   <li>每份财报作为一个 document 上传（复用 {@link FilingSourceSelector} 挑选主文件），
 *       并把 form_type、fiscal_year、filing_date、category、source_fingerprint 写入文档 meta_fields，
 *       供检索侧过滤</li>
 *   <li>parse 由用户手动在 RAGFlow Web UI 触发；本 backend 只负责上传 + meta 写入</li>
 * </ul>
 * <p>
 * <b>注意</b>：检索（retrieve）能力已迁移到 {@code workspace/skills/financial-filing-retrieve}
 * Python skill，本类只保留 preprocess / build / list / delete。
 */
@Slf4j
public class RagflowKnowledgeBaseBackend implements KnowledgeBaseBackend {

    private static final String META_TICKER = "ticker";
    private static final String META_DOCUMENT_ID = "document_id";
    private static final String META_FORM_TYPE = "form_type";
    private static final String META_FISCAL_YEAR = "fiscal_year";
    private static final String META_FISCAL_PERIOD = "fiscal_period";
    private static final String META_FILING_DATE = "filing_date";
    private static final String META_SOURCE_FINGERPRINT = "source_fingerprint";

    private final Path workspace;
    private final RagflowClient client;
    private final RagflowProperties properties;
    private final FilingSourceSelector sourceSelector;

    public RagflowKnowledgeBaseBackend(Path workspace, RagflowClient client,
                                       ApplicationProperties applicationProperties) {
        this(workspace, client, applicationProperties, new FilingSourceSelector());
    }

    public RagflowKnowledgeBaseBackend(Path workspace, RagflowClient client,
                                       ApplicationProperties applicationProperties,
                                       FilingSourceSelector sourceSelector) {
        this.workspace = workspace;
        this.client = client;
        this.properties = applicationProperties.getRagflow();
        this.sourceSelector = sourceSelector;
    }

    @Override
    public String name() {
        return "ragflow";
    }

    @Override
    public KnowledgeBaseOperationResult preprocess(String ticker, String documentId, boolean force) {
        // RAGFlow 侧自带 chunk/parse 流程，preprocess 语义在本 backend 中约等于"确认候选文件存在"，
        // 因此这里只做扫描汇报，不做实际上传，避免误消耗后端资源。
        String normalizedTicker = StringUtils.upperCase(ticker);
        try {
            List<Path> filingDirs = listFilingDirs(normalizedTicker, documentId);
            int candidateFiles = 0;
            for (Path dir : filingDirs) {
                candidateFiles += selectFilesFor(dir).size();
            }
            return KnowledgeBaseOperationResult.builder()
                    .success(true)
                    .operation("preprocess")
                    .ticker(normalizedTicker)
                    .documentId(documentId)
                    .knowledgeBaseId(datasetName(normalizedTicker))
                    .chunkCount(candidateFiles)
                    .message("发现 " + candidateFiles + " 个可上传文件，等待构建")
                    .metadata(Map.of("backend", name(), "filing_dirs", filingDirs.size()))
                    .build();
        } catch (Exception e) {
            return error("preprocess", normalizedTicker, documentId, e);
        }
    }

    @Override
    public KnowledgeBaseOperationResult build(String ticker, String documentId, boolean force) {
        String normalizedTicker = StringUtils.upperCase(ticker);
        try {
            String datasetName = datasetName(normalizedTicker);
            String datasetId = client.ensureDataset(datasetName);

            // 现有 dataset 里已经存在的文档，按 name 建索引，用于幂等跳过
            Map<String, JsonNode> existingByName = new HashMap<>();
            for (JsonNode doc : client.listDocuments(datasetId)) {
                existingByName.put(doc.path("name").asText(), doc);
            }

            List<Path> filingDirs = listFilingDirs(normalizedTicker, documentId);
            if (filingDirs.isEmpty()) {
                return KnowledgeBaseOperationResult.builder()
                        .success(true)
                        .operation("build")
                        .ticker(normalizedTicker)
                        .documentId(documentId)
                        .knowledgeBaseId(datasetName)
                        .chunkCount(0)
                        .message("没有找到可处理的财报文件，请先下载财报")
                        .build();
            }

            List<String> uploadedDocIds = new ArrayList<>();
            List<String> processedDocumentIds = new ArrayList<>();
            List<String> errors = new ArrayList<>();

            for (Path filingDir : filingDirs) {
                Path metaFile = filingDir.resolve("meta.json");
                if (!Files.exists(metaFile)) {
                    continue;
                }
                JSONObject meta = JSON.parseObject(Files.readString(metaFile));
                if (Boolean.FALSE.equals(meta.getBoolean("ingest_complete")) || meta.getBooleanValue("is_deleted")) {
                    continue;
                }
                String currentDocumentId = StringUtils.defaultIfBlank(
                        meta.getString("document_id"),
                        StringUtils.defaultIfBlank(meta.getString("documentId"),
                                filingDir.getFileName().toString()));
                Map<String, Object> metaFields = buildMetaFields(normalizedTicker, currentDocumentId, meta);

                for (Path source : selectFilesFor(filingDir)) {
                    String remoteName = filingRemoteName(currentDocumentId, source);
                    JsonNode existing = existingByName.get(remoteName);
                    if (existing != null && !force) {
                        processedDocumentIds.add(currentDocumentId);
                        continue;
                    }
                    if (existing != null) {
                        try {
                            client.deleteDocuments(datasetId, List.of(existing.path("id").asText()));
                        } catch (Exception e) {
                            log.warn("delete existing document failed: {}", e.getMessage());
                        }
                    }
                    try {
                        JsonNode uploaded = client.uploadDocument(datasetId, source);
                        String uploadedId = uploaded.path("id").asText();
                        if (StringUtils.isBlank(uploadedId)) {
                            errors.add("upload returned empty id for " + remoteName);
                            continue;
                        }
                        client.updateDocumentMeta(datasetId, uploadedId, metaFields);
                        uploadedDocIds.add(uploadedId);
                        processedDocumentIds.add(currentDocumentId);
                    } catch (RagflowClientException e) {
                        errors.add(remoteName + ": " + e.getMessage());
                    }
                }
            }

            // parse 阶段由用户在 RAGFlow Web UI 上手动触发，避免本地 embedding 吞吐不稳定导致 build 长时间阻塞。
            // 上传 + meta 写入完成即视为"iagent 侧构建完毕"；retrieve 时若 dataset 尚未完成 parse，会自然返回空结果。
            String message;
            if (uploadedDocIds.isEmpty()) {
                message = "无新增文档，全部已存在（force=false 时跳过重复上传）；如需重新解析，请去 Web UI 上删除后重传或 force=true";
            } else {
                message = "已上传 " + uploadedDocIds.size() + " 份文件到 RAGFlow，"
                        + "请到 Web UI（http://<ragflow-host>/ → 知识库 → " + datasetName + "）手动点击"
                        + "\"解析\"完成 chunk 与 embedding，随后再调用 retrieve";
            }

            return KnowledgeBaseOperationResult.builder()
                    .success(errors.isEmpty())
                    .operation("build")
                    .ticker(normalizedTicker)
                    .documentId(documentId)
                    .knowledgeBaseId(datasetName)
                    .chunkCount(0)
                    .documentIds(processedDocumentIds.stream().distinct().toList())
                    .message(message)
                    .errors(errors.isEmpty() ? null : errors)
                    .metadata(Map.of(
                            "backend", name(),
                            "dataset_id", datasetId,
                            "uploaded", uploadedDocIds.size(),
                            "parse_mode", "manual"))
                    .build();
        } catch (Exception e) {
            return error("build", normalizedTicker, documentId, e);
        }
    }

    @Override
    public KnowledgeBaseOperationResult delete(String ticker, String documentId) {
        String normalizedTicker = StringUtils.upperCase(ticker);
        try {
            String datasetName = datasetName(normalizedTicker);
            JsonNode datasetNode = client.findDatasetByName(datasetName);
            if (datasetNode == null || !datasetNode.hasNonNull("id")) {
                return KnowledgeBaseOperationResult.builder()
                        .success(true)
                        .operation("delete")
                        .ticker(normalizedTicker)
                        .documentId(documentId)
                        .knowledgeBaseId(datasetName)
                        .chunkCount(0)
                        .message("知识库不存在，无需删除")
                        .build();
            }
            String datasetId = datasetNode.get("id").asText();
            List<String> toDelete = new ArrayList<>();
            for (JsonNode doc : client.listDocuments(datasetId)) {
                if (StringUtils.isBlank(documentId)) {
                    toDelete.add(doc.path("id").asText());
                } else {
                    String metaDoc = readMetaField(doc, META_DOCUMENT_ID);
                    if (StringUtils.equals(metaDoc, documentId)
                            || StringUtils.startsWith(doc.path("name").asText(), documentId + "__")) {
                        toDelete.add(doc.path("id").asText());
                    }
                }
            }
            if (!toDelete.isEmpty()) {
                client.deleteDocuments(datasetId, toDelete);
            }
            return KnowledgeBaseOperationResult.builder()
                    .success(true)
                    .operation("delete")
                    .ticker(normalizedTicker)
                    .documentId(documentId)
                    .knowledgeBaseId(datasetName)
                    .chunkCount(toDelete.size())
                    .message("已删除 " + toDelete.size() + " 个文档")
                    .build();
        } catch (Exception e) {
            return error("delete", normalizedTicker, documentId, e);
        }
    }

    @Override
    public List<KnowledgeBaseDocumentDTO> list(String ticker) {
        String normalizedTicker = StringUtils.upperCase(ticker);
        String datasetName = datasetName(normalizedTicker);
        JsonNode datasetNode = client.findDatasetByName(datasetName);
        if (datasetNode == null || !datasetNode.hasNonNull("id")) {
            return List.of();
        }
        String datasetId = datasetNode.get("id").asText();
        Map<String, KnowledgeBaseDocumentDTO> byDocId = new LinkedHashMap<>();
        for (JsonNode doc : client.listDocuments(datasetId)) {
            String docId = StringUtils.defaultIfBlank(
                    readMetaField(doc, META_DOCUMENT_ID),
                    doc.path("name").asText());
            KnowledgeBaseDocumentDTO existing = byDocId.get(docId);
            int chunkCount = doc.path("chunk_count").asInt(0)
                    + (existing == null ? 0 : (existing.getChunkCount() == null ? 0 : existing.getChunkCount()));
            Integer fiscalYear = parseFiscalYear(readMetaField(doc, META_FISCAL_YEAR));
            byDocId.put(docId, KnowledgeBaseDocumentDTO.builder()
                    .ticker(normalizedTicker)
                    .documentId(docId)
                    .formType(readMetaField(doc, META_FORM_TYPE))
                    .fiscalYear(fiscalYear)
                    .fiscalPeriod(readMetaField(doc, META_FISCAL_PERIOD))
                    .filingDate(readMetaField(doc, META_FILING_DATE))
                    .sourceFingerprint(readMetaField(doc, META_SOURCE_FINGERPRINT))
                    .chunkCount(chunkCount)
                    .status(mapDocStatus(doc.path("run").asText()))
                    .build());
        }
        return new ArrayList<>(byDocId.values());
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private List<Path> listFilingDirs(String ticker, String documentId) throws IOException {
        Path filingsDir = WorkspacePaths.filingsDir(workspace, ticker);
        if (!Files.isDirectory(filingsDir)) {
            return List.of();
        }
        List<Path> result = new ArrayList<>();
        try (var stream = Files.list(filingsDir)) {
            for (Path dir : stream.filter(Files::isDirectory).sorted().toList()) {
                if (StringUtils.isNotBlank(documentId)
                        && !StringUtils.equals(documentId, dir.getFileName().toString())) {
                    continue;
                }
                result.add(dir);
            }
        }
        return result;
    }

    private List<Path> selectFilesFor(Path filingDir) throws IOException {
        Path metaFile = filingDir.resolve("meta.json");
        if (!Files.exists(metaFile)) {
            return List.of();
        }
        JSONObject meta = JSON.parseObject(Files.readString(metaFile));
        return sourceSelector.selectSourceFiles(filingDir, meta);
    }

    private Map<String, Object> buildMetaFields(String ticker, String documentId, JSONObject meta) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(META_TICKER, ticker);
        fields.put(META_DOCUMENT_ID, documentId);
        putIfNotBlank(fields, META_FORM_TYPE, meta.getString("form_type"), meta.getString("formType"));
        putIfNotBlank(fields, META_FISCAL_YEAR,
                stringify(meta.getInteger("fiscal_year")),
                stringify(meta.getInteger("fiscalYear")));
        putIfNotBlank(fields, META_FISCAL_PERIOD, meta.getString("fiscal_period"), meta.getString("fiscalPeriod"));
        putIfNotBlank(fields, META_FILING_DATE, meta.getString("filing_date"), meta.getString("filingDate"));
        putIfNotBlank(fields, META_SOURCE_FINGERPRINT, meta.getString("source_fingerprint"));
        return fields;
    }

    private static void putIfNotBlank(Map<String, Object> fields, String key, String... candidates) {
        for (String v : candidates) {
            if (StringUtils.isNotBlank(v)) {
                fields.put(key, v);
                return;
            }
        }
    }

    private static String stringify(Integer v) {
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 文件在 RAGFlow 中的显示名：{@code <documentId>__<原文件名>}，便于列表/去重与 delete 匹配。
     */
    private static String filingRemoteName(String documentId, Path source) {
        return documentId + "__" + source.getFileName().toString();
    }

    private String datasetName(String ticker) {
        return properties.getDatasetPrefix() + ticker;
    }

    private static String mapDocStatus(String run) {
        if ("DONE".equalsIgnoreCase(run)) return "indexed";
        if (StringUtils.startsWithIgnoreCase(run, "FAIL")) return "failed";
        return StringUtils.defaultIfBlank(run, "pending").toLowerCase();
    }

    private static String readMetaField(JsonNode doc, String key) {
        JsonNode meta = doc.get("meta_fields");
        if (meta == null || meta.isNull() || !meta.has(key)) {
            return null;
        }
        JsonNode v = meta.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Integer parseFiscalYear(String v) {
        if (StringUtils.isBlank(v)) return null;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private KnowledgeBaseOperationResult error(String operation, String ticker, String documentId, Exception e) {
        log.error("ragflow {} failed: ticker={} doc={}", operation, ticker, documentId, e);
        return KnowledgeBaseOperationResult.builder()
                .success(false)
                .operation(operation)
                .ticker(StringUtils.upperCase(ticker))
                .documentId(documentId)
                .knowledgeBaseId(datasetName(StringUtils.upperCase(ticker)))
                .chunkCount(0)
                .message(e.getMessage())
                .errors(List.of(e.getClass().getSimpleName() + ": " + e.getMessage()))
                .build();
    }
}
