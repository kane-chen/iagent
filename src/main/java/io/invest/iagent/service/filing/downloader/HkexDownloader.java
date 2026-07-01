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
     * 从公司投资者关系页面搜索财报。
     *
     * 优先级：
     *   - 请求"季度报告"分类时，若配置了 quarterlyPageUrl，先抓这个页面；如果没抓到再回退到主 IR 页
     *   - 其他情况（年报 / 中期），只抓主 IR 页
     * 有些公司（如腾讯）季度业绩单独有一页 quarter-result.html，其结构和主页一致（都是
     * 路径分段日期 URL），所以可以复用同一份解析器 {@link #parseIRPageHtml}。
     */
    private List<HkAnnouncement> searchFromCompanyIRPage(String stockCode, String startDate,
                                                          String endDate, String category) {
        List<HkAnnouncement> results = new ArrayList<>();
        try {
            // 分类是季度报告且有专用页时，先试专用页
            boolean isQuarterly = T2_CATEGORY_QUARTERLY_REPORT.equals(category);
            if (isQuarterly) {
                String qUrl = HkIRConfigLoader.getQuarterlyPageUrl(stockCode);
                if (qUrl != null && !qUrl.isBlank()) {
                    List<HkAnnouncement> qResults = fetchAndParseIRPage(qUrl, stockCode, startDate, endDate, category);
                    if (!qResults.isEmpty()) {
                        return qResults;
                    }
                }
            }

            String irUrl = getIRPageUrl(stockCode);
            if (irUrl == null) {
                logger.debug("No IR page URL found for stock: {}", stockCode);
                return results;
            }
            results = fetchAndParseIRPage(irUrl, stockCode, startDate, endDate, category);
        } catch (Exception e) {
            logger.warn("IR page search failed for {}: {}", stockCode, e.getMessage());
        }
        return results;
    }

    /**
     * 抓取单个 IR 页面并解析 —— 供主 IR 页和季度业绩页复用。
     */
    private List<HkAnnouncement> fetchAndParseIRPage(String pageUrl, String stockCode, String startDate,
                                                     String endDate, String category) {
        try {
            logger.info("Searching IR page: {}", pageUrl);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(pageUrl))
                    .header("User-Agent", HKEX_USER_AGENT)
                    .header("Accept", "text/html,*/*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .timeout(java.time.Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                logger.debug("IR page fetched successfully, content length: {}", response.body().length());
                return parseIRPageHtml(response.body(), stockCode, startDate, endDate, category);
            } else {
                logger.warn("Failed to fetch IR page {}, status code: {}", pageUrl, response.statusCode());
            }
        } catch (Exception e) {
            logger.warn("IR page fetch failed for {}: {}", pageUrl, e.getMessage());
        }
        return new ArrayList<>();
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

            // 模式1: 匹配 <a> 标签及其内部文本；从 URL 路径 /YYYY/MM/DD/ 提取发布日期
            //   腾讯主 IR 页 URL 格式: https://static.www.tencent.com/uploads/2025/08/26/xxx.pdf
            //   腾讯季度页/公告页 URL 同上，<a> 内部结构不同：
            //     - quarter-result.html 结构简单：<a>业绩新闻</a>
            //     - announcements.html 结构含嵌套 <span>/<label>/<h3>：
            //         <a><p><span>2026.05.13</span><label>截至...业绩公布</label></p></a>
            //   所以捕获组用 DOTALL + 到 </a> 结束，再由 stripHtmlTags 统一去标签
            java.util.regex.Pattern pdfPattern = java.util.regex.Pattern.compile(
                    "<a[^>]*href=\"(https?://[^\"]+/(\\d{4})/(\\d{2})/(\\d{2})/[^\"]+\\.pdf)\"[^>]*>(.*?)</a>",
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL
            );
            java.util.regex.Matcher matcher = pdfPattern.matcher(html);

            // 季度报告：一次季度日期通常挂 3 个 PDF（业绩新闻 / 业绩演示 / 港交所公告），
            // 我们只想要"港交所公告"版本（正式披露）。先按 (year, quarter) 分组收集所有候选，
            // 最后每组选最优（优先 港交所公告，退而其次业绩新闻）。
            Map<String, QuarterlyCandidate> quarterlyBest = new java.util.LinkedHashMap<>();

            HkCompanyIRConfig.CompanyConfig companyConfig = HkIRConfigLoader.getCompanyConfig(stockCode);
            boolean supportsQuarterly = companyConfig != null && companyConfig.isSupportsQuarterly();

            int matchCount = 0;
            // announcements.html 可能有 1000+ <a> 标签，把上限调高一些
            while (matcher.find() && matchCount < 2000) {
                matchCount++;
                String pdfUrl = matcher.group(1);
                String year = matcher.group(2);
                String month = matcher.group(3);
                String day = matcher.group(4);
                String rawInner = matcher.group(5) == null ? "" : matcher.group(5);
                String linkText = stripHtmlTags(rawInner);

                logger.debug("Found PDF URL: {}, year={}, month={}, linkText={}", pdfUrl, year, month, linkText);

                // 检查年份范围
                int pdfYear = Integer.parseInt(year);
                if (pdfYear < startYear || pdfYear > endYear) {
                    continue;
                }

                // 获取周围文本内容进行分类判断（用于年报/中期页的兼容判断）
                int contextStart = Math.max(0, matcher.start() - 300);
                int contextEnd = Math.min(html.length(), matcher.end() + 300);
                String surrounding = html.substring(contextStart, contextEnd).toLowerCase();

                boolean matchesCategory = false;
                String title = HkIRConfigLoader.getCompanyName(stockCode) + " " + year + "年";

                int monthInt = Integer.parseInt(month);

                // 关键词
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

                if (category.equals(T2_CATEGORY_ANNUAL_REPORT)) {
                    boolean isAnnualMonth = companyConfig != null ?
                            companyConfig.isAnnualReportMonth(monthInt) :
                            (monthInt >= 3 && monthInt <= 5);
                    if ((isAnnualMonth && !hasInterimKeyword) || (hasAnnualKeyword && !hasInterimKeyword)) {
                        matchesCategory = true;
                        title += "年度报告";
                    }
                } else if (category.equals(T2_CATEGORY_INTERIM_REPORT)) {
                    boolean isInterimMonth = companyConfig != null ?
                            companyConfig.isInterimReportMonth(monthInt) :
                            (monthInt >= 7 && monthInt <= 9);
                    if (isInterimMonth || (hasInterimKeyword && !hasAnnualKeyword)) {
                        matchesCategory = true;
                        title += "中期报告";
                    }
                } else if (category.equals(T2_CATEGORY_QUARTERLY_REPORT)) {
                    if (!supportsQuarterly) continue;
                    // 用配置的 "Q:month" 表把发布月份映射为具体季度
                    String quarterCode = companyConfig != null
                            ? companyConfig.quarterFromReleaseMonth(monthInt) : null;
                    // 兜底：仅当配置未提供 quarterlyReportMonths 时才使用常见月份映射；
                    // 若配置显式声明了 ["Q1:5", "Q3:11"]（如腾讯），说明 8 月发布的属于
                    // 中期报告而不是 Q2 季度业绩，此时不做兜底避免误抓。
                    boolean hasQuarterlyMonthConfig = companyConfig != null
                            && companyConfig.getQuarterlyReportMonths() != null
                            && !companyConfig.getQuarterlyReportMonths().isEmpty();
                    if (quarterCode == null && !hasQuarterlyMonthConfig) {
                        switch (monthInt) {
                            case 5 -> quarterCode = "Q1";
                            case 8 -> quarterCode = "Q2";
                            case 11 -> quarterCode = "Q3";
                            case 3 -> quarterCode = "Q4"; // 通常与年报同批发布
                        }
                    }
                    if (quarterCode == null) continue;

                    // 会计年推断：3 月发的 Q4 属于上一会计年（次年发布前一年 Q4）
                    int fiscalYear = "Q4".equals(quarterCode) ? pdfYear - 1 : pdfYear;
                    if (fiscalYear < startYear || fiscalYear > endYear) continue;

                    // 一日多个 PDF：按 linkText 打分选最优 —— 优先 港交所公告（正式披露），
                    // 其次 业绩新闻 / 业绩公告 / 季度业绩，最差 业绩演示 / 电话会议记录
                    int score = quarterlyLinkScore(linkText);
                    if (score <= 0) continue; // 演示 / 会议记录 直接跳过

                    String key = fiscalYear + "_" + quarterCode;
                    QuarterlyCandidate current = quarterlyBest.get(key);
                    if (current == null || score > current.score) {
                        String q = quarterCode;
                        String dateStr = year + "-" + month + "-" + day;
                        String annTitle = HkIRConfigLoader.getCompanyName(stockCode)
                                + " " + fiscalYear + "年第" + q.substring(1) + "季度业绩 " + q;
                        String annId = stockCode + "_" + fiscalYear + "_" + category + "_" + q;
                        quarterlyBest.put(key, new QuarterlyCandidate(
                                new HkAnnouncement(annId, stockCode, annTitle, pdfUrl, dateStr, category),
                                score));
                    }
                    // 季度报告的匹配走单独 bucket，不走下面的通用 results.add
                    continue;
                }

                if (matchesCategory) {
                    String dateStr = year + "-" + month + "-" + day;
                    String annId = stockCode + "_" + year + "_" + category + "_" + results.size();
                    logger.info("Found filing from IR page: title={}, url={}", title, pdfUrl);
                    results.add(new HkAnnouncement(annId, stockCode, title, pdfUrl, dateStr, category));
                }
            }

            // 把季度最优候选统一加入结果
            for (QuarterlyCandidate qc : quarterlyBest.values()) {
                logger.info("Found quarterly filing from IR page: title={}, url={}",
                        qc.announcement.title(), qc.announcement.link());
                results.add(qc.announcement);
            }

            // 模式2: PDF URL 直接以 YYYYMMDD 开头作为文件名（美团/todayir.com 等托管方）
            //   例: https://media-meituan.todayir.com/20260424065602215412120050_tc.pdf
            // 这种页面的 PDF 链接旁边通常有一段固定 200-400 字节的标题/日期 HTML 片段
            // 或者页面里夹带的结构化 JSON 数据有 "title"/"year"/"date" 字段
            int beforeFlatPattern = results.size();
            parseIRPageFlatPdfLinks(html, stockCode, startYear, endYear, category, results);
            logger.debug("Flat-pdf pattern added {} additional filings",
                    results.size() - beforeFlatPattern);

            logger.debug("Found {} matching filings from IR page", results.size());

        } catch (Exception e) {
            logger.warn("Failed to parse IR page HTML: {}", e.getMessage());
        }
        return results;
    }

    /**
     * 给腾讯季度业绩页/公告页里的 &lt;a&gt; 内部文字打分：
     *   港交所公告 → 100  （官方规范披露，quarter-result.html 使用；含分部数据 — 首选）
     *   业绩公布 / 业绩公告 / 三个月业绩 → 80  （announcements.html 使用；正式披露公告）
     *   业绩新闻 / 季度业绩 / 业绩报告 → 60
     *   业绩演示 / 演示 / 记录 / 电话会议 → 0  （不含结构化财务表格，跳过）
     *   其他中性内容 → 0（announcements.html 有大量"翌日披露报表/代表委任表格/通函"等非业绩公告 PDF，
     *                   不能用默认高分放进来，否则会污染季度分类；只有以上白名单标签才认为是"季度业绩" 类型）
     */
    private int quarterlyLinkScore(String linkText) {
        if (linkText == null) return 0;
        if (linkText.contains("港交所公告") || linkText.contains("港交所")) return 100;
        if (linkText.contains("业绩公布") || linkText.contains("业绩公告")
                || linkText.contains("三个月业绩") || linkText.contains("年度全年业绩")) return 80;
        if (linkText.contains("业绩新闻") || linkText.contains("季度业绩")
                || linkText.contains("业绩报告")) return 60;
        // 明确的非业绩内容 → 直接拒
        if (linkText.contains("演示") || linkText.contains("电话会议")
                || linkText.contains("记录") || linkText.contains("会议")) return 0;
        // 其他一律拒 —— announcements.html 上大部分 PDF 都是无关的（月报表、通函等），
        // 我们只要显式命名为业绩的
        return 0;
    }

    /**
     * 去掉 HTML 标签，把嵌套结构（如 &lt;a&gt;&lt;span&gt;date&lt;/span&gt;&lt;label&gt;title&lt;/label&gt;&lt;/a&gt;）
     * 折叠成纯文本 "date title"。
     */
    private String stripHtmlTags(String s) {
        if (s == null) return "";
        // 去 HTML 注释
        String noComments = s.replaceAll("(?s)<!--.*?-->", " ");
        // 去所有标签
        String noTags = noComments.replaceAll("<[^>]+>", " ");
        // 折叠空白
        return noTags.replaceAll("\\s+", " ").trim();
    }

    /** 季度候选缓存 —— 一个 (fiscalYear, quarter) 只保留分数最高的 PDF。 */
    private static final class QuarterlyCandidate {
        final HkAnnouncement announcement;
        final int score;
        QuarterlyCandidate(HkAnnouncement a, int s) { this.announcement = a; this.score = s; }
    }

    /**
     * 模式2：识别"扁平 PDF URL"（文件名带 YYYYMMDD 时间戳前缀的托管方），
     * 例如美团/todayir.com 的链接：
     *   https://media-meituan.todayir.com/20260424065602215412120050_tc.pdf
     *
     * 这类页面里 PDF 链接附近通常有以下信号之一：
     *   - HTML 元素 <span class="iy">2026-04-24</span> 或 <p class="iF">2026-04-24</p>
     *   - 嵌入的 JSON 片段 "title":"年报","link":"...pdf","year":"2026","date":"2026-04-24"
     *
     * 为减少误抓，本方法做如下取舍：
     *   - 排除标题里包含"职权范围/披露报表/社会责任/邀请/通函/章程"等非财报类关键词的 PDF
     *   - 强制要求链接附近能找到年/月/日，否则放弃这条
     *   - 由调用方的 category 决定保留哪一类（年报 / 中期 / 季度）
     */
    private void parseIRPageFlatPdfLinks(String html, String stockCode, int startYear, int endYear,
                                         String category, List<HkAnnouncement> results) {
        // 关键词集合：用于把 PDF 上下文按"标题暗示的报告类型"分类
        // 这里都是中文/英文中性词；非财报类（治理/CSR/通函/股份回购）会在标题里被剔除。
        final String[] excludeKeywords = {
                "职权范围", "披露报表", "通函", "章程", "回购", "邀请",
                "企业管治", "委员会", "授权",
                "csr", "社会责任",
                "circular", "notice", "proxy", "appointment"
        };

        // 匹配单个 PDF URL（含整段 href 起止位置，便于取上下文）
        java.util.regex.Pattern flatPdfPattern = java.util.regex.Pattern.compile(
                "href=\"(https?://[^\"]+/(\\d{8})[A-Za-z0-9_]*\\.pdf)\"",
                java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher = flatPdfPattern.matcher(html);

        // 跟踪同一 URL 不再重复处理（多种模式可能同时匹配）
        java.util.Set<String> seenUrls = new java.util.HashSet<>();
        for (HkAnnouncement existing : results) {
            seenUrls.add(existing.link());
        }

        HkCompanyIRConfig.CompanyConfig companyConfig = HkIRConfigLoader.getCompanyConfig(stockCode);
        boolean supportsQuarterly = companyConfig != null && companyConfig.isSupportsQuarterly();

        int matchCount = 0;
        while (matcher.find() && matchCount < 200) {
            matchCount++;
            String pdfUrl = matcher.group(1);
            if (seenUrls.contains(pdfUrl)) continue;

            // 文件名里的 YYYYMMDD —— 但这其实是"发布时间戳"，不一定等于会计期。
            // 例如 20250428... 的是 FY2024 年报。所以这里只用它作"链接活跃年份"参考。
            String fileDate = matcher.group(2);

            // 关键：先尝试提取该链接所属的 JSON 记录（自包含的 { ... link ... } 花括号块）
            // 这是最可靠的信号 —— 明确的 title/year/date；如果拿到就以它为准，
            // 不再用相邻 HTML 元素的关键词，以避免同一列表里前后 sibling 词汇串扰
            //（比如 "年报"条目紧挨"中期报告"条目，两者的 400 字节 window 会互相污染）。
            FlatRecord rec = extractFlatRecordFromJson(html, pdfUrl);

            String surroundingLower;
            if (rec != null && rec.title != null) {
                // 只用本记录的 title 判断类型 —— 忽略邻近 HTML
                surroundingLower = rec.title.toLowerCase(Locale.ROOT);
            } else {
                // 没有 JSON 数据：退回到相邻 HTML 元素（<h6 class="ix">...）
                int contextStart = Math.max(0, matcher.start() - 400);
                int contextEnd = Math.min(html.length(), matcher.end() + 400);
                String context = html.substring(contextStart, contextEnd);
                surroundingLower = context.toLowerCase(Locale.ROOT);
                rec = extractFlatRecordFromHtml(context, pdfUrl);
            }

            // 排除非财报类（用 title / 记录 subTitle）
            boolean isExcluded = false;
            String excludeScope = rec != null && rec.title != null ? rec.title.toLowerCase(Locale.ROOT)
                                                                   : surroundingLower;
            for (String kw : excludeKeywords) {
                if (excludeScope.contains(kw.toLowerCase(Locale.ROOT))) {
                    isExcluded = true;
                    break;
                }
            }
            if (isExcluded) continue;

            if (rec == null || rec.fiscalYear <= 0) {
                rec = rec == null ? new FlatRecord() : rec;
                if (rec.fiscalYear <= 0 && fileDate != null && fileDate.length() >= 4) {
                    try { rec.fiscalYear = Integer.parseInt(fileDate.substring(0, 4)); }
                    catch (NumberFormatException ignored) {}
                }
            }
            if (rec.fiscalYear <= 0) continue;
            if (rec.fiscalYear < startYear || rec.fiscalYear > endYear) continue;

            String lowerTitle = rec.title == null ? "" : rec.title.toLowerCase(Locale.ROOT);
            boolean isAnnualEntry = lowerTitle.equals("年报") || lowerTitle.contains("年度报告")
                    || lowerTitle.contains("annual report");
            boolean isInterimEntry = lowerTitle.equals("中期报告") || lowerTitle.contains("interim");
            boolean isQuarterlyEntry = lowerTitle.contains("三个月") || lowerTitle.contains("季度");

            // 识别具体季度（Q1/Q2/Q3/Q4）—— 来自"截至YYYY年X月X日止三个月业绩公告"
            // Q1 = 3月, Q2 = 6月, Q3 = 9月, Q4 = 12月
            String quarterCode = null;
            if (isQuarterlyEntry && rec.title != null) {
                java.util.regex.Matcher qm = java.util.regex.Pattern
                        .compile("(\\d{1,2})月(?:\\d{1,2}日)?止三个月")
                        .matcher(rec.title);
                if (qm.find()) {
                    int endMonth = Integer.parseInt(qm.group(1));
                    switch (endMonth) {
                        case 3 -> quarterCode = "Q1";
                        case 6 -> quarterCode = "Q2";
                        case 9 -> quarterCode = "Q3";
                        case 12 -> quarterCode = "Q4";
                    }
                }
            }

            boolean matchesCategory = false;
            String displayTitle = HkIRConfigLoader.getCompanyName(stockCode) + " " + rec.fiscalYear + "年";

            if (category.equals(T2_CATEGORY_ANNUAL_REPORT)) {
                if (isAnnualEntry) {
                    matchesCategory = true;
                    displayTitle += "年度报告";
                }
            } else if (category.equals(T2_CATEGORY_INTERIM_REPORT)) {
                if (isInterimEntry) {
                    matchesCategory = true;
                    displayTitle += "中期报告";
                }
            } else if (category.equals(T2_CATEGORY_QUARTERLY_REPORT)) {
                if (supportsQuarterly && isQuarterlyEntry && quarterCode != null) {
                    matchesCategory = true;
                    // 关键：display title 里放明确的 QN，配合 findBestHkFiling 的
                    // formType 打分（它会检查 title 里的"第N季度"或原始 QN 关键词）
                    displayTitle += "第" + quarterCode.substring(1) + "季度报告 " + quarterCode;
                }
            }

            if (!matchesCategory) continue;

            String annId = stockCode + "_" + rec.fiscalYear + "_" + category + "_" + results.size();
            logger.info("Found flat-pattern filing from IR page: title={}, url={}",
                    displayTitle, pdfUrl);
            results.add(new HkAnnouncement(annId, stockCode, displayTitle, pdfUrl,
                    rec.date != null ? rec.date : fileDateToIso(fileDate), category));
            seenUrls.add(pdfUrl);
        }
    }

    /**
     * 在整份 HTML 里找一个包含 pdfUrl 的 JSON 记录（用花括号定界，
     * 形如 {"id":...,"title":"年报","link":"...pdf","year":"2026","date":"2026-04-24"}）。
     * 用花括号平衡扫描，避免和相邻记录的 title/year 混起。
     *
     * URL 在页面里通常出现两次：一次是 HTML {@code <a href>}，一次是嵌入 JSON 的 {@code "link":"...pdf"}。
     * 我们只对后者感兴趣，所以在扫描时匹配 {@code "link":"<url>"} 的形式。
     */
    private FlatRecord extractFlatRecordFromJson(String html, String pdfUrl) {
        // 找 "link":"pdfUrl" 出现的位置（可能有多处，取第一个 —— 因为同一记录只会有一处）
        String linkMarker = "\"link\":\"" + pdfUrl + "\"";
        int idx = html.indexOf(linkMarker);
        if (idx < 0) return null;

        // 向前扫描找到最近的 '{'（跨过的字符不能超过 800，避免绕过整个上一条记录）
        int lookback = Math.max(0, idx - 800);
        int open = -1;
        int depth = 0;
        for (int i = idx - 1; i >= lookback; i--) {
            char c = html.charAt(i);
            if (c == '}') depth++;
            else if (c == '{') {
                if (depth == 0) { open = i; break; }
                depth--;
            }
        }
        if (open < 0) return null;

        // 向后扫描找到匹配的 '}'
        int close = -1;
        depth = 0;
        int lookForward = Math.min(html.length(), idx + linkMarker.length() + 400);
        for (int i = open; i < lookForward; i++) {
            char c = html.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) { close = i; break; }
            }
        }
        if (close < 0) return null;

        String jsonBlock = html.substring(open, close + 1);
        // 快速健全性检查：块里必须包含我们要的 pdfUrl
        if (!jsonBlock.contains(pdfUrl)) return null;

        FlatRecord rec = new FlatRecord();
        java.util.regex.Matcher mTitle = java.util.regex.Pattern
                .compile("\"title\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(jsonBlock);
        if (mTitle.find()) rec.title = mTitle.group(1);

        java.util.regex.Matcher mSubTitle = java.util.regex.Pattern
                .compile("\"subTitle\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(jsonBlock);
        if (mSubTitle.find() && (rec.title == null || rec.title.isBlank())) {
            rec.title = mSubTitle.group(1);
        }

        java.util.regex.Matcher mDate = java.util.regex.Pattern
                .compile("\"date\"\\s*:\\s*\"(\\d{4}-\\d{2}-\\d{2})\"")
                .matcher(jsonBlock);
        if (mDate.find()) rec.date = mDate.group(1);

        java.util.regex.Matcher mYear = java.util.regex.Pattern
                .compile("\"year\"\\s*:\\s*\"?(\\d{4})\"?")
                .matcher(jsonBlock);
        if (mYear.find()) {
            try { rec.fiscalYear = Integer.parseInt(mYear.group(1)); } catch (NumberFormatException ignored) {}
        }

        // 关键调整：Meituan 的 JSON year 是"发布年"而不是"会计年"。
        // 对于年报，发布日期在次年 3-4 月：release=2025-04-28 的年报是 FY 2024。
        // 一般规律：
        //   title="年报" 且 release month 在 1-6 → fiscalYear = releaseYear - 1
        //   title="中期报告" → fiscalYear = releaseYear（发布于 8-9 月，报告涵盖当年 1-6 月）
        //   title="截至 X月 Y日止三个月业绩公告"：
        //     Q1 (3月) 发布于同年 5-6 月 → fiscalYear = releaseYear
        //     Q3 (9月) 发布于同年 11 月 → fiscalYear = releaseYear
        //     Q4 (12月) 发布于次年 3 月 → fiscalYear = releaseYear - 1
        //     Q2 (6月) 发布于同年 8 月 → fiscalYear = releaseYear
        if (rec.title != null && rec.date != null && rec.date.length() >= 7) {
            try {
                int releaseYear = Integer.parseInt(rec.date.substring(0, 4));
                int releaseMonth = Integer.parseInt(rec.date.substring(5, 7));
                String t = rec.title.toLowerCase(Locale.ROOT);
                if (t.contains("年报") || t.contains("年度报告") || t.contains("annual report")) {
                    // 年报：发布月 1-6 → fiscalYear = releaseYear - 1
                    rec.fiscalYear = releaseMonth <= 6 ? releaseYear - 1 : releaseYear;
                } else if (rec.title.contains("三个月") || rec.title.contains("季度")) {
                    // 从标题里的月份判断
                    java.util.regex.Matcher qm = java.util.regex.Pattern
                            .compile("(\\d{1,2})月(?:\\d{1,2}日)?止三个月")
                            .matcher(rec.title);
                    if (qm.find()) {
                        int endMonth = Integer.parseInt(qm.group(1));
                        // 报告结束月对应的会计年：Q4(12月) 结束的报告，发布月在次年 3 月，fiscalYear=releaseYear-1
                        // 其他 Q1/Q2/Q3 都发布于同年
                        rec.fiscalYear = (endMonth == 12) ? releaseYear - 1 : releaseYear;
                    }
                }
            } catch (NumberFormatException ignored) {}
        }

        return (rec.title != null || rec.date != null || rec.fiscalYear > 0) ? rec : null;
    }

    /**
     * 备用：从紧邻的 HTML 元素里提取 title/date（当没有嵌入 JSON 时）。
     */
    private FlatRecord extractFlatRecordFromHtml(String context, String pdfUrl) {
        FlatRecord rec = new FlatRecord();
        java.util.regex.Matcher mH = java.util.regex.Pattern
                .compile("<h\\d[^>]*>([^<]+?)</h\\d>")
                .matcher(context);
        if (mH.find()) rec.title = mH.group(1).trim();

        java.util.regex.Matcher mSpanDate = java.util.regex.Pattern
                .compile("<(?:span|p)[^>]*>(\\d{4}-\\d{2}-\\d{2})</(?:span|p)>")
                .matcher(context);
        if (mSpanDate.find()) rec.date = mSpanDate.group(1);

        if (rec.title == null) {
            java.util.regex.Matcher mTitleZh = java.util.regex.Pattern
                    .compile("<span[^>]*>([^<]{2,80}?(?:年报|中期报告|季度|三个月)[^<]{0,60}?)</span>")
                    .matcher(context);
            if (mTitleZh.find()) rec.title = mTitleZh.group(1).trim();
        }

        // 推断 fiscalYear
        if (rec.title != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(20\\d{2})").matcher(rec.title);
            if (m.find()) {
                try { rec.fiscalYear = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
            }
        }
        if (rec.fiscalYear <= 0 && rec.date != null && rec.date.length() >= 4) {
            try {
                int releaseYear = Integer.parseInt(rec.date.substring(0, 4));
                int releaseMonth = rec.date.length() >= 7 ? Integer.parseInt(rec.date.substring(5, 7)) : 0;
                String t = rec.title == null ? "" : rec.title;
                if (t.contains("年报") || t.toLowerCase(Locale.ROOT).contains("annual")) {
                    rec.fiscalYear = (releaseMonth >= 1 && releaseMonth <= 6) ? releaseYear - 1 : releaseYear;
                } else {
                    rec.fiscalYear = releaseYear;
                }
            } catch (NumberFormatException ignored) {}
        }
        return rec;
    }

    private String fileDateToIso(String yyyymmdd) {
        if (yyyymmdd == null || yyyymmdd.length() < 8) return "";
        return yyyymmdd.substring(0, 4) + "-" + yyyymmdd.substring(4, 6) + "-" + yyyymmdd.substring(6, 8);
    }

    /** PDF 链接上下文里提取出的字段（任何一个为空都允许，后续按需推断）。 */
    private static class FlatRecord {
        String title;
        String date;       // YYYY-MM-DD 发布日期
        int fiscalYear;    // 会计年度
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
                    // 若标题里包含季度/中期字样，说明这条不是年报，减到负分让门槛过滤掉
                    if (title.contains("季度") || title.contains("三个月") || title.contains("中期")
                            || title.contains("interim")) {
                        score -= 200;
                    }
                }
                case "H1" -> {
                    if (title.contains("中期报告") || title.contains("半年报") ||
                        title.contains("interim report") || title.contains("中期报告")) {
                        score += 100;
                    }
                    if (title.contains("截至六月三十日")) score += 80;
                    if (title.contains("季度") || title.contains("三个月") || title.contains("年报")
                            || title.contains("年度报告") || title.contains("annual")) {
                        score -= 200;
                    }
                }
                case "Q1", "Q2", "Q3", "Q4" -> {
                    String qName = formType.replace("Q", "第") + "季度";
                    boolean isQuarterly = title.contains("季度报告") || title.contains("季度业绩")
                            || title.contains("三个月");
                    if (isQuarterly) score += 50;
                    // 必须能识别出具体是哪个季度 —— 从标题里抓 "第N季度" 或 "M月X日止三个月"
                    String detectedQ = detectQuarterFromTitle(ann.title());
                    if (detectedQ != null && detectedQ.equalsIgnoreCase(formType)) {
                        score += 150;
                    } else if (detectedQ != null) {
                        // 抓到了 quarter 但不是本次要的季度 → 强烈拒绝，避免把 Q2 的 PDF 当 Q1
                        score -= 300;
                    }
                    if (title.contains(qName)) score += 40;
                    // 年报/中期直接拒
                    if (title.contains("年报") || title.contains("年度报告")
                            || title.contains("中期报告") || title.contains("interim")) {
                        score -= 200;
                    }
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
     * 从公告标题里检测具体季度：
     *   - "3月/三月...止三个月" → Q1
     *   - "6月/六月...止三个月" → Q2
     *   - "9月/九月...止三个月" → Q3
     *   - "12月/十二月...止三个月" → Q4
     *   - "第N季度" 直接映射
     * 找不到返回 null。
     */
    private String detectQuarterFromTitle(String title) {
        if (title == null) return null;
        String t = title.toLowerCase(Locale.ROOT);

        java.util.regex.Matcher mZh = java.util.regex.Pattern
                .compile("第\\s*([1-4])\\s*季度")
                .matcher(title);
        if (mZh.find()) return "Q" + mZh.group(1);

        java.util.regex.Matcher mQNum = java.util.regex.Pattern
                .compile("\\bq([1-4])\\b")
                .matcher(t);
        if (mQNum.find()) return "Q" + mQNum.group(1);

        // "3月31日止三个月" / "6月30日止三个月" 等
        java.util.regex.Matcher mMonth = java.util.regex.Pattern
                .compile("(\\d{1,2})月(?:\\d{1,2}日)?止三个月")
                .matcher(title);
        if (mMonth.find()) {
            int endMonth = Integer.parseInt(mMonth.group(1));
            return switch (endMonth) {
                case 3 -> "Q1";
                case 6 -> "Q2";
                case 9 -> "Q3";
                case 12 -> "Q4";
                default -> null;
            };
        }
        return null;
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
