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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/**
 * A股巨潮资讯下载器
 */
public class CnInfoDownloader extends FilingDownloadSupport {

    private static final Logger logger = LoggerFactory.getLogger(CnInfoDownloader.class);

    // CN-INFO 配置
    private static final String CN_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    private static final String CN_STOCK_MAPPING_URL = "http://www.cninfo.com.cn/new/data/szse_stock.json";
    private static final String CN_QUERY_URL = "http://www.cninfo.com.cn/new/hisAnnouncement/query";

    private final HttpClient httpClient;
    private final Map<String, String> cnStockToOrgIdCache;

    public CnInfoDownloader(Path workspaceDir, HttpClient httpClient, ObjectMapper objectMapper) {
        super(workspaceDir, objectMapper);
        this.httpClient = httpClient;
        this.cnStockToOrgIdCache = new HashMap<>();
    }

    public FinancialFilingDownloadResult downloadCnFiling(String normalizedCode, Set<Integer> fiscalYears, boolean allYears,
                                   Set<String> formTypes, boolean allTypes, boolean overwrite) throws Exception {
        logger.info("Downloading CN filing for stock: {}, years: {}, types: {}", normalizedCode, fiscalYears, formTypes);

        // 1. 解析股票代码
        String exchange = normalizedCode.startsWith("6") ? "sse" : "szse";

        // 2. 获取 orgId
        String orgId = getCnOrgIdForStock(normalizedCode);
        if (orgId == null) {
            return buildErrorResult(normalizedCode, String.format("Could not find organization ID for stock %s", normalizedCode));
        }
        logger.info("Stock: {}, Exchange: {}, OrgId: {}", normalizedCode, exchange, orgId);

        // 3. 确定搜索年份范围（全量 = 最近 10 年）
        Set<Integer> searchYears = fiscalYears;
        if (allYears) {
            int currentYear = java.time.Year.now().getValue();
            searchYears = new LinkedHashSet<>();
            for (int y = currentYear; y >= currentYear - 10; y--) {
                searchYears.add(y);
            }
        }

        String stockParam = normalizedCode + "," + orgId;
        List<DownloadedFiling> downloadedFilings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // 4. 逐年分批下载
        for (int fiscalYear : searchYears) {
            for (String formType : formTypes) {
                try {
                    DownloadedFiling result = downloadSingleCnFiling(
                            normalizedCode, exchange, stockParam, fiscalYear, formType, overwrite);
                    if (result != null) {
                        downloadedFilings.add(result);
                    }
                } catch (Exception e) {
                    errors.add(String.format("Failed to download %s %s FY%d: %s",
                            normalizedCode, formType, fiscalYear, e.getMessage()));
                    logger.debug("Error downloading CN filing {} {} FY{}: {}",
                            normalizedCode, formType, fiscalYear, e.getMessage());
                }
                Thread.sleep(800);
            }
        }

        // 5. 更新 manifest
        if (!downloadedFilings.isEmpty()) {
            updateFilingManifest(normalizedCode, downloadedFilings);
        }

        // 6. 构建结果
        return buildDownloadResultDTO(normalizedCode, formTypes, searchYears, allYears, downloadedFilings, errors, List.of());
    }

