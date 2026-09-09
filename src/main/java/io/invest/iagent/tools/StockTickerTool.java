package io.invest.iagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 股票代码查询工具：公司名 → 股票代码 + 市场归属 + 财报类型信息。
 *
 * <p>参考 workspace/skills/stock-ticker 做的简化实现（无缓存、无外部配置文件）：
 * <ol>
 *   <li>调东方财富 suggest 综合搜索接口取候选；</li>
 *   <li>过滤非股票证券类型与衍生品/ETF；</li>
 *   <li>MktNum 翻成交易所简称并按交易所偏好稳定排序；</li>
 *   <li>截取前 limit 条，补齐市场大区、公司类型、三类财报类型、监管机构。</li>
 * </ol>
 *
 * <p>数据源公开免鉴权，覆盖 A 股（沪深京）/港股/美股。
 */
public class StockTickerTool {

    // ==================== 接口常量 ====================
    private static final String SEARCH_URL = "https://searchapi.eastmoney.com/api/suggest/get";
    /** 东方财富前端固定 token，公开接口只做参数校验，不构成鉴权。 */
    private static final String TOKEN = "D43BF722C8E33BDC906FB84D85E326E8";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";
    private static final String REFERER = "https://quote.eastmoney.com/";
    /** 单次向搜索接口拉取的候选上限。 */
    private static final int SEARCH_COUNT = 20;
    private static final int TIMEOUT_SECONDS = 10;

    // ==================== 映射与过滤规则（与 skill 的 stock-search.json 保持一致） ====================
    /** 东方财富 MktNum → 交易所简称。 */
    private static final Map<Integer, String> MARKET_MAP = Map.of(
            0, "SZSE",       // 深交所
            1, "SSE",        // 上交所
            2, "SZSE",       // 深交所（备选码）
            105, "NASDAQ",   // 纳斯达克
            106, "NYSE",     // 纽交所
            116, "HKG",      // 港交所
            155, "LSE"       // 伦交所
    );

    /** 只保留 SecurityTypeName 包含以下任一 token 的结果（其余视为债券、指数等非股票）。 */
    private static final Set<String> SECURITY_TYPE_TOKENS = Set.of(
            "A股", "港股", "美股", "科创板", "创业板", "北交所", "沪A", "深A"
    );

    /** 股票名包含以下任一 token 视为衍生品/ETF/杠杆产品，直接过滤。 */
    private static final Set<String> DERIVATIVE_KEYWORDS = Set.of(
            "ETF", "etf", "做多", "做空", "杠杆", "反向", "两倍", "三倍",
            "二倍", "1倍", "2倍", "3倍", "指数", "基金", "ADRC", "ADRS", "Trust"
    );

    /** symbol 包含以下任一 token 视为美股中概股，走 20-F/6-K 而非 10-K/10-Q。 */
    private static final Set<String> CHINESE_ADR_TOKENS = Set.of(
            "BABA", "PDD", "JD", "BIDU", "NIO", "LI", "XPEV",
            "-ADR", "-ADS", ".US"
    );

    /** 不传 preferredExchanges 时的默认交易所排序偏好（越靠前越优先）。 */
    private static final List<String> DEFAULT_PREFERRED_EXCHANGES =
            List.of("NASDAQ", "NYSE", "HKG", "SSE", "SZSE");

    /** 交易所 → 大区分组。 */
    private static final Set<String> CN_EXCHANGES = Set.of("SSE", "Shanghai", "SZSE", "Shenzhen", "BSE");
    private static final Set<String> HK_EXCHANGES = Set.of("HKG", "Hong Kong");
    private static final Set<String> US_EXCHANGES = Set.of("NASDAQ", "NYSE", "NYSE Arca", "AMEX");

    /** 各大区/公司类型的画像：市场区域、公司类型、年报/季报/半年报类型、监管机构。 */
    private record Profile(String marketRegion, String companyType,
                           String annualReportType, String quarterlyReportType,
                           String semiAnnualReportType, String filingAuthority) {
    }

    private static final Profile PROFILE_CN = new Profile(
            "CN", "CN_LISTED", "年度报告", "季度报告", "半年度报告", "中国证监会");
    private static final Profile PROFILE_HK = new Profile(
            "HK", "HK_LISTED", "年报", "季度业绩公告（自愿）", "中期报告", "香港联交所");
    private static final Profile PROFILE_US_DOMESTIC = new Profile(
            "US", "US_DOMESTIC", "10-K", "10-Q", "N/A", "美国SEC");
    private static final Profile PROFILE_US_ADR = new Profile(
            "US", "FOREIGN_PRIVATE_ISSUER", "20-F", "6-K", "N/A", "美国SEC");
    private static final Profile PROFILE_OTHER = new Profile(
            "OTHER", "OTHER", "年度报告", "季度报告", "N/A", "当地监管机构");

    // ==================== 客户端 ====================
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public StockTickerTool() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    // ==================== 数据模型 ====================
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StockInfo {
        /** 股票代码，如 BABA / 00700 / 600519 */
        private String symbol;
        /** 证券简称 */
        private String name;
        /** 东方财富数字市场码 */
        private Integer exchange;
        /** 交易所简称，如 NASDAQ / HKG / SZSE */
        private String exchangeName;
        /** 市场大区：CN / HK / US / OTHER */
        private String marketRegion;
        /** 东方财富返回的证券类型中文名 */
        private String securityType;
        /** 公司类型：CN_LISTED / HK_LISTED / US_DOMESTIC / FOREIGN_PRIVATE_ISSUER / OTHER */
        private String companyType;
        private String annualReportType;
        private String quarterlyReportType;
        private String semiAnnualReportType;
        private String filingAuthority;
        /** 匹配分：搜索接口位次越靠前分越高，按 0.05 递减 */
        private Double matchScore;
    }

