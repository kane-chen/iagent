package io.invest.iagent.service.filing.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.invest.iagent.model.FinancialFilingDownloadResult;
import io.invest.iagent.service.filing.model.DownloadedFile;
import io.invest.iagent.service.filing.model.DownloadedFiling;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

public class FilingDownloadSupport {

    private static final Logger logger = LoggerFactory.getLogger(FilingDownloadSupport.class);

    protected final Path workspaceDir;
    protected final ObjectMapper objectMapper;

    protected FilingDownloadSupport(Path workspaceDir, ObjectMapper objectMapper) {
        this.workspaceDir = workspaceDir;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建 US filing 的 meta.json
     */
    protected ObjectNode createFilingMetaJson(String documentId, String accessionNumber, String ticker,
                                              String companyId, String formType, int fiscalYear,
                                              String reportDate, String filingDate, String fingerprint,
                                              List<DownloadedFile> files) {
        return createFilingMetaJson(documentId, accessionNumber, ticker, companyId, formType, fiscalYear,
                reportDate, filingDate, fingerprint, files, null);
    }

    protected ObjectNode createFilingMetaJson(String documentId, String accessionNumber, String ticker,
                                              String companyId, String formType, int fiscalYear,
                                              String reportDate, String filingDate, String fingerprint,
                                              List<DownloadedFile> files, String primaryDocumentOverride) {
        ObjectNode meta = objectMapper.createObjectNode();
        String now = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        meta.put("document_id", documentId);
        meta.put("internal_document_id", accessionNumber);
        meta.put("accession_number", accessionNumber);
        meta.put("ingest_method", "download");
        meta.put("ticker", ticker);
        meta.put("company_id", companyId);
        meta.put("form_type", formType);
        meta.put("fiscal_year", fiscalYear);
        meta.putNull("fiscal_period");
        meta.putNull("report_kind");
        meta.put("report_date", reportDate);
        meta.put("filing_date", filingDate);
        meta.put("first_ingested_at", now);
        meta.put("ingest_complete", true);
        meta.put("is_deleted", false);
        meta.putNull("deleted_at");
        meta.put("document_version", "v1");
        meta.put("source_fingerprint", fingerprint);
        meta.put("amended", false);
        meta.put("download_version", "sec_pipeline_download_v1.2.0");
        meta.put("created_at", now);
        meta.put("updated_at", now);
        meta.put("has_xbrl", files.stream().anyMatch(f -> f.getName().endsWith(".xml")));
        meta.put("primary_document", primaryDocumentOverride != null && !primaryDocumentOverride.isBlank()
                ? primaryDocumentOverride : files.isEmpty() ? "" : files.get(0).getName());

        ArrayNode filesArray = objectMapper.createArrayNode();
        for (DownloadedFile file : files) {
            filesArray.add(fileToNode(file));
        }
        meta.set("files", filesArray);

        return meta;
    }

    /**
     * 创建 CN filing 的 meta.json
     */
    protected ObjectNode createCnFilingMetaJson(String documentId, String ticker, String secCode,
                                                String reportType, int fiscalYear,
                                                String reportDate, String filingDate, String fingerprint,
                                                DownloadedFile file) {
        ObjectNode meta = objectMapper.createObjectNode();
        String now = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        meta.put("document_id", documentId);
        meta.put("internal_document_id", documentId.replace("fil_cn_", ""));
        meta.put("accession_number", documentId.replace("fil_cn_", ""));
        meta.put("ingest_method", "download");
        meta.put("ticker", ticker);
        meta.put("company_id", secCode);
        meta.put("form_type", reportType);
        meta.put("fiscal_year", fiscalYear);
        meta.putNull("fiscal_period");
        meta.putNull("report_kind");
        meta.put("report_date", reportDate);
        meta.put("filing_date", filingDate);
        meta.put("first_ingested_at", now);
        meta.put("ingest_complete", true);
        meta.put("is_deleted", false);
        meta.putNull("deleted_at");
        meta.put("document_version", "v1");
        meta.put("source_fingerprint", fingerprint);
        meta.put("amended", false);
        meta.put("download_version", "cninfo_pipeline_download_v1.1.0");
        meta.put("created_at", now);
        meta.put("updated_at", now);
        meta.put("has_xbrl", false);
        meta.put("primary_document", file.getName());

        ArrayNode filesArray = objectMapper.createArrayNode();
        filesArray.add(fileToNode(file));
        meta.set("files", filesArray);

        return meta;
    }

    private ObjectNode fileToNode(DownloadedFile file) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", file.getName());
        node.put("uri", file.getUri());
        node.put("sha256", file.getSha256());
        node.put("size", file.getSize());
        node.put("source_url", file.getSourceUrl());
        node.put("content_type", file.getContentType());
        node.put("ingested_at", file.getIngestedAt());
        return node;
    }

