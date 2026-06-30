package io.invest.iagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * 公司金融信息查询工具（东方财富版）
 *
 * 数据源：东方财富搜索接口 + 行情接口
 * 覆盖市场：A股（沪深京）、港股、美股
 * 无需认证，国内直连可用。
 *
 * 用法：
 *   java CompanyFinanceInfoTool --company_name "拼多多" [--preferred_exchanges "NASDAQ,HKG"] [--limit 1]
 */
@Component
public class StockInfoTool {

    // ==================== 接口常量 ====================
    private static final String SEARCH_URL = "https://searchapi.eastmoney.com/api/suggest/get";
    private static final String QUOTE_URL = "https://push2.eastmoney.com/api/qt/stock/get";

    // ==================== 映射表 ====================
    private static final Map<Integer, String> EASTMONEY_MARKET_MAP;
    private static final Map<Integer, String> EASTMONEY_REGION_MAP;
    private static final Map<String, Integer> PREFERRED_EXCHANGE_MAP;

    private static final Set<String> SECURITY_TYPE_FILTER = Set.of(
            "A股", "港股", "美股", "科创板", "创业板", "北交所","沪A","深A"
    );

    private static final List<String> DERIVATIVE_KEYWORDS = List.of(
            "ETF", "etf", "做多", "做空", "杠杆", "反向", "两倍", "三倍",
            "二倍", "三倍", "1倍", "2倍", "3倍", "指数", "基金", "ADRC", "ADRS", "Trust"
    );

    private static final List<String> CHINESE_ADR_KEYWORDS = List.of(
            "BABA", "PDD", "JD", "BIDU", "NIO", "LI", "XPEV"
    );
    private static final List<String> CHINESE_ADR_SUFFIXES = List.of("-ADR", "-ADS", ".US");

    static {
        // 深交所（备选）
        EASTMONEY_MARKET_MAP = Map.of(0, "SZSE",      // 深交所
                1, "SSE",       // 上交所
                116, "HKG",     // 港交所
                105, "NASDAQ", // 美股（默认映射为 NASDAQ）
                106, "NYSE", // 美股（默认映射为 NASDAQ）
                155, "LSE",     // 伦交所
                2, "SZSE");

        EASTMONEY_REGION_MAP = Map.of(0, "CN"
                , 1, "CN"
                , 2, "CN"
                , 116, "HK"
                , 105, "US"
                , 106, "US"
                , 155, "EU");

        PREFERRED_EXCHANGE_MAP = Map.of(
                "NASDAQ", 105
                , "NYSE", 105
                , "HKG", 116
                , "SSE", 1
                , "SZSE", 0
                , "BSE", 0);
    }

    // ==================== HTTP 客户端 ====================
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public StockInfoTool() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    // ==================== 核心方法 ====================