    // ==================== Tool 入口 ====================
    @Tool(name = "get_stock_ticker",
            description = "根据公司名查询股票代码及上市市场信息，覆盖A股（沪深京）、港股、美股。"
                    + "返回股票代码、交易所、市场大区（CN/HK/US）、公司类型、年报/季报/半年报类型、监管机构等。"
                    + "需要把公司名（如 阿里巴巴、腾讯、BABA）转换为股票代码或确认其上市市场时使用。")
    public List<StockInfo> searchTicker(
            @ToolParam(name = "companyName", required = true,
                    description = "公司名或代码，例如 阿里巴巴 / 腾讯 / BABA") String companyName,
            @ToolParam(name = "preferredExchanges", required = false,
                    description = "优先交易所（按顺序排序），例如 [\"HKG\",\"NASDAQ\"]；不填使用默认偏好") List<String> preferredExchanges,
            @ToolParam(name = "limit", required = false,
                    description = "返回记录数上限，默认 1") Integer limit
    ) {
        if (StringUtils.isBlank(companyName)) {
            return List.of();
        }
        List<String> preferred = (preferredExchanges == null || preferredExchanges.isEmpty())
                ? DEFAULT_PREFERRED_EXCHANGES : preferredExchanges;
        int max = (limit == null || limit <= 0) ? 1 : limit;

        try {
            String body = doSearch(companyName.trim());
            return parseResults(body, preferred, max);
        } catch (IOException e) {
            throw new UncheckedIOException("查询股票信息失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("查询股票信息被中断: " + companyName, e);
        }
    }

    // ==================== 搜索接口 ====================
    /** 请求东方财富 suggest 接口，返回原始 JSON 文本。 */
    private String doSearch(String companyName) throws IOException, InterruptedException {
        String url = SEARCH_URL + "?input=" + URLEncoder.encode(companyName, StandardCharsets.UTF_8)
                + "&type=14&token=" + TOKEN + "&count=" + SEARCH_COUNT;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Search API returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    /**
     * 解析接口返回：过滤非股票/衍生品 → 位次打分 → 按交易所偏好稳定排序 → 截取 limit 条 → 补市场画像。
     * 包级可见以便离线单测（不依赖网络）。
     */
    List<StockInfo> parseResults(String json, List<String> preferredExchanges, int limit) throws IOException {
        JsonNode dataList = objectMapper.readTree(json).path("QuotationCodeTable").path("Data");
        if (!dataList.isArray() || dataList.isEmpty()) {
            return List.of();
        }

        // 1) 过滤 + 翻市场码
        List<StockInfo> results = new ArrayList<>();
        for (JsonNode item : dataList) {
            String securityType = item.path("SecurityTypeName").asText("");
            String name = item.path("Name").asText("");
            if (SECURITY_TYPE_TOKENS.stream().noneMatch(securityType::contains)) {
                continue;
            }
            if (containsAny(name, DERIVATIVE_KEYWORDS)) {
                continue;
            }
            int marketCode = item.path("MktNum").asInt(0);
            results.add(StockInfo.builder()
                    .symbol(item.path("Code").asText(""))
                    .name(name)
                    .exchange(marketCode)
                    .exchangeName(MARKET_MAP.getOrDefault(marketCode, "OTHER"))
                    .securityType(securityType)
                    .matchScore(1.0)
                    .build());
        }
        if (results.isEmpty()) {
            return List.of();
        }

        // 2) 匹配分随过滤后的位次递减，表示搜索接口本身的排序信心
        for (int i = 0; i < results.size(); i++) {
            double score = Math.round((1.0 - i * 0.05) * 10000.0) / 10000.0;
            results.get(i).setMatchScore(score);
        }

        // 3) 偏好排序：preferredExchanges 中位置越靠前越前；不在列表里的按原顺序追加（稳定排序）
        List<String> preferred = preferredExchanges == null ? List.of() : preferredExchanges;
        results.sort(Comparator.comparingInt(s -> {
            int idx = preferred.indexOf(s.getExchangeName());
            return idx < 0 ? Integer.MAX_VALUE : idx;
        }));

        // 4) 截取 + 补画像
        int actualLimit = Math.min(limit, results.size());
        List<StockInfo> finalResults = new ArrayList<>(actualLimit);
        for (int i = 0; i < actualLimit; i++) {
            StockInfo info = results.get(i);
            fillProfile(info);
            finalResults.add(info);
        }
        return finalResults;
    }

    // ==================== 市场画像 ====================
    /** 根据交易所定位 marketRegion / companyType / 财报类型 / 监管机构。 */
    private void fillProfile(StockInfo info) {
        Profile profile;
        String exchangeName = info.getExchangeName();
        if (CN_EXCHANGES.contains(exchangeName)) {
            profile = PROFILE_CN;
        } else if (HK_EXCHANGES.contains(exchangeName)) {
            profile = PROFILE_HK;
        } else if (US_EXCHANGES.contains(exchangeName)) {
            profile = containsAny(info.getSymbol(), CHINESE_ADR_TOKENS)
                    ? PROFILE_US_ADR : PROFILE_US_DOMESTIC;
        } else {
            profile = PROFILE_OTHER;
        }
        info.setMarketRegion(profile.marketRegion());
        info.setCompanyType(profile.companyType());
        info.setAnnualReportType(profile.annualReportType());
        info.setQuarterlyReportType(profile.quarterlyReportType());
        info.setSemiAnnualReportType(profile.semiAnnualReportType());
        info.setFilingAuthority(profile.filingAuthority());
    }

    private static boolean containsAny(String text, Set<String> tokens) {
        if (StringUtils.isEmpty(text)) {
            return false;
        }
        return tokens.stream().anyMatch(text::contains);
    }
}
