package io.invest.iagent.service.filing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.invest.iagent.model.FinancialFilingDownloadResult;
import io.invest.iagent.model.FinancialFormType;
import io.invest.iagent.model.TickerMarket;
import io.invest.iagent.service.filing.downloader.CnInfoDownloader;
import io.invest.iagent.service.filing.downloader.HkexDownloader;
import io.invest.iagent.service.filing.downloader.SecFilingDownloader;
import io.invest.iagent.service.filing.util.TickerMarketUtil;
import io.invest.iagent.service.filing.util.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 统一的财报下载工具
 * 支持美股（SEC EDGAR）、A股（巨潮资讯网）、港股（披露易）
 */
public class FinancialFilingDownloadService {

    private static final Logger logger = LoggerFactory.getLogger(FinancialFilingDownloadService.class);

    // SEC EDGAR 配置
    private static final String DEFAULT_SEC_USER_AGENT = "InvestIAgent contact@example.com";

    // 通用配置
    private final SecFilingDownloader secDownloader;
    private final CnInfoDownloader cnInfoDownloader;
    private final HkexDownloader hkexDownloader;

    // ========== 构造器 ==========

    public FinancialFilingDownloadService(Path baseDir) {
        this(baseDir, DEFAULT_SEC_USER_AGENT);
    }

    public FinancialFilingDownloadService(Path baseDir, String secUserAgent) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        ObjectMapper objectMapper = new ObjectMapper();

        try {
            Files.createDirectories(baseDir);
            Files.createDirectories(WorkspacePaths.companiesDir(baseDir));
        } catch (IOException e) {
            logger.warn("Failed to create directories", e);
        }

