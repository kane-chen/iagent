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
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
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
 * 公司金融信息查询工具（东方财富版）。
 *
 * <p>数据源：东方财富搜索接口。覆盖 A 股（沪深京）、港股、美股，无需认证国内直连可用。
 * 作为 agentscope Tool（{@code get_stock_ticker}）暴露给上层 Agent 使用。
 */
@Component
public class StockInfoTool {

    // ==================== 接口常量 ====================
    private static final String SEARCH_URL = "https://searchapi.eastmoney.com/api/suggest/get";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    // ==================== 映射表 ====================
    /** 东方财富 MktNum → 交易所简称。用于把搜索接口返回的数字市场码翻成人类可读名。 */
    private static final Map<Integer, String> EASTMONEY_MARKET_MAP = Map.of(
            0, "SZSE",       // 深交所
            1, "SSE",        // 上交所
            2, "SZSE",       // 深交所（备选码）
            116, "HKG",      // 港交所
            105, "NASDAQ",   // 纳斯达克
            106, "NYSE",     // 纽交所
            155, "LSE"       // 伦交所
    );

    /** 只保留 SecurityTypeName 包含以下任一 token 的结果（其余视为非股票类）。 */
    private static final Set<String> SECURITY_TYPE_FILTER = Set.of(
            "A股", "港股", "美股", "科创板", "创业板", "北交所", "沪A", "深A"
    );

    /** 名字里出现即视为衍生品/ETF/杠杆产品，一律过滤掉。 */
    private static final Set<String> DERIVATIVE_KEYWORDS = Set.of(
            "ETF", "etf", "做多", "做空", "杠杆", "反向", "两倍", "三倍",
            "二倍", "1倍", "2倍", "3倍", "指数", "基金", "ADRC", "ADRS", "Trust"
    );

    /** symbol 包含以下任意 token 视为中概股（美股走 20-F/6-K 而非 10-K/10-Q）。 */
    private static final Set<String> CHINESE_ADR_TOKENS = Set.of(
            "BABA", "PDD", "JD", "BIDU", "NIO", "LI", "XPEV",
            "-ADR", "-ADS", ".US"
    );

    private static final List<String> DEFAULT_PREFERRED_EXCHANGES =
            List.of("NASDAQ", "NYSE", "HKG", "SSE", "SZSE");

    private static final Set<String> CN_EXCHANGES = Set.of("SSE", "Shanghai", "SZSE", "Shenzhen", "BSE");
    private static final Set<String> HK_EXCHANGES = Set.of("HKG", "Hong Kong");
    private static final Set<String> US_EXCHANGES = Set.of("NASDAQ", "NYSE", "NYSE Arca", "AMEX");

    // ==================== HTTP 客户端 ====================
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public StockInfoTool() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    // ==================== 数据模型 ====================
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StockInfo {
        private String symbol;
        private String name;
        private Integer exchange;
        private String exchangeName;
        private String marketRegion;
        private String securityType;
        private String companyType;
        private String annualReportType;
        private String quarterlyReportType;
        private String semiAnnualReportType;
        private String filingAuthority;
        private Double matchScore;
    }

    // ==================== 对外接口 ====================
    @Tool(name = "get_stock_ticker", description = "根据公司名获取该公司的股票代码、上市市场信息。")
    public List<StockInfo> searchTicker(
            @ToolParam(name = "companyName", required = true, description = "公司名") String companyName,
            @ToolParam(name = "preferredExchanges", required = false,
                    description = "优先获取的股票市场，可为空。") List<String> preferredExchanges)
            throws IOException, InterruptedException {
        List<String> preferred = CollectionUtils.isEmpty(preferredExchanges)
                ? DEFAULT_PREFERRED_EXCHANGES : preferredExchanges;
        return searchTicker(companyName, preferred, 1);
    }

    /**
     * 搜索匹配公司的股票并按 {@code preferredExchanges} 提前排序，取前 {@code limit} 条并补齐财报字段。
     * preferredExchanges 中位置越靠前排得越前；不在列表里的按搜索接口原顺序追加在末尾。
     */
    public List<StockInfo> searchTicker(String companyName, List<String> preferredExchanges, int limit)
            throws IOException, InterruptedException {
        List<StockInfo> results = doSearchTicker(companyName, 20);
        if (results.isEmpty()) {
            return results;
        }

        List<String> preferred = preferredExchanges == null ? List.of() : preferredExchanges;
        // List.sort 是稳定排序：同分（都不在 preferred 里 → MAX_VALUE）的元素保留搜索接口原顺序。
        results.sort(Comparator.comparingInt(s -> indexOrLast(preferred, s.getExchangeName())));

        int actualLimit = Math.min(limit, results.size());
        List<StockInfo> finalResults = new ArrayList<>(actualLimit);
        for (int i = 0; i < actualLimit; i++) {
            StockInfo info = results.get(i);
            fillStockDetail(info);
            finalResults.add(info);
        }
        return finalResults;
    }

