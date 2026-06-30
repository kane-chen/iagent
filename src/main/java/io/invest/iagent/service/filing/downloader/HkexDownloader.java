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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.invest.iagent.service.filing.config.HkIRConfigLoader;
import io.invest.iagent.service.filing.model.HkCompanyIRConfig;

/**
 * 港股港交所披露易下载器
 * <p>
 * 使用 HKEX 披露易 API 和公司IR页面自动下载上市公司财报
 * </p>
 */
public class HkexDownloader extends FilingDownloadSupport {

    private static final Logger logger = LoggerFactory.getLogger(HkexDownloader.class);

    // HKEX 披露易配置
    private static final String HKEX_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String HKEX_SEARCH_URL = "https://www1.hkexnews.hk/search/partial.do";
    private static final String HKEX_DOWNLOAD_BASE = "https://www1.hkexnews.hk";

    // HKEX 文档分类代码
    private static final String T1_CATEGORY_FINANCIAL = "40000";  // 财务报表/环境、社会及管治资料
    private static final String T2_CATEGORY_ANNUAL_REPORT = "40100";  // 年报
    private static final String T2_CATEGORY_INTERIM_REPORT = "40200";  // 中期/半年度报告
    private static final String T2_CATEGORY_QUARTERLY_REPORT = "40300";  // 季度报告

    private final HttpClient httpClient;

    public HkexDownloader(Path workspaceDir, HttpClient httpClient, ObjectMapper objectMapper) {
        super(workspaceDir, objectMapper);
        this.httpClient = httpClient;
    }

    public FinancialFilingDownloadResult downloadHkFiling(String stockCode, Set<Integer> fiscalYears, boolean allYears,
                                   Set<String> formTypes, boolean allTypes, boolean overwrite) throws Exception {
        logger.info("HK filing download for stock: {}, years: {}, types: {}", stockCode, fiscalYears, formTypes);

        // 标准化股票代码（确保是5位数字）
        String hkexStockCode = normalizeHkexStockCode(stockCode);
        logger.info("Using HKEX stock code: {}", hkexStockCode);

        // 确定搜索年份范围
        Set<Integer> years = fiscalYears;
        if (allYears) {
            int currentYear = java.time.Year.now().getValue();
            years = new LinkedHashSet<>();
            for (int y = currentYear; y >= currentYear - 10; y--) {
                years.add(y);
            }
        }

        // 根据公司配置过滤报告类型（如腾讯不支持季度报告）
        HkCompanyIRConfig.CompanyConfig companyConfig = HkIRConfigLoader.getCompanyConfig(hkexStockCode);
        Set<String> filteredFormTypes = new LinkedHashSet<>();
        for (String formType : formTypes) {
            // 季度报告需要检查公司是否支持
            if (isQuarterlyReportType(formType)) {
                if (companyConfig != null && companyConfig.isSupportsQuarterly()) {
                    filteredFormTypes.add(formType);
                    logger.debug("Stock {} supports quarterly report type: {}", hkexStockCode, formType);
                } else {
                    logger.info("Stock {} does not support quarterly reports, skipping type: {}", hkexStockCode, formType);
                }
            } else {
                // 年报和中期报告默认支持
                filteredFormTypes.add(formType);
            }
        }

        logger.info("Filtered form types for stock {}: {}", hkexStockCode, filteredFormTypes);

        List<DownloadedFiling> downloadedFilings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        // 按年份搜索并下载
        for (int fiscalYear : years) {
            for (String formType : filteredFormTypes) {
                try {
                    DownloadedFiling result = downloadSingleHkFiling(
                            hkexStockCode, fiscalYear, formType, overwrite);
                    if (result != null) {
                        downloadedFilings.add(result);
                    } else {
                        skipped.add(String.format("%s FY%d %s not found", hkexStockCode, fiscalYear, formType));
                    }
                } catch (Exception e) {
                    errors.add(String.format("Failed to download %s %s FY%d: %s",
                            hkexStockCode, formType, fiscalYear, e.getMessage()));
                    logger.debug("Error downloading HK filing {} {} FY{}: {}",
                            hkexStockCode, formType, fiscalYear, e.getMessage());
                }
                Thread.sleep(1000);
            }
        }

        // 更新 manifest
        if (!downloadedFilings.isEmpty()) {
            updateFilingManifest(hkexStockCode, downloadedFilings);
        }

        // 构建结果
        return buildDownloadResultDTO(hkexStockCode, formTypes, years, allYears, downloadedFilings, errors, skipped);
    }