    /**
     * 更新 filing_manifest.json（按 document_id upsert，保证幂等）
     */
    protected void updateFilingManifest(String ticker, List<DownloadedFiling> newFilings) throws IOException {
        Path manifestPath = WorkspacePaths.filingsDir(workspaceDir, ticker).resolve("filing_manifest.json");
        String now = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        ObjectNode manifest;
        ArrayNode documents;

        // 读取现有 manifest 或创建新的
        if (Files.exists(manifestPath)) {
            String existingContent = Files.readString(manifestPath);
            manifest = (ObjectNode) objectMapper.readTree(existingContent);
            JsonNode docsNode = manifest.get("documents");
            documents = (docsNode instanceof ArrayNode) ? (ArrayNode) docsNode : objectMapper.createArrayNode();
        } else {
            manifest = objectMapper.createObjectNode();
            manifest.put("ticker", ticker);
            manifest.put("created_at", now);
            documents = objectMapper.createArrayNode();
            manifest.set("documents", documents);
        }

        // 构建现有 document_id 索引（用于 upsert）
        Map<String, ObjectNode> existingDocs = new LinkedHashMap<>();
        for (int i = 0; i < documents.size(); i++) {
            JsonNode doc = documents.get(i);
            String docId = doc.has("document_id") ? doc.get("document_id").asText() : "";
            if (!docId.isEmpty() && doc instanceof ObjectNode) {
                existingDocs.put(docId, (ObjectNode) doc);
            }
        }

        // upsert 新结果
        for (DownloadedFiling filing : newFilings) {
            ObjectNode docNode = toManifestNode(filing, now);
            existingDocs.put(filing.getDocumentId(), docNode);
        }

        // 写回排序后的数组
        ArrayNode updatedDocs = objectMapper.createArrayNode();
        existingDocs.values().stream()
                .sorted(Comparator.comparing(
                        d -> d.has("filing_date") ? d.get("filing_date").asText("") : "",
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(updatedDocs::add);
        manifest.set("documents", updatedDocs);
        manifest.put("updated_at", now);

        Files.createDirectories(manifestPath.getParent());
        Files.writeString(manifestPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
    }

    private ObjectNode toManifestNode(DownloadedFiling filing, String now) {
        ObjectNode docNode = objectMapper.createObjectNode();
        docNode.put("document_id", filing.getDocumentId());
        docNode.put("internal_document_id", filing.getInternalDocumentId());
        docNode.put("form_type", filing.getFormType());
        docNode.put("fiscal_year", filing.getFiscalYear());
        docNode.putNull("fiscal_period");
        docNode.put("report_date", filing.getReportDate());
        docNode.put("filing_date", filing.getFilingDate());
        docNode.put("amended", false);
        docNode.put("ingest_method", "download");
        docNode.put("ingest_complete", true);
        docNode.put("is_deleted", false);
        docNode.putNull("deleted_at");
        docNode.put("document_version", "v1");
        docNode.put("source_fingerprint", filing.getSourceFingerprint());
        docNode.put("has_xbrl", filing.isHasXbrl());
        return docNode;
    }

    /**
     * 计算目录的 SHA256 fingerprint，排除 meta.json 和 manifest 文件。
     */
    public String calculateDirectoryFingerprint(Path dir) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return !name.equals("meta.json") && !name.equals("filing_manifest.json");
                    })
                    .sorted()
                    .toList();
        }

        for (Path file : files) {
            byte[] content = Files.readAllBytes(file);
            digest.update(content);
        }