    private DownloadedFiling downloadSingleCnFiling(String stockCode, String exchange,
                                                     String stockParam, int fiscalYear,
                                                     String formType, boolean overwrite) throws Exception {
        String category = getCnCategoryByFormType(formType);

        // annual/FY 优先查询 fiscalYear + 1（年报通常在次年发布）
        boolean isAnnual = "FY".equals(formType);
        int searchYear = isAnnual ? fiscalYear + 1 : fiscalYear;
        String startDate = String.format("%d-01-01", searchYear);
        String endDate = String.format("%d-12-31", searchYear);

        JsonNode searchResult = searchCnAnnouncements(stockParam, exchange, startDate, endDate, category);

        // annual 无结果时回退到 fiscalYear
        if (isAnnual && (searchResult == null || !searchResult.has("announcements")
                || searchResult.get("announcements").isEmpty())) {
            logger.info("No results in FY{} for {}, trying FY{}", searchYear, stockCode, fiscalYear);
            startDate = String.format("%d-01-01", fiscalYear);
            endDate = String.format("%d-12-31", fiscalYear);
            searchResult = searchCnAnnouncements(stockParam, exchange, startDate, endDate, category);
        }

        if (searchResult == null || !searchResult.has("announcements")
                || searchResult.get("announcements").isEmpty()) {
            logger.debug("No CN filing found for {} {} FY{}", stockCode, formType, fiscalYear);
            return null;
        }

        JsonNode announcements = searchResult.get("announcements");

        // 查找最佳匹配
        JsonNode targetFiling = findBestCnFiling(announcements, stockCode, formType);
        if (targetFiling == null) {
            logger.debug("No matching CN filing for {} {} FY{}", stockCode, formType, fiscalYear);
            return null;
        }

        String adjunctUrl = targetFiling.get("adjunctUrl").asText();
        String title = targetFiling.has("announcementTitle")
                ? targetFiling.get("announcementTitle").asText() : "Unknown";
        String announcementTime = targetFiling.has("announcementTime")
                ? targetFiling.get("announcementTime").asText() : "";
        String announcementId = targetFiling.has("announcementId")
                ? targetFiling.get("announcementId").asText() : "";

        // documentId 优先使用 announcementId
        String documentId;
        if (!announcementId.isEmpty()) {
            documentId = "fil_cn_" + announcementId;
        } else {
            documentId = "fil_cn_" + announcementTime + "_" + stockCode + "_" + formType;
        }

        // 快速跳过：如果 meta.json 已存在且不需要覆盖，直接返回
        Path filingDir = WorkspacePaths.filingsDir(workspaceDir, stockCode, documentId);
        Path metaPath = filingDir.resolve("meta.json");
        if (!overwrite && Files.exists(metaPath)) {
            logger.info("CN filing already exists, skipping download: {}", documentId);
            return null;
        }

        logger.info("Downloading CN filing: {} -> {}", title, documentId);

        Files.createDirectories(filingDir);

        // 下载 PDF
        String downloadUrl = String.format("http://static.cninfo.com.cn/%s", adjunctUrl);
        String fileName = String.format("%s_%d_%s_%s.pdf",
                stockCode, fiscalYear, formType,
                announcementId.isEmpty() ? announcementTime : announcementId);

        Path filePath = filingDir.resolve(fileName);
        byte[] content;
        if (!overwrite && Files.exists(filePath)) {
            logger.info("Reusing existing CN filing file: {}", filePath.toAbsolutePath());
            content = Files.readAllBytes(filePath);
        } else {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .header("User-Agent", CN_USER_AGENT)
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                throw new IOException(String.format("Failed to download %s (Status: %d)", title, response.statusCode()));
            }

            content = response.body();
            Files.write(filePath, content);
        }

        // 计算 SHA256
        String sha256 = calculateSHA256(content);
        long fileSize = content.length;

        // fingerprint（排除 meta.json）
        String fingerprint = calculateDirectoryFingerprint(filingDir);

        // 提取日期
        String reportDate = extractDateFromAnnouncementTime(announcementTime);
        String filingDate = reportDate;

        // 创建文件元数据
        String now = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        DownloadedFile dFile = new DownloadedFile(
                fileName,
                "local://portfolio/" + stockCode + "/filings/" + documentId + "/" + fileName,
                sha256, fileSize, downloadUrl, "application/pdf", now
        );