    /**
     * 从东方财富搜索接口查询股票代码（已过滤衍生品）
     */
    public List<StockInfo> doSearchTicker(String companyName, int limit)
            throws IOException, InterruptedException {

        String encodedName = URLEncoder.encode(companyName, StandardCharsets.UTF_8);
        String uriStr = SEARCH_URL + "?input=" + encodedName
                + "&type=14&token=D43BF722C8E33BDC906FB84D85E326E8&count=" + limit;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uriStr))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                .header("Referer", "https://quote.eastmoney.com/")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Search API returned HTTP " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode dataList = root.path("QuotationCodeTable").path("Data");

        List<StockInfo> results = new ArrayList<>();
        if (dataList.isArray()) {
            for (JsonNode item : dataList) {
                String securityType = item.path("SecurityTypeName").asText("");
                String name = item.path("Name").asText("");

                // 1. 只保留股票类
                boolean isStock = SECURITY_TYPE_FILTER.stream().anyMatch(securityType::contains);
                if (!isStock) continue;

                // 2. 过滤衍生品/ETF/杠杆产品
                if (isDerivative(name)) continue;

                int marketCode = item.path("MktNum").asInt(0);
                String exchangeName = EASTMONEY_MARKET_MAP.getOrDefault(marketCode, "OTHER");
                String marketRegion = EASTMONEY_REGION_MAP.getOrDefault(marketCode, "OTHER");

                StockInfo stock = StockInfo.builder()
                        .symbol(item.path("Code").asText(""))
                        .name(name)
                        .exchange(marketCode)
                        .exchangeName(exchangeName)
                        .marketRegion(marketRegion)
                        .securityType(securityType)
                        .matchScore(1.0)
                        .build();
                results.add(stock);
            }
        }

        // 设置 match_score 递减
        for (int i = 0; i < results.size(); i++) {
            double score = Math.round((1.0 - i * 0.05) * 10000.0) / 10000.0;
            results.get(i).setMatchScore(score);
        }

        return results;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StockInfo{
        private String symbol ;
        private String name ;
        private Integer exchange ;
        private String exchangeName ;
        private String marketRegion ;
        private String securityType ;
        private String companyType ;
        private String annualReportType ;
        private String quarterlyReportType ;
        private String semiAnnualReportType ;
        private String filingAuthority ;
        private Double matchScore ;
    }

    /**
     * 判断是否为衍生品/ETF/杠杆产品
     */
    private boolean isDerivative(String name) {
        if (name == null) return false;
        for (String kw : DERIVATIVE_KEYWORDS) {
            if (name.contains(kw)) return true;
        }
        return false;
    }

    /**
     * 从东方财富行情接口获取股票详细信息
     *
     * @param secid 格式: "市场代码.股票代码"，如 "1.600519"、"105.PDD"
     */
    public JsonNode getStockDetail(String secid) throws IOException, InterruptedException {
        String uriStr = QUOTE_URL + "?secid=" + secid
                + "&fields=f57,f58,f127,f43,f44,f45,f169,f170,f116,f117,f162";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uriStr))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                .header("Referer", "https://quote.eastmoney.com/")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Quote API returned HTTP " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        return root.path("data");
    }

    /**
     * 根据交易所和股票代码确定财报文件类型
     */

    /**
     * Skill 主执行函数
     */
    public Map<String, Object> execute(String companyName, List<String> preferredExchanges, int limit) {
        try {
            // 搜索接口固定拉取 20 条，再由 limit 参数限制最终输出
            List<StockInfo> items =this.searchTicker(companyName,preferredExchanges, limit);

            if (items.isEmpty()) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("success", false);
                err.put("error", "未找到与 '" + companyName + "' 相关的股票");
                return err;
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("results", items);
            return response;

        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("error", "查询失败: " + e.getMessage());
            return err;
        }
    }

    public List<StockInfo> searchTicker(String companyName,List<String> preferredExchanges, int limit)
            throws IOException, InterruptedException {
        List<StockInfo> results = doSearchTicker(companyName, 20);

        if (results.isEmpty()) {
            return results ;
        }

        // 优先展示指定交易所的结果
        Set<Integer> preferredCodes = new HashSet<>();
        Set<String> preferredNames = new HashSet<>();
        if(null != preferredExchanges){
            for (String ex : preferredExchanges) {
                Integer code = PREFERRED_EXCHANGE_MAP.get(ex);
                if (code != null) preferredCodes.add(code);
                preferredNames.add(ex);
            }
        }

        List<StockInfo> prioritized = new ArrayList<>();
        List<StockInfo> others = new ArrayList<>();
        for (StockInfo result : results) {
            Integer exCode =  result.getExchange();
            String exName = result.getExchangeName();
            if (preferredCodes.contains(exCode) || preferredNames.contains(exName)) {
                prioritized.add(result);
            } else {
                others.add(result);
            }
        }

        List<StockInfo> sortedResults = new ArrayList<>();
        sortedResults.addAll(prioritized);
        sortedResults.addAll(others);

        // 限制数量并添加财报类型
        List<StockInfo> finalResults = new ArrayList<>();
        int actualLimit = Math.min(limit, sortedResults.size());
        for (int i = 0; i < actualLimit; i++) {
            StockInfo info = sortedResults.get(i);
            fillStockDetail(info);
            finalResults.add(info);
        }
        return finalResults ;
    }

    private void fillStockDetail(StockInfo info) {
        String exchangeName = info.getExchangeName();
        // china
        if (Lists.newArrayList("SSE","Shanghai","SZSE","Shenzhen","BSE").contains(exchangeName)){
            info.setMarketRegion("CN");
            info.setCompanyType("CN_LISTED");
            info.setAnnualReportType("年度报告");
            info.setQuarterlyReportType("季度报告");
            info.setSemiAnnualReportType("半年度报告");
            info.setFilingAuthority("中国证监会");
            return ;
        }
        // hk
        if (Lists.newArrayList("HKG","Hong Kong").contains(exchangeName)) {
            info.setMarketRegion("HK");
            info.setCompanyType("HK_LISTED");
            info.setAnnualReportType("年报");
            info.setQuarterlyReportType("季度业绩公告（自愿）");
            info.setSemiAnnualReportType("中期报告");
            info.setFilingAuthority("香港联交所");
            return ;
        }
        // US
        if(Lists.newArrayList("NASDAQ","NYSE","NYSE Arca","AMEX").contains(exchangeName)){
            boolean isChineseStock = this.isChinese(info.getSymbol()) ;
            if (isChineseStock) {
                info.setMarketRegion("US");
                info.setCompanyType("FOREIGN_PRIVATE_ISSUER");
                info.setAnnualReportType("20-F");
                info.setQuarterlyReportType("6-K");
                info.setSemiAnnualReportType("N/A");
                info.setFilingAuthority("美国SEC");
                return ;
            } else {
                info.setMarketRegion("US");
                info.setCompanyType("US_DOMESTIC");
                info.setAnnualReportType("10-K");
                info.setQuarterlyReportType("10-Q");
                info.setSemiAnnualReportType("N/A");
                info.setFilingAuthority("美国SEC");
                return ;
            }
        }
        // other
        info.setMarketRegion("OTHER");
        info.setCompanyType("OTHER");
        info.setAnnualReportType("年度报告");
        info.setQuarterlyReportType("季度报告");
        info.setSemiAnnualReportType("N/A");
        info.setFilingAuthority("当地监管机构");
    }

    private boolean isChinese(String symbol){
        if(StringUtils.isBlank(symbol)){
            return false ;
        }
        if (symbol.endsWith(".US")){
            return true ;
        }
        for (String kw : CHINESE_ADR_KEYWORDS) {
            if (symbol.contains(kw)) {
                return true ;
            }
        }
        for (String sfx : CHINESE_ADR_SUFFIXES) {
            if (symbol.contains(sfx)) {
                return true ;
            }
        }
        return false ;
    }

}