        this.secDownloader = new SecFilingDownloader(baseDir, httpClient, objectMapper, secUserAgent);
        this.cnInfoDownloader = new CnInfoDownloader(baseDir, httpClient, objectMapper);
        this.hkexDownloader = new HkexDownloader(baseDir, httpClient, objectMapper);
    }

    // ========== 服务入口 ==========

    public FinancialFilingDownloadResult downloadFiling(String ticker, String fiscalYear, String filingType) {
        return downloadFiling(ticker, fiscalYear, filingType, false);
    }

    public FinancialFilingDownloadResult downloadFiling(String ticker, String fiscalYear, String filingType, boolean overwrite) {
        try {
            logger.info("Downloading filing for ticker: {}, year: {}, type: {}, overwrite: {}", ticker, fiscalYear, filingType, overwrite);

            // 1. 识别市场
            TickerMarket market = TickerMarketUtil.resolveMarket(ticker);
            String normalizedTicker = normalizeTicker(ticker, market);
            logger.info("Identified market: {} for ticker: {}", market, normalizedTicker);

            // 2. 解析参数
            boolean allYears;
            Set<Integer> fiscalYears;
            try {
                var yearsResult = parseFiscalYears(fiscalYear);
                allYears = yearsResult.allYears;
                fiscalYears = yearsResult.years;
            } catch (IllegalArgumentException e) {
                return buildErrorResult(ticker, e.getMessage());
            }

            boolean allTypes;
            Set<String> formTypes;
            try {
                var typesResult = parseFilingTypes(filingType, market);
                allTypes = typesResult.allTypes;
                formTypes = typesResult.types;
            } catch (IllegalArgumentException e) {
                return buildErrorResult(ticker, e.getMessage());
            }

            logger.info("Parsed: market={}, years={}, allYears={}, types={}, allTypes={}",
                    market, fiscalYears, allYears, formTypes, allTypes);

            // 3. 根据市场调用对应的下载逻辑
            return switch (market) {
                case US -> secDownloader.downloadUsFiling(normalizedTicker, fiscalYears, allYears, formTypes, allTypes, overwrite);
                case CN_A -> cnInfoDownloader.downloadCnFiling(normalizedTicker, fiscalYears, allYears, formTypes, allTypes, overwrite);
                case HK -> hkexDownloader.downloadHkFiling(normalizedTicker, fiscalYears, allYears, formTypes, allTypes, overwrite);
            };

        } catch (IllegalArgumentException e) {
            logger.error("Invalid input", e);
            return buildErrorResult(ticker, e.getMessage());
        } catch (Exception e) {
            logger.error("Error downloading filing", e);
            return buildErrorResult(ticker, String.format("Error downloading filing: %s", e.getMessage()));
        }
    }

    // Java 调用兼容重载（测试等直接传 int 的场景）
    public FinancialFilingDownloadResult downloadFiling(String ticker, int fiscalYear, String filingType) {
        return downloadFiling(ticker, String.valueOf(fiscalYear), filingType);
    }

    public FinancialFilingDownloadResult downloadFiling(String ticker, int fiscalYear, String filingType, boolean overwrite) {
        return downloadFiling(ticker, String.valueOf(fiscalYear), filingType, overwrite);
    }

    private FinancialFilingDownloadResult buildErrorResult(String ticker, String errorMessage) {
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

    // ========== 内部类型 ==========

    public record YearParseResult(boolean allYears, Set<Integer> years) {}
    public record TypeParseResult(boolean allTypes, Set<String> types) {}

    // ========== 参数解析 ==========

    public YearParseResult parseFiscalYears(String fiscalYear) {
        if (isAllToken(fiscalYear)) {
            return new YearParseResult(true, Collections.emptySet());
        }
        if (fiscalYear.isBlank()) {
            return new YearParseResult(true, Collections.emptySet());
        }

        Set<Integer> years = new LinkedHashSet<>();
        String[] parts = fiscalYear.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            if (!trimmed.matches("\\d{4}")) {
                throw new IllegalArgumentException(
                        "Invalid fiscal year: '" + trimmed + "'. Expected 4-digit year(s) like 2024 or 2024,2025");
            }
            int year = Integer.parseInt(trimmed);
            if (year < 1900 || year > 2100) {
                throw new IllegalArgumentException(
                        "Fiscal year out of range: " + year + ". Expected between 1900 and 2100");
            }
            years.add(year);
        }
        if (years.isEmpty()) {
            return new YearParseResult(true, Collections.emptySet());
        }
        return new YearParseResult(false, years);
    }

    public TypeParseResult parseFilingTypes(String filingType, TickerMarket market) {
        if (isAllToken(filingType)) {
            return new TypeParseResult(true, defaultTypesForMarket(market));
        }
        if (filingType.isBlank()) {
            return new TypeParseResult(true, defaultTypesForMarket(market));
        }

        Set<String> types = new LinkedHashSet<>();
        String[] parts = filingType.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            types.addAll(resolveTypeAlias(trimmed, market));
        }
        if (types.isEmpty()) {
            return new TypeParseResult(true, defaultTypesForMarket(market));
        }
        return new TypeParseResult(false, types);
    }

    private boolean isAllToken(String value) {
        if (value == null) return true;
        String v = value.trim().toLowerCase();
        return v.isEmpty() || v.equals("all") || v.equals("*") || v.equals("全部");
    }

    private Set<String> defaultTypesForMarket(TickerMarket market) {
        return switch (market) {
            case US -> Set.of("10-K", "10-Q", "20-F", "6-K", "8-K", "DEF 14A", "SC 13D", "SC 13G");
            case CN_A -> Set.of("FY", "H1", "Q1", "Q3");
            case HK -> Set.of("FY", "H1","Q1","Q2","Q3","Q4");
        };
    }

    private Set<String> resolveTypeAlias(String input, TickerMarket market) {
        String lower = input.toLowerCase(Locale.ROOT).trim();
        return switch (market) {
            case US -> switch (lower) {
                case "annual" -> Set.of("10-K", "20-F");
                case "quarterly" -> Set.of("10-Q", "6-K");
                case "10-k" -> Set.of("10-K");
                case "10-q" -> Set.of("10-Q");
                case "20-f" -> Set.of("20-F");
                case "6-k" -> Set.of("6-K");
                case "8-k" -> Set.of("8-K");
                case "def 14a" -> Set.of("DEF 14A");
                case "sc 13d" -> Set.of("SC 13D");
                case "sc 13g" -> Set.of("SC 13G");
                default -> {
                    // 尝试直接匹配 FormType
                    try {
                        FinancialFormType ft = FinancialFormType.fromCode(input);
                        if (ft.isSupported(TickerMarket.US)) {
                            yield Set.of(ft.getCode());
                        }
                    } catch (IllegalArgumentException ignored) {}
                    logger.warn("Unknown US filing type '{}', defaulting to annual", input);
                    yield Set.of("10-K", "20-F");
                }
            };
            case CN_A -> switch (lower) {
                case "annual", "年报", "fy" -> Set.of("FY");
                case "semi-annual", "semiannual", "中报", "半年报", "h1" -> Set.of("H1");
                case "quarterly", "季报", "q1" -> Set.of("Q1");
                case "q2" -> Set.of("Q2");
                case "q3" -> Set.of("Q3");
                case "q4" -> Set.of("Q4");
                default -> {
                    try {
                        FinancialFormType ft = FinancialFormType.fromCode(input);
                        if (ft.isSupported(TickerMarket.CN_A)) {
                            yield Set.of(ft.getCode());
                        }
                    } catch (IllegalArgumentException ignored) {}
                    logger.warn("Unknown CN filing type '{}', defaulting to annual", input);
                    yield Set.of("FY");
                }
            };
            case HK -> switch (lower) {
                case "annual", "年报", "fy" -> Set.of("FY");
                case "interim", "中报", "半年报", "h1" -> Set.of("H1");
                case "quarterly", "季报" -> Set.of("Q1", "Q2", "Q3", "Q4");
                case "q1" -> Set.of("Q1");
                case "q2" -> Set.of("Q2");
                case "q3" -> Set.of("Q3");
                case "q4" -> Set.of("Q4");
                default -> {
                    try {
                        FinancialFormType ft = FinancialFormType.fromCode(input);
                        if (ft.isSupported(TickerMarket.HK)) {
                            yield Set.of(ft.getCode());
                        }
                    } catch (IllegalArgumentException ignored) {}
                    logger.warn("Unknown HK filing type '{}', defaulting to annual", input);
                    yield Set.of("FY");
                }
            };
        };
    }

    private String normalizeTicker(String ticker, TickerMarket market) {
        String clean = ticker.toUpperCase(Locale.ROOT).trim();
        return switch (market) {
            case US -> clean;  // 美股保持原样
            case CN_A -> {
                // 去除 .SH/.SZ 后缀，补齐 6 位
                String c = clean.replace(".SH", "").replace(".SZ", "");
                while (c.length() < 6) c = "0" + c;
                yield c;
            }
            case HK -> {
                // 补齐 5 位
                String c = clean.replace(".HK", "");
                while (c.length() < 5) c = "0" + c;
                yield c;
            }
        };
    }

    public List<JsonNode> findAllMatchingUsFilings(JsonNode submissions, Set<String> formTypes,
                                                     Set<Integer> fiscalYears, boolean allYears) {
        return secDownloader.findAllMatchingUsFilings(submissions, formTypes, fiscalYears, allYears);
    }

    public boolean shouldDownloadFile(String fileName, String primaryDocument, String formType, String secDocumentType) {
        return secDownloader.shouldDownloadFile(fileName, primaryDocument, formType, secDocumentType);
    }

    public String calculateDirectoryFingerprint(Path dir) throws IOException, NoSuchAlgorithmException {
        return secDownloader.calculateDirectoryFingerprint(dir);
    }
}