    /**
     * 标准化港股股票代码（转换为5位数字格式）
     */
    private String normalizeHkexStockCode(String inputCode) {
        // 移除前缀如 "HK."
        String code = inputCode.replaceAll("^(?i)HK\\.?", "");

        // 确保是5位数字，不足则前面补零
        if (code.matches("\\d+")) {
            return String.format("%05d", Integer.parseInt(code));
        }
        return code;
    }

    /**
     * 下载单份港股财报
     */
    private DownloadedFiling downloadSingleHkFiling(String stockCode, int fiscalYear,
                                                     String formType, boolean overwrite) throws Exception {

        // 构建搜索参数
        String category = getHkCategoryByFormType(formType);
        String startDate = String.format("%d-01-01", fiscalYear);
        String endDate = String.format("%d-12-31", fiscalYear);

        // 搜索公告
        List<HkAnnouncement> announcements = searchHkAnnouncements(stockCode, startDate, endDate, category);

        if (announcements.isEmpty()) {
            logger.debug("No HK filing found for {} {} FY{}", stockCode, formType, fiscalYear);
            return null;
        }

        // 查找最佳匹配的公告
        HkAnnouncement bestMatch = findBestHkFiling(announcements, formType, fiscalYear);
        if (bestMatch == null) {
            logger.debug("No matching HK filing found for {} {} FY{}", stockCode, formType, fiscalYear);
            return null;
        }

        logger.info("Found matching HK filing: {}", bestMatch.title());

        // documentId 使用公告编号
        String documentId = "fil_hk_" + stockCode + "_" + fiscalYear + "_" + formType;
        Path filingDir = WorkspacePaths.filingsDir(workspaceDir, stockCode, documentId);
        Path metaPath = filingDir.resolve("meta.json");

        // 快速跳过已存在的文件
        if (!overwrite && Files.exists(metaPath)) {
            logger.info("HK filing already exists, skipping download: {}", documentId);
            return null;
        }

        Files.createDirectories(filingDir);

        // 下载财报文件
        DownloadedFile dFile = downloadHkAnnouncement(bestMatch, filingDir, stockCode, overwrite);
        if (dFile == null) {
            throw new IOException("Failed to download HK filing: " + bestMatch.title());
        }

        // 计算 fingerprint
        String fingerprint = calculateDirectoryFingerprint(filingDir);

        // 创建 meta.json
        String reportDate = bestMatch.announcementDate();
        String filingDate = reportDate;

        ObjectNode metaJson = createHkFilingMetaJson(
                documentId, bestMatch.announcementId(), stockCode, formType, fiscalYear,
                reportDate, filingDate, fingerprint, dFile);
        Files.writeString(metaPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metaJson));

        logger.info("Successfully downloaded HK filing to: {}", filingDir.toAbsolutePath());