        // 创建 meta.json
        ObjectNode metaJson = createCnFilingMetaJson(
                documentId, stockCode, stockCode, formType, fiscalYear,
                reportDate, filingDate, fingerprint, dFile
        );
        Files.writeString(metaPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metaJson));

        logger.info("Successfully downloaded to: {}", filePath.toAbsolutePath());
        return new DownloadedFiling(
                documentId, documentId.replace("fil_cn_", ""), formType, fiscalYear,
                reportDate, filingDate, fingerprint, false, List.of(dFile)
        );
    }

    private JsonNode findBestCnFiling(JsonNode announcements, String stockCode, String formType) {
        if (announcements == null || !announcements.isArray()) return null;

        List<JsonNode> candidates = new ArrayList<>();
        for (JsonNode ann : announcements) {
            String title = ann.has("announcementTitle")
                    ? ann.get("announcementTitle").asText().toLowerCase(Locale.ROOT) : "";
            String secCode = ann.has("secCode") ? ann.get("secCode").asText() : "";

            if (!secCode.equals(stockCode)) continue;

            int score = switch (formType) {
                case "FY" -> scoreAnnualCnFiling(title);
                case "H1" -> scoreSemiAnnualCnFiling(title);
                case "Q1", "Q2", "Q3", "Q4" -> scoreQuarterlyCnFiling(title, formType);
                default -> 0;
            };

            if (score > 50) {
                ann = ((ObjectNode) ann).put("_score", score);
                candidates.add(ann);
            }
        }

        if (candidates.isEmpty()) return null;
        candidates.sort((a, b) -> Integer.compare(b.get("_score").asInt(), a.get("_score").asInt()));
        return candidates.get(0);
    }

    private int scoreAnnualCnFiling(String title) {
        if (title.contains("年度报告") || title.contains("年报")) {
            int score = 100;
            if (title.contains("摘要") || title.contains("摘要版")) score -= 80;
            if (title.contains("英文") || title.contains("english")) score -= 50;
            if (title.contains("修订") || title.contains("更新") || title.contains("更正")) score += 30;
            if (title.contains("全文") || (!title.contains("摘要") && !title.contains("摘要版"))) score += 20;
            return score;
        }
        return 0;
    }

    private int scoreSemiAnnualCnFiling(String title) {
        if (title.contains("半年度报告") || title.contains("中报")) {
            int score = 100;
            if (title.contains("摘要") || title.contains("摘要版")) score -= 80;
            if (title.contains("英文") || title.contains("english")) score -= 50;
            if (title.contains("全文") || (!title.contains("摘要") && !title.contains("摘要版"))) score += 20;
            return score;
        }
        return 0;
    }

    private int scoreQuarterlyCnFiling(String title, String formType) {
        int quarterNum;
        try {
            quarterNum = Integer.parseInt(formType.substring(1));
        } catch (Exception e) {
            return 0;
        }

        // Q1 -> 一季报/第一季度报告, Q3 -> 三季报/第三季度报告
        String[] keywords = {"第" + toChineseNum(quarterNum) + "季度报告",
                "第" + quarterNum + "季度报告",
                quarterNum + "季报"};
        boolean matched = false;
        for (String kw : keywords) {
            if (title.contains(kw)) {
                matched = true;
                break;
            }
        }
        if (!matched && title.contains("季度报告") && title.contains("季报")) {
            // 通用季度报告但不确定是哪个季度，谨慎匹配
            matched = false;
        }

        if (matched) {
            int score = 100;
            if (title.contains("摘要") || title.contains("摘要版")) score -= 80;
            if (title.contains("英文") || title.contains("english")) score -= 50;
            if (title.contains("全文") || (!title.contains("摘要") && !title.contains("摘要版"))) score += 20;
            return score;
        }
        return 0;
    }

    private String toChineseNum(int n) {
        return switch (n) {
            case 1 -> "一";
            case 2 -> "二";
            case 3 -> "三";
            case 4 -> "四";
            default -> String.valueOf(n);
        };
    }

    private String getCnOrgIdForStock(String stockCode) throws IOException, InterruptedException {
        if (cnStockToOrgIdCache.containsKey(stockCode)) {
            return cnStockToOrgIdCache.get(stockCode);
        }
        if (cnStockToOrgIdCache.isEmpty()) {
            loadCnStockOrgIdMapping();
        }
        return cnStockToOrgIdCache.get(stockCode);
    }

    private void loadCnStockOrgIdMapping() throws IOException, InterruptedException {
        logger.info("Loading stock-orgId mapping from cninfo...");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CN_STOCK_MAPPING_URL))
                .header("User-Agent", CN_USER_AGENT)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode stockList = root.get("stockList");
            if (stockList != null && stockList.isArray()) {
                for (JsonNode stock : stockList) {
                    String code = stock.has("code") ? stock.get("code").asText() : "";
                    String orgId = stock.has("orgId") ? stock.get("orgId").asText() : "";
                    if (!code.isEmpty() && !orgId.isEmpty()) {
                        cnStockToOrgIdCache.put(code, orgId);
                    }
                }
                logger.info("Loaded {} stock-orgId mappings", cnStockToOrgIdCache.size());
            }
        }
    }

    private String getCnCategoryByFormType(String formType) {
        return switch (formType.toUpperCase(Locale.ROOT)) {
            case "FY" -> "category_ndbg_szsh";
            case "H1" -> "category_bndbg_szsh";
            case "Q1", "Q2", "Q3", "Q4" -> "category_jdbg_szsh";
            default -> "category_ndbg_szsh";
        };
    }

    private JsonNode searchCnAnnouncements(String stockParam, String exchange, String startDate, String endDate, String category)
            throws IOException, InterruptedException {

        String postData = String.format(
                "pageNum=1&pageSize=30&tabName=fulltext&plate=&stock=%s&searchkey=&secid=&category=%s&trade=&seDate=%s~%s&sortName=time&sortType=desc&isHLtitle=true&column=%s",
                stockParam, category, startDate, endDate, exchange
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CN_QUERY_URL))
                .header("User-Agent", CN_USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Accept", "*/*")
                .header("Origin", "http://www.cninfo.com.cn")
                .header("Referer", "http://www.cninfo.com.cn/new/commonUrl/pageOfSearch?url=disclosure/list/search")
                .header("X-Requested-With", "XMLHttpRequest")
                .POST(HttpRequest.BodyPublishers.ofString(postData))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            return objectMapper.readTree(response.body());
        }
        return null;
    }
}