        byte[] hash = digest.digest();
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * 计算字节数组的 SHA256
     */
    protected String calculateSHA256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            logger.error("Error calculating SHA256", e);
            return "";
        }
    }

    /**
     * 构建下载结果字符串
     */
    protected String buildDownloadResult(String ticker, Set<String> formTypes, Set<Integer> fiscalYears,
                                         boolean allYears, List<DownloadedFiling> downloadedFilings,
                                         List<String> errors) {
        return buildDownloadResult(ticker, formTypes, fiscalYears, allYears, downloadedFilings, errors, List.of());
    }

    protected String buildDownloadResult(String ticker, Set<String> formTypes, Set<Integer> fiscalYears,
                                         boolean allYears, List<DownloadedFiling> downloadedFilings,
                                         List<String> errors, List<String> skipped) {
        StringBuilder result = new StringBuilder();
        String yearDesc = allYears ? "all" : fiscalYears.toString();

        result.append(String.format("Successfully downloaded %d filing(s) for %s %s (years: %s)\n\n",
                downloadedFilings.size(), ticker, formTypes, yearDesc));

        if (!downloadedFilings.isEmpty()) {
            result.append("Downloaded filings:\n");
            for (int i = 0; i < downloadedFilings.size(); i++) {
                DownloadedFiling filing = downloadedFilings.get(i);
                result.append(String.format("  [%d] %s (%s FY%d, Report: %s, Filing: %s)\n",
                        i + 1, filing.getDocumentId(), filing.getFormType(), filing.getFiscalYear(),
                        filing.getReportDate(), filing.getFilingDate()));
            }
            result.append(String.format("\nFiles saved to: %s/%s/filings/\n",
                    WorkspacePaths.companiesDir(workspaceDir).toAbsolutePath(), ticker));
            result.append("Each filing directory contains:\n");
            result.append("  - Primary document\n");
            result.append("  - XBRL taxonomy files (.xsd, .xml)\n");
            result.append("  - meta.json with filing metadata\n");
        }

        if (!skipped.isEmpty()) {
            result.append("\nSkipped filings:\n");
            for (String item : skipped) {
                result.append(String.format("  - %s\n", item));
            }
        }

        if (!errors.isEmpty()) {
            result.append("\nErrors:\n");
            for (String error : errors) {
                result.append(String.format("  - %s\n", error));
            }
        }

        return result.toString();
    }

    /**
     * 构建下载结果 DTO
     */
    protected FinancialFilingDownloadResult buildDownloadResultDTO(String ticker, Set<String> formTypes,
                                                                   Set<Integer> fiscalYears, boolean allYears,
                                                                   List<DownloadedFiling> downloadedFilings,
                                                                   List<String> errors, List<String> skipped) {
        String message = buildDownloadResult(ticker, formTypes, fiscalYears, allYears, downloadedFilings, errors, skipped);
        boolean success = errors.isEmpty() || downloadedFilings.size() > 0;
        String error = errors.isEmpty() ? null : String.join("; ", errors);

        return FinancialFilingDownloadResult.builder()
                .success(success)
                .ticker(ticker)
                .formTypes(formTypes != null ? List.copyOf(formTypes) : List.of())
                .fiscalYears(fiscalYears != null ? List.copyOf(fiscalYears) : List.of())
                .allYears(allYears)
                .totalCount(downloadedFilings.size() + skipped.size() + errors.size())
                .downloadedCount(downloadedFilings.size())
                .skippedCount(skipped.size())
                .errorCount(errors.size())
                .downloadedFilings(downloadedFilings)
                .skipped(skipped)
                .errors(errors)
                .message(message)
                .error(error)
                .build();
    }

    protected FinancialFilingDownloadResult buildErrorResult(String ticker, String errorMessage) {
        return FinancialFilingDownloadResult.builder()
                .success(false)
                .ticker(ticker)
                .formTypes(List.of())
                .fiscalYears(List.of())
                .allYears(false)
                .totalCount(0)
                .downloadedCount(0)
                .skippedCount(0)
                .errorCount(1)
                .downloadedFilings(List.of())
                .skipped(List.of())
                .errors(List.of(errorMessage))
                .message("Error: " + errorMessage)
                .error(errorMessage)
                .build();
    }

    protected void deactivateFiling(String ticker, String documentId, String reason) throws IOException {
        markFilingInactive(ticker, documentId, reason);
    }

    protected void deleteFilingDirectory(String ticker, String documentId, String reason) throws IOException {
        markFilingInactive(ticker, documentId, reason);
        Path filingDir = WorkspacePaths.filingsDir(workspaceDir, ticker, documentId);
        if (!Files.exists(filingDir)) {
            return;
        }
        try (var stream = Files.walk(filingDir)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void markFilingInactive(String ticker, String documentId, String reason) throws IOException {
        String now = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        Path filingDir = WorkspacePaths.filingsDir(workspaceDir, ticker, documentId);
        Path metaPath = filingDir.resolve("meta.json");
        if (Files.exists(metaPath)) {
            ObjectNode meta = (ObjectNode) objectMapper.readTree(Files.readString(metaPath));
            meta.put("ingest_complete", false);
            meta.put("is_deleted", true);
            meta.put("deleted_at", now);
            meta.put("rejection_reason", reason);
            meta.put("updated_at", now);
            Files.writeString(metaPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(meta));
        }

        Path manifestPath = WorkspacePaths.filingsDir(workspaceDir, ticker).resolve("filing_manifest.json");
        if (!Files.exists(manifestPath)) {
            return;
        }
        ObjectNode manifest = (ObjectNode) objectMapper.readTree(Files.readString(manifestPath));
        JsonNode docsNode = manifest.get("documents");
        if (docsNode instanceof ArrayNode documents) {
            for (int i = 0; i < documents.size(); i++) {
                JsonNode doc = documents.get(i);
                if (doc instanceof ObjectNode docNode
                        && documentId.equals(docNode.path("document_id").asText())) {
                    docNode.put("ingest_complete", false);
                    docNode.put("is_deleted", true);
                    docNode.put("deleted_at", now);
                    docNode.put("rejection_reason", reason);
                }
            }
            manifest.put("updated_at", now);
            Files.writeString(manifestPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
        }
    }

    protected boolean isCacheExpired(Path cacheFile) throws IOException {
        long lastModified = Files.getLastModifiedTime(cacheFile).toMillis();
        long now = System.currentTimeMillis();
        long oneDay = 24 * 60 * 60 * 1000L;
        return (now - lastModified) > oneDay;
    }

    /**
     * 从 announcementTime（毫秒时间戳）提取日期
     */
    protected String extractDateFromAnnouncementTime(String announcementTime) {
        if (announcementTime == null || announcementTime.isEmpty()) return "";
        try {
            long timestamp = Long.parseLong(announcementTime);
            return Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate().toString();
        } catch (Exception e) {
            return announcementTime.length() >= 10 ? announcementTime.substring(0, 10) : announcementTime;
        }
    }

    protected String guessContentType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".htm") || lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".xsd")) return "application/xml";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }
}