        return new DownloadedFiling(
                documentId, bestMatch.announcementId(), formType, fiscalYear,
                reportDate, filingDate, fingerprint, false, List.of(dFile)
        );
    }

    /**
     * 搜索港交所公告
     */
    private List<HkAnnouncement> searchHkAnnouncements(String stockCode, String startDate,
                                                        String endDate, String category) throws Exception {
        List<HkAnnouncement> results = new ArrayList<>();

        try {
            // 方法1: 尝试使用公司IR页面（如果是已知公司）
            results = searchFromCompanyIRPage(stockCode, startDate, endDate, category);
            if (!results.isEmpty()) {
                return results;
            }

            // 方法2: 尝试使用港交所披露易HTML搜索页面
            results = searchFromHkexHtmlPage(stockCode, startDate, endDate, category);
            if (!results.isEmpty()) {
                return results;
            }

            // 方法3: 使用备用数据源
            results = searchHkAnnouncementsAlternative(stockCode, startDate, endDate, category);

        } catch (Exception e) {
            logger.debug("HKEX search exception: {}", e.getMessage());
        }

        return results;
    }

    /**
     * 从公司投资者关系页面搜索财报
     */
    private List<HkAnnouncement> searchFromCompanyIRPage(String stockCode, String startDate,
                                                          String endDate, String category) {
        List<HkAnnouncement> results = new ArrayList<>();
        try {
            String irUrl = getIRPageUrl(stockCode);
            if (irUrl == null) {
                logger.debug("No IR page URL found for stock: {}", stockCode);
                return results;
            }

            logger.info("Searching IR page: {}", irUrl);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(irUrl))
                    .header("User-Agent", HKEX_USER_AGENT)
                    .header("Accept", "text/html,*/*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .timeout(java.time.Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                logger.debug("IR page fetched successfully, content length: {}", response.body().length());
                results = parseIRPageHtml(response.body(), stockCode, startDate, endDate, category);
            } else {
                logger.warn("Failed to fetch IR page, status code: {}", response.statusCode());
            }
        } catch (Exception e) {
            logger.warn("IR page search failed for {}: {}", stockCode, e.getMessage());
        }
        return results;
    }

    /**
     * 获取公司IR页面URL（从配置文件读取）
     */
    private String getIRPageUrl(String stockCode) {
        return HkIRConfigLoader.getIrPageUrl(stockCode);
    }

    /**
     * 解析IR页面HTML内容
     */
    private List<HkAnnouncement> parseIRPageHtml(String html, String stockCode, String startDate,
                                                  String endDate, String category) {
        List<HkAnnouncement> results = new ArrayList<>();
        try {
            logger.debug("Parsing IR page, content length: {}", html.length());

            int startYear = Integer.parseInt(startDate.substring(0, 4));
            int endYear = Integer.parseInt(endDate.substring(0, 4));

            // 模式1: 匹配PDF链接，并从URL路径中提取年份信息
            // 腾讯IR页面URL格式: https://static.www.tencent.com/uploads/2025/08/26/xxx.pdf
            java.util.regex.Pattern pdfPattern = java.util.regex.Pattern.compile(
                    "href=\"(https?://[^\"]+/(\\d{4})/(\\d{2})/(\\d{2})/[^\"]+\\.pdf)\"",
                    java.util.regex.Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher matcher = pdfPattern.matcher(html);

            int matchCount = 0;
            while (matcher.find() && matchCount < 50) {
                matchCount++;
                String pdfUrl = matcher.group(1);
                String year = matcher.group(2);
                String month = matcher.group(3);
                String day = matcher.group(4);

                logger.debug("Found PDF URL: {}, year={}, month={}", pdfUrl, year, month);

                // 检查年份范围
                int pdfYear = Integer.parseInt(year);
                if (pdfYear < startYear || pdfYear > endYear) {
                    continue;
                }

                // 获取周围文本内容进行分类判断
                int contextStart = Math.max(0, matcher.start() - 300);
                int contextEnd = Math.min(html.length(), matcher.end() + 300);
                String surrounding = html.substring(contextStart, contextEnd).toLowerCase();

                // 根据月份和文本内容判断财报类型
                boolean matchesCategory = false;
                String title = HkIRConfigLoader.getCompanyName(stockCode) + " " + year + "年";

                int monthInt = Integer.parseInt(month);

                // 根据月份和关键词更准确地判断财报类型
                boolean hasAnnualKeyword = surrounding.contains("年报") ||
                                          surrounding.contains("年度报告") ||
                                          surrounding.contains("annual report");
                boolean hasInterimKeyword = surrounding.contains("中期") ||
                                           surrounding.contains("中报") ||
                                           surrounding.contains("interim") ||
                                           surrounding.contains("半年");
                boolean hasQuarterlyKeyword = surrounding.contains("季度") ||
                                             surrounding.contains("q1") ||
                                             surrounding.contains("q2") ||
                                             surrounding.contains("q3") ||
                                             surrounding.contains("q4");

                // 从配置文件获取公司特定的财报发布规律
                HkCompanyIRConfig.CompanyConfig companyConfig = HkIRConfigLoader.getCompanyConfig(stockCode);
                boolean supportsQuarterly = companyConfig != null && companyConfig.isSupportsQuarterly();

                if (category.equals(T2_CATEGORY_ANNUAL_REPORT)) {
                    // 年报：根据配置的月份或关键词判断，且不是中期报告
                    boolean isAnnualMonth = companyConfig != null ?
                            companyConfig.isAnnualReportMonth(monthInt) :
                            (monthInt >= 3 && monthInt <= 5);
                    if ((isAnnualMonth && !hasInterimKeyword) || (hasAnnualKeyword && !hasInterimKeyword)) {
                        matchesCategory = true;
                        title += "年度报告";
                    }
                } else if (category.equals(T2_CATEGORY_INTERIM_REPORT)) {
                    // 中期报告：根据配置的月份或关键词判断，且不是年报
                    boolean isInterimMonth = companyConfig != null ?
                            companyConfig.isInterimReportMonth(monthInt) :
                            (monthInt >= 7 && monthInt <= 9);
                    if (isInterimMonth || (hasInterimKeyword && !hasAnnualKeyword)) {
                        matchesCategory = true;
                        title += "中期报告";
                    }
                } else if (category.equals(T2_CATEGORY_QUARTERLY_REPORT)) {
                    // 季度报告：仅当公司配置支持时才匹配
                    if (supportsQuarterly) {
                        if (hasQuarterlyKeyword || (!hasAnnualKeyword && !hasInterimKeyword)) {
                            matchesCategory = true;
                            title += "季度报告";
                        }
                    }
                }

                if (matchesCategory) {
                    String dateStr = year + "-" + month + "-" + day;
                    String annId = stockCode + "_" + year + "_" + category + "_" + results.size();
                    logger.info("Found filing from IR page: title={}, url={}", title, pdfUrl);
                    results.add(new HkAnnouncement(annId, stockCode, title, pdfUrl, dateStr, category));
                }
            }

            logger.debug("Found {} matching filings from IR page", results.size());

        } catch (Exception e) {
            logger.warn("Failed to parse IR page HTML: {}", e.getMessage());
        }
        return results;
    }

    /**
     * 从标题中提取年份
     */
    private String extractYearFromTitle(String title) {
        java.util.regex.Pattern yearPattern = java.util.regex.Pattern.compile("(20\\d{2})");
        java.util.regex.Matcher matcher = yearPattern.matcher(title);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 从港交所HTML搜索页面获取结果
     */
    private List<HkAnnouncement> searchFromHkexHtmlPage(String stockCode, String startDate,
                                                         String endDate, String category) {
        List<HkAnnouncement> results = new ArrayList<>();
        try {
            // 构建搜索URL
            String searchUrl = String.format(
                    "https://www1.hkexnews.hk/search/titlesearch.xhtml?lang=zh&searchType=2&t1code=40000&t2Gcode=-2&t2code=-2&stockId=%s&from=%s&to=%s",
                    stockCode, startDate.replace("-", ""), endDate.replace("-", "")
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(searchUrl))
                    .header("User-Agent", HKEX_USER_AGENT)
                    .header("Accept", "text/html,*/*")
                    .header("Referer", "https://www1.hkexnews.hk/search/titlesearch.xhtml?lang=zh")
                    .timeout(java.time.Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                results = parseSearchResultHtml(response.body(), stockCode, category);
            }
        } catch (Exception e) {
            logger.debug("HKEX HTML search failed for {}: {}", stockCode, e.getMessage());
        }
        return results;
    }

    /**
     * 解析港交所搜索结果HTML
     */
    private List<HkAnnouncement> parseSearchResultHtml(String html, String stockCode, String category) {
        List<HkAnnouncement> results = new ArrayList<>();
        try {
            logger.debug("Parsing HKEX search result, content length: {}", html.length());

            // 更宽松的表格行匹配
            java.util.regex.Pattern rowPattern = java.util.regex.Pattern.compile(
                    "<tr[^>]*>(.*?)</tr>",
                    java.util.regex.Pattern.DOTALL
            );
            java.util.regex.Matcher rowMatcher = rowPattern.matcher(html);

            while (rowMatcher.find() && results.size() < 20) {
                String row = rowMatcher.group(1);

                // 提取PDF链接
                java.util.regex.Pattern pdfPattern = java.util.regex.Pattern.compile(
                        "href=\"([^\"]+\\.pdf)\"",
                        java.util.regex.Pattern.CASE_INSENSITIVE
                );
                java.util.regex.Matcher pdfMatcher = pdfPattern.matcher(row);
                String pdfUrl = null;
                if (pdfMatcher.find()) {
                    pdfUrl = pdfMatcher.group(1);
                    if (!pdfUrl.startsWith("http")) {
                        if (pdfUrl.startsWith("/")) {
                            pdfUrl = "https://www1.hkexnews.hk" + pdfUrl;
                        } else {
                            pdfUrl = "https://www1.hkexnews.hk/" + pdfUrl;
                        }
                    }
                }

                // 提取标题 - 更宽松的匹配
                String title = null;
                java.util.regex.Pattern titlePattern = java.util.regex.Pattern.compile(
                        "(?i)(年报|年度报告|Annual Report|中期报告|Interim Report|季度报告|業績公佈|业绩公告)[^<]{0,80}",
                        java.util.regex.Pattern.CASE_INSENSITIVE
                );
                java.util.regex.Matcher titleMatcher = titlePattern.matcher(row);
                if (titleMatcher.find()) {
                    title = titleMatcher.group(0).trim();
                }

                // 如果没有找到标题，尝试从链接文本提取
                if (title == null && pdfUrl != null) {
                    java.util.regex.Pattern linkTextPattern = java.util.regex.Pattern.compile(
                            "href=\"[^\"]+\\.pdf\"[^>]*>([^<]+)</a>",
                            java.util.regex.Pattern.CASE_INSENSITIVE
                    );
                    java.util.regex.Matcher linkTextMatcher = linkTextPattern.matcher(row);
                    if (linkTextMatcher.find()) {
                        title = linkTextMatcher.group(1).trim();
                    }
                }

                // 提取日期
                String date = null;
                java.util.regex.Pattern datePattern = java.util.regex.Pattern.compile(
                        "(\\d{2}/\\d{2}/\\d{4})|(20\\d{6})",
                        java.util.regex.Pattern.CASE_INSENSITIVE
                );
                java.util.regex.Matcher dateMatcher = datePattern.matcher(row);
                if (dateMatcher.find()) {
                    String dateStr = dateMatcher.group(1) != null ? dateMatcher.group(1) : dateMatcher.group(2);
                    if (dateStr.contains("/")) {
                        date = dateStr.substring(6) + "-" + dateStr.substring(3, 5) + "-" + dateStr.substring(0, 2);
                    } else if (dateStr.length() == 8) {
                        date = dateStr.substring(0, 4) + "-" + dateStr.substring(4, 6) + "-" + dateStr.substring(6, 8);
                    }
                }

                if (pdfUrl != null && title != null && !title.isEmpty()) {
                    // 根据分类过滤
                    boolean matchesCategory = false;
                    String lowerTitle = title.toLowerCase();
                    if (category.equals(T2_CATEGORY_ANNUAL_REPORT)) {
                        matchesCategory = lowerTitle.contains("年报") || lowerTitle.contains("年度报告") ||
                                         lowerTitle.contains("annual report");
                    } else if (category.equals(T2_CATEGORY_INTERIM_REPORT)) {
                        matchesCategory = lowerTitle.contains("中期") || lowerTitle.contains("中报") ||
                                         lowerTitle.contains("interim");
                    } else {
                        matchesCategory = lowerTitle.contains("季度") || lowerTitle.contains("q1") ||
                                         lowerTitle.contains("q2") || lowerTitle.contains("q3") ||
                                         lowerTitle.contains("q4");
                    }

                    if (matchesCategory) {
                        String annId = stockCode + "_" + System.currentTimeMillis() + "_" + results.size();
                        logger.debug("Found HKEX filing: title={}, url={}", title, pdfUrl);
                        results.add(new HkAnnouncement(annId, stockCode, title, pdfUrl, date != null ? date : "", category));
                    }
                }
            }

            logger.debug("Found {} filings from HKEX search page", results.size());

        } catch (Exception e) {
            logger.warn("Failed to parse HKEX search result HTML: {}", e.getMessage());
        }
        return results;
    }

    /**
     * 备用搜索方式（使用已知的财报链接模式）
     */
    private List<HkAnnouncement> searchHkAnnouncementsAlternative(String stockCode,
                                                                   String startDate, String endDate, String category) {
        List<HkAnnouncement> results = new ArrayList<>();
        try {
            // 对于一些大公司，可以使用已知的直接PDF链接模式
            int startYear = Integer.parseInt(startDate.substring(0, 4));
            int endYear = Integer.parseInt(endDate.substring(0, 4));

            for (int year = endYear; year >= startYear; year--) {
                List<String> urls = getKnownFilingUrls(stockCode, year, category);
                for (String url : urls) {
                    // 简单检查URL是否可访问
                    try {
                        HttpRequest headRequest = HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .header("User-Agent", HKEX_USER_AGENT)
                                .timeout(java.time.Duration.ofSeconds(10))
                                .method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody())
                                .build();

                        HttpResponse<Void> headResponse = httpClient.send(headRequest,
                                HttpResponse.BodyHandlers.discarding());

                        if (headResponse.statusCode() == 200 || headResponse.statusCode() == 302) {
                            String title = generateFilingTitle(stockCode, year, category);
                            String annId = stockCode + "_" + year + "_" + category;
                            results.add(new HkAnnouncement(annId, stockCode, title, url, year + "-03-31", category));
                            break;  // 每个年份只添加一个有效链接
                        }
                    } catch (Exception e) {
                        // URL不可访问，跳过
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Alternative search failed for {}: {}", stockCode, e.getMessage());
        }
        return results;
    }

    /**
     * 获取已知的财报链接模式（从配置文件读取）
     */
    private List<String> getKnownFilingUrls(String stockCode, int year, String category) {
        List<String> urls = new ArrayList<>();
        HkCompanyIRConfig.CompanyConfig config = HkIRConfigLoader.getCompanyConfig(stockCode);

        if (config != null && config.getPdfUrlPattern() != null) {
            String pattern = config.getPdfUrlPattern();

            if (category.equals(T2_CATEGORY_ANNUAL_REPORT)) {
                // 年报：根据配置的月份生成URL
                if (config.getAnnualReportMonths() != null) {
                    for (int month : config.getAnnualReportMonths()) {
                        for (int day = 1; day <= 31; day++) {
                            urls.add(pattern.replace("{year}", String.valueOf(year))
                                    .replace("{month}", String.format("%02d", month))
                                    .replace("{day}", String.format("%02d", day))
                                    .replace("{filename}", "annual_report_" + year));
                            urls.add(pattern.replace("{year}", String.valueOf(year))
                                    .replace("{month}", String.format("%02d", month))
                                    .replace("{day}", String.format("%02d", day))
                                    .replace("{filename}", "ar_" + year + "_zh"));
                        }
                    }
                }
            } else if (category.equals(T2_CATEGORY_INTERIM_REPORT)) {
                // 中期报告：根据配置的月份生成URL
                if (config.getInterimReportMonths() != null) {
                    for (int month : config.getInterimReportMonths()) {
                        for (int day = 1; day <= 31; day++) {
                            urls.add(pattern.replace("{year}", String.valueOf(year))
                                    .replace("{month}", String.format("%02d", month))
                                    .replace("{day}", String.format("%02d", day))
                                    .replace("{filename}", "interim_report_" + year));
                            urls.add(pattern.replace("{year}", String.valueOf(year))
                                    .replace("{month}", String.format("%02d", month))
                                    .replace("{day}", String.format("%02d", day))
                                    .replace("{filename}", "ir_" + year + "_zh"));
                        }
                    }
                }
            } else if (config.isSupportsQuarterly()) {
                // 季度报告：仅当公司支持时才生成
                for (int q = 1; q <= 4; q++) {
                    int month = q * 3;
                    for (int day = 1; day <= 31; day++) {
                        urls.add(pattern.replace("{year}", String.valueOf(year))
                                .replace("{month}", String.format("%02d", month))
                                .replace("{day}", String.format("%02d", day))
                                .replace("{filename}", "q" + q + "_report_" + year));
                    }
                }
            }
        }

        // 港交所披露易通用模式（虽然搜索API不工作，但直接文件访问可能可用）
        urls.add(String.format("https://www1.hkexnews.hk/listedco/listconews/sehk/%d/%d%d%d/%s.pdf",
                year, year, (int)(Math.random() * 12) + 1, (int)(Math.random() * 28) + 1, stockCode));

        return urls;
    }

    /**
     * 生成财报标题
     */
    private String generateFilingTitle(String stockCode, int year, String category) {
        String companyName = HkIRConfigLoader.getCompanyName(stockCode);
        String periodName;
        if (category.equals(T2_CATEGORY_ANNUAL_REPORT)) {
            periodName = "年度报告";
        } else if (category.equals(T2_CATEGORY_INTERIM_REPORT)) {
            periodName = "中期报告";
        } else {
            periodName = "季度报告";
        }
        return companyName + " " + year + "年" + periodName;
    }

    /**
     * 解析港交所公告JSON响应
     */
    private List<HkAnnouncement> parseHkAnnouncementsJson(String jsonContent, String stockCode) {
        List<HkAnnouncement> results = new ArrayList<>();

        try {
            JsonNode rootNode = objectMapper.readTree(jsonContent);
            JsonNode announcementsNode = rootNode.path("announcements");

            if (announcementsNode.isArray()) {
                for (JsonNode annNode : announcementsNode) {
                    String annId = annNode.path("id").asText();
                    String title = annNode.path("title").asText();
                    String date = annNode.path("releaseTime").asText();
                    String link = annNode.path("pdfLink").asText();
                    String docType = annNode.path("docType").asText();

                    // 确保链接是完整的URL
                    if (!link.startsWith("http")) {
                        if (link.startsWith("/")) {
                            link = HKEX_DOWNLOAD_BASE + link;
                        } else {
                            link = HKEX_DOWNLOAD_BASE + "/" + link;
                        }
                    }

                    results.add(new HkAnnouncement(annId, stockCode, title, link, date, docType));
                }
            }

        } catch (Exception e) {
            logger.debug("Failed to parse HK announcements JSON: {}", e.getMessage());
        }

        return results;
    }

    private String extractHref(String line) {
        int hrefStart = line.indexOf("href=\"");
        if (hrefStart > 0) {
            int hrefEnd = line.indexOf("\"", hrefStart + 6);
            if (hrefEnd > hrefStart) {
                String url = line.substring(hrefStart + 6, hrefEnd);
                if (url.startsWith("/")) {
                    url = HKEX_DOWNLOAD_BASE + url;
                }
                return url;
            }
        }
        return null;
    }

    private String extractTitle(String line) {
        // 简单提取链接文本作为标题
        int titleStart = line.indexOf(">");
        int titleEnd = line.indexOf("</a>");
        if (titleStart > 0 && titleEnd > titleStart) {
            return line.substring(titleStart + 1, titleEnd).trim();
        }
        return "Unknown";
    }

    private String extractDateFromLine(String line) {
        // 查找 YYYYMMDD 或 YYYY-MM-DD 格式的日期
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(20\\d{6}|20\\d{2}-\\d{2}-\\d{2})");
        java.util.regex.Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            String date = matcher.group(1);
            if (date.length() == 8) {
                return date.substring(0, 4) + "-" + date.substring(4, 6) + "-" + date.substring(6, 8);
            }
            return date;
        }
        return LocalDate.now().toString();
    }

    private String extractAnnouncementId(String link) {
        // 从URL中提取编号
        if (link != null) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\d{6,})");
            java.util.regex.Matcher matcher = pattern.matcher(link);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return String.valueOf(System.currentTimeMillis());
    }

    /**
     * 下载港交所公告文件
     */
    private DownloadedFile downloadHkAnnouncement(HkAnnouncement announcement, Path filingDir,
                                                  String stockCode, boolean overwrite) throws Exception {
        String downloadUrl = announcement.link();
        String fileName = new java.io.File(downloadUrl).getName();

        // 确保文件名有 .pdf 后缀
        if (!fileName.toLowerCase().endsWith(".pdf")) {
            fileName = fileName + ".pdf";
        }

        Path filePath = filingDir.resolve(fileName);
        byte[] content;

        // 检查是否已存在
        if (!overwrite && Files.exists(filePath)) {
            logger.info("Reusing existing HK filing file: {}", filePath.toAbsolutePath());
            content = Files.readAllBytes(filePath);
        } else {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .header("User-Agent", HKEX_USER_AGENT)
                    .header("Accept", "application/pdf,*/*")
                    .header("Referer", "https://www1.hkexnews.hk/")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                throw new IOException(String.format("Failed to download %s (Status: %d)",
                        announcement.title(), response.statusCode()));
            }

            content = response.body();
            Files.write(filePath, content);
        }

        // 计算 SHA256
        String sha256 = calculateSHA256(content);
        long fileSize = content.length;

        String now = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        return new DownloadedFile(
                fileName,
                "local://portfolio/" + stockCode + "/filings/" + filingDir.getFileName() + "/" + fileName,
                sha256, fileSize, downloadUrl, "application/pdf", now
        );
    }

    /**
     * 查找最佳匹配的公告
     */
    private HkAnnouncement findBestHkFiling(List<HkAnnouncement> announcements, String formType, int fiscalYear) {
        if (announcements.isEmpty()) {
            return null;
        }

        int bestScore = 0;
        HkAnnouncement bestAnnouncement = null;

        for (HkAnnouncement ann : announcements) {
            String title = ann.title().toLowerCase(Locale.ROOT);
            int score = 0;

            // 根据财报类型评分
            switch (formType.toUpperCase(Locale.ROOT)) {
                case "FY" -> {
                    if (title.contains("年度报告") || title.contains("年报") ||
                        title.contains("annual report") || title.contains("results for the year")) {
                        score += 100;
                    }
                    if (title.contains("全年业绩") || title.contains("年全年业绩")) score += 80;
                    if (title.contains("摘要")) score -= 30;
                }
                case "H1" -> {
                    if (title.contains("中期报告") || title.contains("半年报") ||
                        title.contains("interim report") || title.contains("中期报告")) {
                        score += 100;
                    }
                    if (title.contains("截至六月三十日")) score += 80;
                }
                case "Q1", "Q2", "Q3", "Q4" -> {
                    if (title.contains("季度报告") || title.contains("季度业绩")) {
                        score += 80;
                    }
                    String qName = formType.replace("Q", "第") + "季度";
                    if (title.contains(qName)) score += 100;
                }
            }

            // 包含年份加分
            if (title.contains(String.valueOf(fiscalYear))) score += 50;
            if (title.contains(String.valueOf(fiscalYear + 1))) score += 30;

            // 英文公告减分
            if (title.matches("[a-zA-Z\\s]+") && !title.matches(".*[\\u4e00-\\u9fa5].*")) {
                score -= 20;
            }

            // 优先选择高评分的
            if (score > bestScore) {
                bestScore = score;
                bestAnnouncement = ann;
            }
        }

        // 最低分门槛
        return bestScore >= 50 ? bestAnnouncement : null;
    }

    /**
     * 根据财报类型获取港交所分类代码
     */
    private String getHkCategoryByFormType(String formType) {
        return switch (formType.toUpperCase(Locale.ROOT)) {
            case "FY" -> T2_CATEGORY_ANNUAL_REPORT;     // 年报
            case "H1" -> T2_CATEGORY_INTERIM_REPORT;    // 中期/半年度报告
            case "Q1", "Q2", "Q3", "Q4" -> T2_CATEGORY_QUARTERLY_REPORT;  // 季度报告
            default -> T2_CATEGORY_ANNUAL_REPORT;
        };
    }

    /**
     * 创建港股财报的meta.json
     */
    private ObjectNode createHkFilingMetaJson(String documentId, String announcementId,
                                               String stockCode, String formType, int fiscalYear,
                                               String reportDate, String filingDate, String fingerprint,
                                               DownloadedFile primaryFile) {
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("documentId", documentId);
        meta.put("announcementId", announcementId);
        meta.put("ticker", stockCode);
        meta.put("formType", formType);
        meta.put("fiscalYear", fiscalYear);
        meta.put("reportDate", reportDate);
        meta.put("filingDate", filingDate);
        meta.put("source", "hkex");
        meta.put("fingerprint", fingerprint);
        meta.put("downloadTimestamp", Instant.now().toString());

        // 主文件信息
        ObjectNode fileNode = objectMapper.createObjectNode();
        fileNode.put("name", primaryFile.getName());
        fileNode.put("sha256", primaryFile.getSha256());
        fileNode.put("size", primaryFile.getSize());
        fileNode.put("contentType", primaryFile.getContentType());
        fileNode.put("sourceUrl", primaryFile.getSourceUrl());
        meta.set("primaryFile", fileNode);

        return meta;
    }

    /**
     * 判断是否为季度报告类型
     */
    private boolean isQuarterlyReportType(String reportType) {
        if (reportType == null) {
            return false;
        }
        String type = reportType.toUpperCase(Locale.ROOT);
        return type.equals("Q1") || type.equals("Q2") || type.equals("Q3") || type.equals("Q4");
    }

    /**
     * 港交所公告记录
     */
    private record HkAnnouncement(
            String announcementId,
            String stockCode,
            String title,
            String link,
            String announcementDate,
            String announcementType
    ) {}
}