    private static int indexOrLast(List<String> preferred, String value) {
        int idx = preferred.indexOf(value);
        return idx < 0 ? Integer.MAX_VALUE : idx;
    }

    // ==================== 搜索接口 ====================
    /** 从东方财富搜索接口查询股票代码（已过滤衍生品/非股票类）。 */
    public List<StockInfo> doSearchTicker(String companyName, Integer limit)
            throws IOException, InterruptedException {
        String encodedName = URLEncoder.encode(companyName, StandardCharsets.UTF_8);
        String uriStr = SEARCH_URL + "?input=" + encodedName
                + "&type=14&token=D43BF722C8E33BDC906FB84D85E326E8&count=" + limit;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uriStr))
                .header("User-Agent", UA)
                .header("Referer", "https://quote.eastmoney.com/")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Search API returned HTTP " + response.statusCode());
        }

        JsonNode dataList = objectMapper.readTree(response.body())
                .path("QuotationCodeTable").path("Data");
        if (!dataList.isArray()) {
            return new ArrayList<>();
        }

        List<StockInfo> results = new ArrayList<>();
        for (JsonNode item : dataList) {
            String securityType = item.path("SecurityTypeName").asText("");
            String name = item.path("Name").asText("");
            if (SECURITY_TYPE_FILTER.stream().noneMatch(securityType::contains)) continue;
            if (isDerivative(name)) continue;

            int marketCode = item.path("MktNum").asInt(0);
            results.add(StockInfo.builder()
                    .symbol(item.path("Code").asText())
                    .name(name)
                    .exchange(marketCode)
                    .exchangeName(EASTMONEY_MARKET_MAP.getOrDefault(marketCode, "OTHER"))
                    .securityType(securityType)
                    .matchScore(1.0)
                    .build());
        }

        // 匹配分随位次递减，表示搜索接口本身的排序信心（保留原语义）。
        for (int i = 0; i < results.size(); i++) {
            double score = Math.round((1.0 - i * 0.05) * 10000.0) / 10000.0;
            results.get(i).setMatchScore(score);
        }
        return results;
    }

    private boolean isDerivative(String name) {
        if (name == null) return false;
        return DERIVATIVE_KEYWORDS.stream().anyMatch(name::contains);
    }

    // ==================== 市场信息填充 ====================
    /** 根据交易所定位 marketRegion / companyType / 财报类型 / 监管机构。 */
    private void fillStockDetail(StockInfo info) {
        String exchangeName = info.getExchangeName();
        if (CN_EXCHANGES.contains(exchangeName)) {
            applyProfile(info, "CN", "CN_LISTED",
                    "年度报告", "季度报告", "半年度报告", "中国证监会");
        } else if (HK_EXCHANGES.contains(exchangeName)) {
            applyProfile(info, "HK", "HK_LISTED",
                    "年报", "季度业绩公告（自愿）", "中期报告", "香港联交所");
        } else if (US_EXCHANGES.contains(exchangeName)) {
            if (isChineseAdr(info.getSymbol())) {
                applyProfile(info, "US", "FOREIGN_PRIVATE_ISSUER",
                        "20-F", "6-K", "N/A", "美国SEC");
            } else {
                applyProfile(info, "US", "US_DOMESTIC",
                        "10-K", "10-Q", "N/A", "美国SEC");
            }
        } else {
            applyProfile(info, "OTHER", "OTHER",
                    "年度报告", "季度报告", "N/A", "当地监管机构");
        }
    }

    private static void applyProfile(StockInfo info, String region, String companyType,
                                     String annual, String quarterly, String semi, String authority) {
        info.setMarketRegion(region);
        info.setCompanyType(companyType);
        info.setAnnualReportType(annual);
        info.setQuarterlyReportType(quarterly);
        info.setSemiAnnualReportType(semi);
        info.setFilingAuthority(authority);
    }

    private boolean isChineseAdr(String symbol) {
        if (StringUtils.isBlank(symbol)) return false;
        return CHINESE_ADR_TOKENS.stream().anyMatch(symbol::contains);
    }
}
