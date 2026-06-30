package io.invest.iagent.service.filing.downloader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.invest.iagent.model.FinancialFilingDownloadResult;
import io.invest.iagent.service.filing.model.DownloadedFile;
import io.invest.iagent.service.filing.model.DownloadedFiling;
import io.invest.iagent.service.filing.util.FilingDownloadSupport;
import io.invest.iagent.service.filing.util.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 美股 SEC EDGAR 下载器
 */
public class SecFilingDownloader extends FilingDownloadSupport {

    private static final Logger logger = LoggerFactory.getLogger(SecFilingDownloader.class);

    // SEC EDGAR 配置
    private static final String SEC_EDGAR_BASE = "https://www.sec.gov/Archives/edgar/data";
    private static final String SEC_COMPANY_TICKERS_URL = "https://www.sec.gov/files/company_tickers.json";

    private static final Pattern SEC_6K_EX99_1_FILE_PATTERN = Pattern.compile(".*(?:dex991|ex99[-_]?1).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEC_6K_EX99_2_3_FILE_PATTERN = Pattern.compile(".*(?:dex99[23]|ex99[-_]?[23]).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEC_6K_EX99_FILE_PATTERN = Pattern.compile(".*(?:dex99|ex99).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEC_6K_TITLE_EXCLUDE_PATTERN = Pattern.compile(
            "(?i).*(analyst\\s+(visit|day)|repurchase right notification|" +
                    "(proposed\\s+offering|pricing|issuance|exchange|repurchase).{0,80}convertible senior notes|" +
                    "updates on its investments|strategic\\s+review|quarterly\\s+activities\\s+and\\s+cashflow\\s+report|" +
                    "agrees\\s+to\\s+acquire|completes?\\s+acquisition\\s+of|entered\\s+into\\s+a\\s+definitive\\s+merger\\s+agreement|" +
                    "capitalization\\s+and\\s+indebtedness|next\\s+day\\s+disclosure\\s+return|discloseable\\s+transaction|" +
                    "(clinical|trial|study).{0,60}(interim|half.?year).{0,60}results?|" +
                    "(open-label|extension).{0,80}(clinical\\s+dataset|interim\\s+results?)).*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SEC_6K_STRONG_EXCLUDE_PATTERN = Pattern.compile(
            "(?i).*(date\\s+of\\s+(audit\\s+committee|board)\\s+meeting|financial\\s+results?\\s+announcement\\s+date|" +
                    "invitation\\s+to\\s+the\\s+annual\\s+general\\s+meeting|profit\\s+warning|profit\\s+alert|" +
                    "transcript\\s+of\\s+the\\s+earnings\\s+call|earnings\\s+(conference\\s+call\\s+)?transcript|" +
                    "production\\s+results?.{0,160}(earnings?\\s+call|conference\\s+call|financial\\s+results?\\s+will\\s+be\\s+released|will\\s+be\\s+released)|" +
                    "operating\\s+update.{0,240}financial\\s+results?\\s+are\\s+only\\s+provided\\s+on\\s+a\\s+six-?monthly\\s+basis).*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SEC_6K_STRONG_KEEP_PATTERN = Pattern.compile(
            "(?i).*(financial results and business updates|key highlights for the (first|second|third|fourth) quarter|" +
                    "reports?\\s+(the\\s+)?((first|second|third|fourth)[-\\s]+quarter|q[1-4]).{0,120}financial\\s+results|" +
                    "reports?\\s+full[-\\s]+year.{0,120}financial\\s+results?.{0,120}(fourth[-\\s]+quarter|q4).{0,80}business\\s+update|" +
                    "reports?\\s+half[-\\s]+year.{0,120}financial\\s+results?.{0,120}(second[-\\s]+quarter|q2).{0,80}business\\s+update|" +
                    "reports unaudited .* financial results|((1q|2q|3q|4q|q[1-4]).{0,20}(results?|earnings?|financial\\s+results?))|" +
                    "financial\\s+management\\s+review|quarterly\\s+ytd\\s+report|q[1-4].{0,40}full\\s+year.{0,40}financial\\s+results|" +
                    "q[1-4].{0,240}financial\\s+statements|financial\\s+report.{0,80}(quarter|first\\s+quarter|second\\s+quarter|third\\s+quarter|fourth\\s+quarter)|" +
                    "announces.{0,80}quarter.{0,80}results|reported\\s+its\\s+financial\\s+results\\s+for\\s+the\\s+three\\s+and\\s+twelve\\s+month\\s+periods\\s+ending|" +
                    "announced its financial results for the quarter|quarter \\d{4} results|unaudited.{1,60}financial results|" +
                    "unaudited\\s+condensed\\s+consolidated\\s+financial\\s+statements?.{0,240}(three\\s+and\\s+six|three\\s+and\\s+nine|quarter\\s+ended|six\\s+months?\\s+ended|nine\\s+months?\\s+ended)|" +
                    "interim\\s+report.{0,240}(financial\\s+information|financial\\s+statements?|balance\\s+sheets?|statement\\s+of\\s+cash\\s+flows?|statement\\s+of\\s+comprehensive\\s+(income|loss))|" +
                    "(results?|earnings?|financial\\s+results?|financial\\s+report).{0,80}(quarter|three\\s+months?|six\\s+months?|nine\\s+months?|half.?year|interim).{0,40}(ended|end)|" +
                    "(quarter|three\\s+months?|six\\s+months?|nine\\s+months?|half.?year|interim).{0,40}(ended|end).{0,80}(results?|earnings?|financial\\s+results?|financial\\s+report)|" +
                    "quarterly\\s+report\\s+for\\s+(the\\s+)?(period|quarter)|interim\\s+results?\\s+announcement|" +
                    "exhibit\\s+99\\.[12].{0,80}(quarter|quarterly|interim).{0,40}(results?|report|financial)).*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SEC_6K_IFRS_RECON_PATTERN = Pattern.compile(
            "(?i).*(reconciliation between u\\.s\\. gaap and ifrs|unaudited interim condensed consolidated|unaudited consolidated (results|financial statements)).*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final String SEC_6K_FILTER_VERSION = "6k_rules_v1";

    private final HttpClient httpClient;
    private final String secUserAgent;
    private final Map<String, String> usTickerToCikCache;

    public SecFilingDownloader(Path workspaceDir, HttpClient httpClient, ObjectMapper objectMapper, String secUserAgent) {
        super(workspaceDir, objectMapper);
        this.httpClient = httpClient;
        this.secUserAgent = secUserAgent;
        this.usTickerToCikCache = new HashMap<>();
    }

    public FinancialFilingDownloadResult downloadUsFiling(String ticker, Set<Integer> fiscalYears, boolean allYears,
                                   Set<String> formTypes, boolean allTypes, boolean overwrite) throws Exception {
        logger.info("Downloading US filing for ticker: {}, years: {}, types: {}", ticker, fiscalYears, formTypes);

        // 1. 获取 CIK
        String cik = getUsCikForTicker(ticker);
        if (cik == null || cik.isEmpty()) {
            return buildErrorResult(ticker, String.format("Could not find CIK for ticker %s", ticker));
        }
        logger.info("Found CIK: {} for ticker: {}", cik, ticker);

        // 2. 获取公司申报记录
        JsonNode submissions = getUsCompanySubmissions(cik);
        if (submissions == null) {
            return buildErrorResult(ticker, String.format("Could not retrieve submissions for CIK %s", cik));
        }

        // 3. 查找所有匹配的财报
        List<JsonNode> filings = findAllMatchingUsFilings(submissions, formTypes, fiscalYears, allYears);
        if (filings.isEmpty()) {
            return buildErrorResult(ticker, String.format("Could not find any matching %s filing for %s in years %s",
                    formTypes, ticker, allYears ? "all" : fiscalYears));
        }
        logger.info("Found {} matching filings", filings.size());

        // 4. 下载所有匹配的财报
        List<DownloadedFiling> downloadedFilings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (JsonNode filing : filings) {
            String accessionNumber = filing.get("accessionNumber").asText();
            String primaryDocument = filing.get("primaryDocument").asText();
            String reportDate = filing.has("reportDate") ? filing.get("reportDate").asText() : "";
            String filingDate = filing.has("filingDate") ? filing.get("filingDate").asText() : "";
            String formType = filing.has("form") ? filing.get("form").asText() : formTypes.iterator().next();
            int fiscalYear = filing.has("fiscalYear") ? filing.get("fiscalYear").asInt() : 0;

            try {
                String documentId = "fil_" + accessionNumber;
                Path filingDir = WorkspacePaths.filingsDir(workspaceDir, ticker, documentId);

                // 快速跳过：如果 meta.json 已存在且不需要覆盖，直接跳过整个下载
                Path metaPath = filingDir.resolve("meta.json");
                if (!overwrite && Files.exists(metaPath)) {
                    logger.info("Filing already exists, skipping download: {}", documentId);
                    skipped.add(String.format("%s already exists, skipped", accessionNumber));
                    continue;
                }

                Files.createDirectories(filingDir);

                SecFilingPackage filingPackage = downloadSecFilingPackage(
                        cik, accessionNumber, primaryDocument, filingDir, ticker, formType, overwrite);
                List<DownloadedFile> files = filingPackage.files();

                // 创建 meta.json
                ObjectNode metaJson = createFilingMetaJson(
                        documentId, accessionNumber, ticker, cik, formType, fiscalYear,
                        reportDate, filingDate, filingPackage.sourceFingerprint(), files, filingPackage.primaryDocument()
                );
                if ("6-K".equals(formType)) {
                    metaJson.put("six_k_filter_version", SEC_6K_FILTER_VERSION);
                }
                Files.writeString(metaPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metaJson));

                boolean hasXbrl = files.stream().anyMatch(f -> f.getName().endsWith(".xml"));
                downloadedFilings.add(new DownloadedFiling(
                        documentId, accessionNumber, formType, fiscalYear,
                        reportDate, filingDate, filingPackage.sourceFingerprint(), hasXbrl, files
                ));
                logger.info("Successfully downloaded filing to: {}", filingDir.toAbsolutePath());

            } catch (SixKRejectedException e) {
                String documentId = "fil_" + accessionNumber;
                if ("NO_MATCH".equals(e.reason()) || "EXCLUDE_NON_QUARTERLY".equals(e.reason())) {
                    deleteFilingDirectory(ticker, documentId, e.reason());
                } else {
                    deactivateFiling(ticker, documentId, e.reason());
                }
                skipped.add(String.format("%s 6-K rejected: %s", accessionNumber, e.reason()));
                logger.info("Skipped 6-K filing {}: {}", accessionNumber, e.reason());
            } catch (Exception e) {
                errors.add(String.format("Failed to download accession %s: %s", accessionNumber, e.getMessage()));
                logger.error("Error downloading filing", e);
            }

            Thread.sleep(1000);
        }

        // 5. 更新 manifest
        updateFilingManifest(ticker, downloadedFilings);

        // 6. 构建结果
        return buildDownloadResultDTO(ticker, formTypes, fiscalYears, allYears, downloadedFilings, errors, skipped);
    }

    /**
     * 查找匹配的美股申报记录。
     * 形式类型精确匹配，年份优先使用 reportDate 年份，无 reportDate 时尝试从主文档名提取财年。
     */
    public List<JsonNode> findAllMatchingUsFilings(JsonNode submissions, Set<String> formTypes,
                                                     Set<Integer> fiscalYears, boolean allYears) {
        List<JsonNode> results = new ArrayList<>();

        JsonNode recent = submissions.get("filings").get("recent");
        if (recent == null) return results;

        JsonNode forms = recent.get("form");
        JsonNode accessionNumbers = recent.get("accessionNumber");
        JsonNode documents = recent.get("primaryDocument");
        JsonNode reportDates = recent.get("reportDate");
        JsonNode filingDates = recent.get("filingDate");

        if (forms == null) return results;

        for (int i = 0; i < forms.size(); i++) {
            String formType = forms.get(i).asText();
            if (!formTypes.contains(formType)) continue;

            // 确定 fiscal year
            int fy = 0;
            String reportDateStr = (reportDates != null && i < reportDates.size())
                    ? reportDates.get(i).asText() : "";
            String filingDateStr = (filingDates != null && i < filingDates.size())
                    ? filingDates.get(i).asText() : "";

            String primaryDocumentStr = (documents != null && i < documents.size())
                    ? documents.get(i).asText() : "";

            if (reportDateStr.length() >= 4) {
                fy = Integer.parseInt(reportDateStr.substring(0, 4));
            } else {
                fy = inferFiscalYearFromFileName(primaryDocumentStr);
                if (fy == 0 && filingDateStr.length() >= 4) {
                    int filingYear = Integer.parseInt(filingDateStr.substring(0, 4));
                    fy = isAnnualUsForm(formType) ? filingYear - 1 : filingYear;
                }
            }

            // 年份过滤：优先以 reportDate/主文档日期判定财年，不能因为 filingDate 落在目标年份就下载上一财年年报
            if (!allYears && !fiscalYears.contains(fy)) {
                continue;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("accessionNumber", (accessionNumbers != null && i < accessionNumbers.size())
                    ? accessionNumbers.get(i).asText() : "");
            result.put("primaryDocument", primaryDocumentStr);
            result.put("filingDate", filingDateStr);
            result.put("reportDate", reportDateStr);
            result.put("form", formType);
            result.put("fiscalYear", fy);
            results.add(objectMapper.valueToTree(result));
        }

        return results;
    }

    private int inferFiscalYearFromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return 0;
        var matcher = java.util.regex.Pattern.compile("(?<!\\d)(19|20)\\d{2}(0[1-9]|1[0-2])([0-2]\\d|3[01])(?!\\d)")
                .matcher(fileName);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group().substring(0, 4));
        }
        return 0;
    }

    private boolean isAnnualUsForm(String formType) {
        return Set.of("10-K", "10-K/A", "20-F", "20-F/A", "40-F", "40-F/A").contains(formType);
    }

    private String getUsCikForTicker(String ticker) throws IOException, InterruptedException {
        if (usTickerToCikCache.containsKey(ticker.toUpperCase(Locale.ROOT))) {
            return usTickerToCikCache.get(ticker.toUpperCase(Locale.ROOT));
        }

        Path cacheFile = WorkspacePaths.companiesDir(workspaceDir).resolve("us_company_tickers.json");
        if (!Files.exists(cacheFile) || isCacheExpired(cacheFile)) {
            logger.info("Downloading company tickers from SEC...");
            downloadUsCompanyTickers(cacheFile);
        }

        if (Files.exists(cacheFile)) {
            String jsonContent = Files.readString(cacheFile);
            JsonNode root = objectMapper.readTree(jsonContent);
            for (JsonNode node : root) {
                String nodeTicker = node.get("ticker").asText().toUpperCase(Locale.ROOT);
                if (nodeTicker.equals(ticker.toUpperCase(Locale.ROOT))) {
                    String cikStr = String.format("%010d", node.get("cik_str").asInt());
                    usTickerToCikCache.put(ticker.toUpperCase(Locale.ROOT), cikStr);
                    return cikStr;
                }
            }
        }
        return null;
    }

    private void downloadUsCompanyTickers(Path cacheFile) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SEC_COMPANY_TICKERS_URL))
                .header("User-Agent", secUserAgent)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            Files.writeString(cacheFile, response.body());
            logger.info("Company tickers cached to: {}", cacheFile.toAbsolutePath());
        }
    }

    private JsonNode getUsCompanySubmissions(String cik) throws IOException, InterruptedException {
        String url = String.format("https://data.sec.gov/submissions/CIK%s.json", cik);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", secUserAgent)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            return objectMapper.readTree(response.body());
        } else {
            logger.error("Failed to get company submissions. Status code: {}", response.statusCode());
            return null;
        }
    }

    // ========== SEC 下载包（基于 index.json） ==========

    /**
     * 下载 SEC filing 完整包（包括主文档、展品和相关 XBRL 文件），基于 index.json 精准获取文件列表。
     */
    private SecFilingPackage downloadSecFilingPackage(String cik, String accessionNumber,
                                                       String primaryDocument, Path filingDir,
                                                       String ticker, String formType, boolean overwrite) throws Exception {
        String accessionNoDash = accessionNumber.replace("-", "");
        String normalizedCik = cik.replaceFirst("^0+(?!$)", "");
        String indexJsonUrl = String.format("%s/%s/%s/index.json", SEC_EDGAR_BASE, normalizedCik, accessionNoDash);
        JsonNode indexData = fetchIndexJson(indexJsonUrl);

        if (indexData == null) {
            if ("6-K".equals(formType)) {
                throw new IOException("Cannot fetch index.json for 6-K: " + indexJsonUrl);
            }
            logger.warn("Cannot fetch index.json, falling back to primary document only: {}", indexJsonUrl);
            List<DownloadedFile> files = downloadPrimaryOnly(primaryDocument, filingDir, ticker, normalizedCik, accessionNoDash, overwrite);
            String fingerprint = calculateDirectoryFingerprint(filingDir);
            return new SecFilingPackage(files, fingerprint, null);
        }

        SecPackageIndex packageIndex = loadSecPackageIndex(indexData, accessionNumber, normalizedCik, accessionNoDash, formType, primaryDocument);
        if (packageIndex.files().isEmpty()) {
            if ("6-K".equals(formType)) {
                throw new IOException("No file list in index.json for 6-K");
            }
            logger.warn("No file list in index.json, falling back to primary document only");
            List<DownloadedFile> files = downloadPrimaryOnly(primaryDocument, filingDir, ticker, normalizedCik, accessionNoDash, overwrite);
            String fingerprint = calculateDirectoryFingerprint(filingDir);
            return new SecFilingPackage(files, fingerprint, null);
        }

        if ("6-K".equals(formType)) {
            SixKPrecheckResult precheck = precheckSixK(packageIndex, primaryDocument, filingDir,
                    normalizedCik, accessionNoDash, overwrite);
            if (precheck.decision() == SixKDecision.REJECT) {
                throw new SixKRejectedException(precheck.reason());
            }
            if (precheck.decision() == SixKDecision.DOWNLOAD_FAILED) {
                throw new IOException(precheck.reason());
            }
            List<DownloadedFile> files = downloadAllSecRemoteFiles(packageIndex.files(), normalizedCik, accessionNoDash,
                    filingDir, ticker, overwrite);
            return new SecFilingPackage(files, packageIndex.sourceFingerprint(), precheck.preferredPrimaryDocument());
        }

        List<DownloadedFile> files = new ArrayList<>();
        for (SecRemoteFile remoteFile : packageIndex.files()) {
            if (shouldDownloadFile(remoteFile.name(), primaryDocument, formType, remoteFile.secDocumentType())) {
                String fileUrl = String.format("%s/%s/%s/%s", SEC_EDGAR_BASE, normalizedCik, accessionNoDash, remoteFile.name());
                DownloadedFile df = downloadAndSaveFileWithMetadata(fileUrl, remoteFile.name(), filingDir, ticker, overwrite);
                if (df != null) {
                    files.add(df);
                }
            }
        }

        if (primaryDocument != null) {
            String baseName = primaryDocument.replaceAll("\\.(htm|html)$", "");
            downloadExtraXbrlFiles(baseName, normalizedCik, accessionNoDash, filingDir, ticker, files, overwrite);
        }
        String fingerprint = calculateDirectoryFingerprint(filingDir);
        return new SecFilingPackage(files, fingerprint, null);
    }

    private SecPackageIndex loadSecPackageIndex(JsonNode indexData, String accessionNumber, String normalizedCik,
                                                String accessionNoDash, String formType, String primaryDocument) {
        JsonNode directory = indexData.get("directory");
        JsonNode itemsNode = (directory != null) ? directory.get("item") : null;
        if (itemsNode == null || !itemsNode.isArray()) {
            return new SecPackageIndex(List.of(), Map.of(), "");
        }

        Map<String, String> secDocTypeMap = new HashMap<>();
        if ("6-K".equals(formType)) {
            String headersUrl = String.format("%s/%s/%s/%s-index-headers.html",
                    SEC_EDGAR_BASE, normalizedCik, accessionNoDash, accessionNumber);
            secDocTypeMap = fetchIndexHeaders(headersUrl);
        }

        List<SecRemoteFile> remoteFiles = new ArrayList<>();
        for (JsonNode item : itemsNode) {
            String fileName = item.path("name").asText();
            if (fileName == null || fileName.isBlank()) continue;
            String indexType = item.path("type").asText("");
            String secDocumentType = secDocTypeMap.getOrDefault(fileName, indexType);
            String size = item.path("size").asText("");
            String lastModified = item.path("last-modified").asText("");
            remoteFiles.add(new SecRemoteFile(fileName, indexType, secDocumentType, size, lastModified));
        }
        return new SecPackageIndex(remoteFiles, secDocTypeMap, buildRemoteSourceFingerprint(remoteFiles, primaryDocument));
    }

    private String buildRemoteSourceFingerprint(List<SecRemoteFile> remoteFiles, String primaryDocument) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(String.valueOf(primaryDocument).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            remoteFiles.stream()
                    .sorted(Comparator.comparing(SecRemoteFile::name))
                    .forEach(file -> {
                        String line = String.join("|", file.name(), file.indexType(), file.secDocumentType(), file.size(), file.lastModified());
                        digest.update(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    });
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            logger.warn("Failed to build remote source fingerprint", e);
            return "";
        }
    }

    private SixKPrecheckResult precheckSixK(SecPackageIndex packageIndex, String primaryDocument, Path filingDir,
                                            String normalizedCik, String accessionNoDash, boolean overwrite) {
        if (hasSixKXbrlInstance(packageIndex.files())) {
            return SixKPrecheckResult.keep(selectSixKTargetName(packageIndex.files(), primaryDocument));
        }

        List<SixKCandidate> candidates = collectSixKCandidates(packageIndex.files(), primaryDocument);
        if (candidates.isEmpty()) {
            return SixKPrecheckResult.reject("NO_MATCH");
        }

        List<SixKCandidateDiagnosis> diagnoses = new ArrayList<>();
        for (SixKCandidate candidate : candidates.stream()
                .sorted(Comparator.comparingInt(SixKCandidate::priority).thenComparing(SixKCandidate::fileName))
                .toList()) {
            String fileUrl = String.format("%s/%s/%s/%s", SEC_EDGAR_BASE, normalizedCik, accessionNoDash, candidate.fileName());
            try {
                byte[] content = readExistingOrFetch(fileUrl, candidate.fileName(), filingDir, overwrite);
                if (content == null) {
                    return SixKPrecheckResult.downloadFailed("DOWNLOAD_FAILED");
                }
                String classification = classifySixKText(extractTextPreview(content, 4000));
                diagnoses.add(new SixKCandidateDiagnosis(candidate.fileName(), candidate.priority(), classification,
                        primaryDocument != null && candidate.fileName().equalsIgnoreCase(primaryDocument)));
            } catch (Exception e) {
                logger.warn("Failed to precheck 6-K candidate {}", candidate.fileName(), e);
                return SixKPrecheckResult.downloadFailed("DOWNLOAD_FAILED");
            }
        }

        return diagnoses.stream()
                .filter(d -> isPositiveSixKClassification(d.classification()))
                .min(Comparator.comparingInt(SixKCandidateDiagnosis::priority).thenComparing(SixKCandidateDiagnosis::fileName))
                .map(d -> SixKPrecheckResult.keep(d.fileName()))
                .orElseGet(() -> rejectSixK(packageIndex.files(), diagnoses));
    }

    private SixKPrecheckResult rejectSixK(List<SecRemoteFile> remoteFiles, List<SixKCandidateDiagnosis> diagnoses) {
        if (!hasSixKExhibitCandidate(remoteFiles)) {
            return SixKPrecheckResult.reject("NO_EX99_OR_XBRL");
        }
        boolean primaryExcluded = diagnoses.stream()
                .anyMatch(d -> d.primary() && "EXCLUDE_NON_QUARTERLY".equals(d.classification()));
        if (primaryExcluded) {
            return SixKPrecheckResult.reject("EXCLUDE_NON_QUARTERLY");
        }
        boolean anyExcluded = diagnoses.stream()
                .anyMatch(d -> "EXCLUDE_NON_QUARTERLY".equals(d.classification()));
        if (anyExcluded) {
            return SixKPrecheckResult.reject("EXCLUDE_NON_QUARTERLY");
        }
        return SixKPrecheckResult.reject("NO_MATCH");
    }

    private boolean hasSixKXbrlInstance(List<SecRemoteFile> remoteFiles) {
        return remoteFiles.stream().anyMatch(file -> file.name().toLowerCase(Locale.ROOT).endsWith("_htm.xml"));
    }

    private boolean hasSixKExhibitCandidate(List<SecRemoteFile> remoteFiles) {
        return remoteFiles.stream().anyMatch(file -> {
            String type = file.secDocumentType() == null ? "" : file.secDocumentType().toUpperCase(Locale.ROOT);
            String lower = file.name().toLowerCase(Locale.ROOT);
            return type.startsWith("EX-99") || lower.contains("dex99") || lower.contains("ex99");
        });
    }

    private List<SixKCandidate> collectSixKCandidates(List<SecRemoteFile> remoteFiles, String primaryDocument) {
        Map<String, SixKCandidate> indexed = new LinkedHashMap<>();
        String primaryLower = primaryDocument == null ? "" : primaryDocument.toLowerCase(Locale.ROOT);
        if (primaryDocument != null && !primaryDocument.isBlank()) {
            indexed.put(primaryLower, new SixKCandidate(primaryDocument, null, sixKFilenamePriority(primaryDocument, primaryDocument, null)));
        }
        for (SecRemoteFile remoteFile : remoteFiles) {
            String lower = remoteFile.name().toLowerCase(Locale.ROOT);
            if (!lower.equals(primaryLower) && !lower.endsWith(".htm") && !lower.endsWith(".html")) {
                continue;
            }
            SixKCandidate candidate = new SixKCandidate(remoteFile.name(), remoteFile.secDocumentType(),
                    sixKFilenamePriority(remoteFile.name(), primaryDocument, remoteFile.secDocumentType()));
            SixKCandidate existing = indexed.get(lower);
            if (existing == null || (existing.secDocumentType() == null && candidate.secDocumentType() != null)) {
                indexed.put(lower, candidate);
            }
        }
        return new ArrayList<>(indexed.values());
    }

    private String selectSixKTargetName(List<SecRemoteFile> remoteFiles, String primaryDocument) {
        return remoteFiles.stream()
                .min(Comparator.comparingInt(file -> sixKFilenamePriority(file.name(), primaryDocument, file.secDocumentType())))
                .map(SecRemoteFile::name)
                .orElse(primaryDocument);
    }

    private List<DownloadedFile> downloadAllSecRemoteFiles(List<SecRemoteFile> remoteFiles, String normalizedCik,
                                                           String accessionNoDash, Path filingDir, String ticker,
                                                           boolean overwrite) throws IOException, InterruptedException {
        List<DownloadedFile> files = new ArrayList<>();
        for (SecRemoteFile remoteFile : remoteFiles) {
            String fileUrl = String.format("%s/%s/%s/%s", SEC_EDGAR_BASE, normalizedCik, accessionNoDash, remoteFile.name());
            DownloadedFile df = downloadAndSaveFileWithMetadata(fileUrl, remoteFile.name(), filingDir, ticker, overwrite);
            if (df != null) {
                files.add(df);
            }
        }
        return files;
    }

    private int sixKFilenamePriority(String fileName, String primaryDocument, String secDocumentType) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String normalizedType = secDocumentType == null ? "" : secDocumentType.trim().toUpperCase(Locale.ROOT);
        if ("EX-99.1".equals(normalizedType)) return 0;
        if (Set.of("EX-99.2", "EX-99.3").contains(normalizedType)) return 1;
        if (normalizedType.startsWith("EX-99")) return 2;
        if (SEC_6K_EX99_1_FILE_PATTERN.matcher(lower).matches()) return 0;
        if (SEC_6K_EX99_2_3_FILE_PATTERN.matcher(lower).matches()) return 1;
        if (SEC_6K_EX99_FILE_PATTERN.matcher(lower).matches()) return 2;
        if (primaryDocument != null && lower.equals(primaryDocument.toLowerCase(Locale.ROOT))) return 3;
        return 4;
    }

    private String classifySixKText(String content) {
        if (content == null || content.isBlank()) return "NO_MATCH";
        String normalized = content.replaceAll("\\s+", " ").trim();
        String titlePrefix = normalized.substring(0, Math.min(420, normalized.length()));
        if (SEC_6K_TITLE_EXCLUDE_PATTERN.matcher(titlePrefix).matches()) return "EXCLUDE_NON_QUARTERLY";
        String excludePrefix = normalized.substring(0, Math.min(1600, normalized.length()));
        if (SEC_6K_STRONG_EXCLUDE_PATTERN.matcher(excludePrefix).matches()) return "EXCLUDE_NON_QUARTERLY";
        String keepPrefix = normalized.substring(0, Math.min(4000, normalized.length()));
        if (SEC_6K_STRONG_KEEP_PATTERN.matcher(keepPrefix).matches()) return "RESULTS_RELEASE";
        if (SEC_6K_IFRS_RECON_PATTERN.matcher(keepPrefix).matches()) return "IFRS_RECON";
        return "NO_MATCH";
    }

    private boolean isPositiveSixKClassification(String classification) {
        return "RESULTS_RELEASE".equals(classification) || "IFRS_RECON".equals(classification);
    }

    private String extractTextPreview(byte[] payload, int maxChars) {
        if (payload == null || payload.length == 0) return "";
        String raw = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        String text = raw.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&");
        text = text.replaceAll("\\s+", " ").trim();
        return text.substring(0, Math.min(maxChars, text.length()));
    }

    private byte[] readExistingOrFetch(String url, String fileName, Path targetDir, boolean overwrite) throws IOException, InterruptedException {
        Path filePath = targetDir.resolve(fileName);
        if (!overwrite && Files.exists(filePath)) {
            return Files.readAllBytes(filePath);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", secUserAgent)
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 200) {
            return response.body();
        }
        logger.debug("Failed to fetch 6-K candidate {}: Status {}", url, response.statusCode());
        return null;
    }

    private JsonNode fetchIndexJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", secUserAgent)
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            try {
                return objectMapper.readTree(response.body());
            } catch (Exception e) {
                logger.error("Failed to parse index.json", e);
            }
        }
        return null;
    }

    private Map<String, String> fetchIndexHeaders(String url) {
        Map<String, String> docTypeMap = new HashMap<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", secUserAgent)
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return docTypeMap;

            String[] lines = response.body().split("\n");
            String currentType = "";
            String currentFile = "";

            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("<TYPE>")) {
                    currentType = line.substring(6).trim();
                } else if (line.startsWith("<FILENAME>")) {
                    currentFile = line.substring(10).trim();
                    if (!currentFile.isEmpty() && !currentType.isEmpty()) {
                        docTypeMap.put(currentFile, currentType);
                    }
                    currentType = "";
                    currentFile = "";
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch/parse index-headers: {}", url, e);
        }
        return docTypeMap;
    }

    public boolean shouldDownloadFile(String fileName, String primaryDocument, String formType, String secDocumentType) {
        String nameLower = fileName.toLowerCase(Locale.ROOT);
        int dotIdx = fileName.lastIndexOf(".");
        String ext = (dotIdx >= 0) ? nameLower.substring(dotIdx) : "";

        // 主文档始终下载
        if (fileName.equals(primaryDocument)) return true;

        // 跳过图片、CSS、JS、压缩包、Office 文档等
        Set<String> skipExtensions = Set.of(
                ".jpg", ".jpeg", ".gif", ".png", ".bmp", ".tif", ".tiff", ".svg", ".ico",
                ".css", ".js",
                ".zip", ".tar", ".gz", ".bz2", ".7z",
                ".txt",
                ".xlsx", ".xls", ".doc", ".docx", ".ppt", ".pptx", ".rtf",
                ".wav", ".mp3", ".mp4", ".avi", ".mov"
        );
        if (skipExtensions.contains(ext)) return false;

        // SEC 修订页模式: R1.htm, R2.htm
        if (nameLower.matches("^r\\d+\\.htm[l]?$")) return false;

        // 跳过 xex 样式的展品页（如 li-20241231xex11d2.htm），仅保留主报表和 XBRL 文件
        if (nameLower.matches(".*xex\\d+.*\\.htm[l]?$")) return false;

        // 自动生成的包装页（长数字串）
        if (nameLower.matches(".*\\d{10,}.*")) {
            // 6-K 展品例外
            if ("6-K".equals(formType)) {
                if ((secDocumentType != null && secDocumentType.startsWith("EX-99"))
                        || nameLower.contains("dex99") || nameLower.contains("ex99")) {
                    return true;
                }
            }
            return false;
        }

        // 6-K 展品
        if ("6-K".equals(formType)) {
            if ((secDocumentType != null && secDocumentType.startsWith("EX-99"))
                    || nameLower.contains("dex99") || nameLower.contains("ex99")) {
                return true;
            }
        }

        // 标准展品
        if (nameLower.matches("^exhibit[_-]?\\d.*")) return true;

        // 封面
        if (nameLower.matches("^cover.*")) return true;

        // XBRL
        if (ext.equals(".xml") || ext.equals(".xsd")) return true;

        // HTML
        if (ext.equals(".htm") || ext.equals(".html")) return true;

        return false;
    }

    private List<DownloadedFile> downloadPrimaryOnly(String primaryDocument, Path filingDir, String ticker,
                                                      String normalizedCik, String accessionNoDash, boolean overwrite) throws Exception {
        List<DownloadedFile> files = new ArrayList<>();
        if (primaryDocument != null) {
            String primaryUrl = String.format("%s/%s/%s/%s", SEC_EDGAR_BASE, normalizedCik, accessionNoDash, primaryDocument);
            DownloadedFile primaryFile = downloadAndSaveFileWithMetadata(primaryUrl, primaryDocument, filingDir, ticker, overwrite);
            if (primaryFile != null) files.add(primaryFile);
        }
        return files;
    }

    private void downloadExtraXbrlFiles(String baseName, String normalizedCik, String accessionNoDash,
                                         Path filingDir, String ticker, List<DownloadedFile> files, boolean overwrite) throws Exception {
        String[] xbrlExtensions = {".xsd", "_cal.xml", "_def.xml", "_htm.xml", "_lab.xml", "_pre.xml"};
        for (String ext : xbrlExtensions) {
            String xbrlFileName = baseName + ext;
            String xbrlUrl = String.format("%s/%s/%s/%s", SEC_EDGAR_BASE, normalizedCik, accessionNoDash, xbrlFileName);
            try {
                DownloadedFile df = downloadAndSaveFileWithMetadata(xbrlUrl, xbrlFileName, filingDir, ticker, overwrite);
                if (df != null) files.add(df);
            } catch (Exception e) {
                logger.debug("Could not download XBRL file: {}", xbrlFileName);
            }
        }
    }

    private DownloadedFile downloadAndSaveFileWithMetadata(String url, String fileName, Path targetDir, String ticker, boolean overwrite)
            throws IOException, InterruptedException {
        Path filePath = targetDir.resolve(fileName);
        if (!overwrite && Files.exists(filePath)) {
            byte[] content = Files.readAllBytes(filePath);
            String sha256 = calculateSHA256(content);
            String now = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            logger.debug("Reusing existing file: {}", fileName);
            return new DownloadedFile(
                    fileName,
                    "local://portfolio/" + ticker + "/filings/" + targetDir.getFileName() + "/" + fileName,
                    sha256, content.length, url,
                    guessContentType(fileName), now
            );
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", secUserAgent)
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() == 200) {
            Files.write(filePath, response.body());

            String sha256 = calculateSHA256(response.body());
            String now = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

            logger.debug("Downloaded file: {} ({} bytes)", fileName, response.body().length);
            return new DownloadedFile(
                    fileName,
                    "local://portfolio/" + ticker + "/filings/" + targetDir.getFileName() + "/" + fileName,
                    sha256, response.body().length, url,
                    guessContentType(fileName), now
            );
        } else {
            logger.debug("Failed to download {}: Status {}", url, response.statusCode());
            return null;
        }
    }

    private record SecFilingPackage(List<DownloadedFile> files, String sourceFingerprint, String primaryDocument) {}

    private record SecPackageIndex(List<SecRemoteFile> files, Map<String, String> secDocTypeMap, String sourceFingerprint) {}

    private record SecRemoteFile(String name, String indexType, String secDocumentType, String size, String lastModified) {}

    private record SixKCandidate(String fileName, String secDocumentType, int priority) {}

    private record SixKCandidateDiagnosis(String fileName, int priority, String classification, boolean primary) {}

    private enum SixKDecision {
        KEEP,
        REJECT,
        DOWNLOAD_FAILED
    }

    private record SixKPrecheckResult(SixKDecision decision, String preferredPrimaryDocument, String reason) {
        static SixKPrecheckResult keep(String preferredPrimaryDocument) {
            return new SixKPrecheckResult(SixKDecision.KEEP, preferredPrimaryDocument, null);
        }

        static SixKPrecheckResult reject(String reason) {
            return new SixKPrecheckResult(SixKDecision.REJECT, null, reason);
        }

        static SixKPrecheckResult downloadFailed(String reason) {
            return new SixKPrecheckResult(SixKDecision.DOWNLOAD_FAILED, null, reason);
        }
    }

    private static class SixKRejectedException extends Exception {
        private final String reason;

        SixKRejectedException(String reason) {
            super(reason);
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }
}
