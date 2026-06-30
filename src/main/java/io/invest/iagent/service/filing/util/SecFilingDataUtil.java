package io.invest.iagent.service.filing.util;

import com.alibaba.fastjson2.TypeReference;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.invest.iagent.service.filing.model.SecFilingDataDTO;
import io.invest.iagent.utils.FileUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

@Slf4j
public class SecFilingDataUtil {

    private static final String SEC_COMPANY_TICKERS_URL = "https://www.sec.gov/files/company_tickers.json";
    private static final String SEC_COMPANY_FINANCIAL_URL = "https://data.sec.gov/api/xbrl/companyfacts/";

    private final String userAgent ;
    private static final String PATH_ROOT = "./sec";

    private final HttpClient httpClient;
    private final Map<String, String> tickerToCikCache;
    private Map<String, Map<String,Pair<String,String>>> indexMappingDict ;
    private final Path workspace ;

    public SecFilingDataUtil(Path workspace,String userAgent) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.tickerToCikCache = new HashMap<>();
        this.workspace = workspace;
        this.userAgent = userAgent;

        try {
            Path rootPath = workspace.resolve(PATH_ROOT);
            Files.createDirectories(rootPath);
            File indexMappingFile = rootPath.resolve("index_mapping.json").toFile() ;
            if(indexMappingFile.exists()){
                indexMappingDict = FileUtils.parseContent(indexMappingFile,new TypeReference<Map<String,Map<String,Pair<String,String>>>>(){}) ;
            }
            if(Objects.isNull(indexMappingDict)){
                indexMappingDict = defaultIndexMappingDict() ;
            }
        } catch (IOException e) {
            log.warn("Failed to create download directory: {}", PATH_ROOT, e);
            indexMappingDict = defaultIndexMappingDict() ;
        }
    }

    private Map<String, Map<String,Pair<String,String>>> defaultIndexMappingDict(){
        Map<String, Pair<String,String>> defaultDict = new HashMap<>() ;
        defaultDict.put("RevenueFromContractWithCustomerExcludingAssessedTax", Pair.of("Revenue","income")) ;
        defaultDict.put("Revenues", Pair.of("Revenue","income")) ;
        defaultDict.put("SalesRevenueNet", Pair.of("Revenue","income")) ;
        defaultDict.put("CostOfRevenue", Pair.of("CostOfRevenue","income")) ;
        defaultDict.put("CostOfGoodsAndServicesSold", Pair.of("CostOfRevenue","income")) ;
        defaultDict.put("OperatingExpenses", Pair.of("OperatingExpenses","income")) ;
        defaultDict.put("OperatingIncomeLoss", Pair.of("OperatingIncomeLoss","income")) ;
        defaultDict.put("NetIncomeLoss", Pair.of("NetIncomeLoss","income")) ;
        defaultDict.put("ProfitLoss", Pair.of("NetIncomeLoss","income")) ;
        return Map.of("DEFAULT", defaultDict) ;
    }

    public Map<String,Pair<String,String>> buildTickerDict(String ticker,Map<String,Pair<String,String>> dict){
        if(Objects.nonNull(dict)){
            return dict ;
        }
        if(Objects.isNull(ticker)){
            return null ;
        }
        if(Objects.isNull(indexMappingDict)){
            indexMappingDict = defaultIndexMappingDict() ;
        }
        dict = indexMappingDict.get(ticker.toUpperCase(Locale.ROOT)) ;
        if(Objects.isNull(dict)){
            dict = indexMappingDict.get("DEFAULT") ;
        }
        if(Objects.isNull(dict)){
            return null ;
        }
        Map<String,Pair<String,String>> merged = new HashMap<>(defaultIndexMappingDict().get("DEFAULT")) ;
        merged.putAll(dict) ;
        return merged ;
    }

    public Path companyFactsCachePath(String ticker){
        return workspace.resolve(PATH_ROOT).resolve(ticker.toUpperCase(Locale.ROOT) + ".json") ;
    }

    public boolean hasCompanyFactsCache(String ticker){
        return Files.exists(companyFactsCachePath(ticker)) ;
    }

    public SecFilingDataDTO fetchFinancialIndexValue(String ticker) throws IOException, InterruptedException {
        return fetchFinancialIndexValue(ticker,true) ;
    }

    public SecFilingDataDTO fetchFinancialIndexValue(String ticker, boolean allowNetwork) throws IOException, InterruptedException {
        String normalizedTicker = ticker.toUpperCase(Locale.ROOT) ;
        String cik = allowNetwork ? getCikForTicker(normalizedTicker) : null ;
        return fetchFinancialIndexValue(normalizedTicker,cik,allowNetwork) ;
    }

    private SecFilingDataDTO fetchFinancialIndexValue(String ticker, String cik, boolean allowNetwork) throws IOException, InterruptedException {
        log.info("Fetching Financial for ticker: {}", ticker);
        Path cacheFile = companyFactsCachePath(ticker);

        if (!Files.exists(cacheFile)) {
            if(!allowNetwork){
                throw new IOException("SEC company facts cache not found: " + cacheFile.toAbsolutePath());
            }
            if(StringUtils.isBlank(cik)){
                throw new IOException("CIK not found for ticker: " + ticker);
            }
            log.info("Downloading company financial values from SEC...");
            downloadCompanyFinancial(cacheFile, cik);
        }
        return FileUtils.parseContent(cacheFile.toFile(), new TypeReference<SecFilingDataDTO>(){});
    }

    private void downloadCompanyFinancial(Path cacheFile,String cik) throws IOException, InterruptedException {
        String paddedCik = String.format("CIK%010d", Integer.parseInt(cik));
        String url = SEC_COMPANY_FINANCIAL_URL + paddedCik + ".json";
        System.out.println(url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", userAgent)
//                .header("Accept-Encoding", "gzip, deflate")
                .GET()
                .build();

        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    Files.writeString(cacheFile, response.body());
                    log.info("Company financial cached to: {}", cacheFile.toAbsolutePath());
                    return;
                } else if (response.statusCode() == 403 || response.statusCode() == 429) {
                    long waitTime = attempt * 5000L;
                    log.warn("Company financial cached Rate limited ({}). Waiting {}ms before retry...", response.statusCode(), waitTime);
                    Thread.sleep(waitTime);
                } else {
                    log.error("Failed to download company financial. Status: {}", response.statusCode());
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }

        log.error("Failed to download company financial after {} attempts", maxRetries);
    }

    private String getCikForTicker(String ticker) throws IOException, InterruptedException {
        if (tickerToCikCache.containsKey(ticker.toUpperCase())) {
            return tickerToCikCache.get(ticker.toUpperCase());
        }
        
        String cik = fetchCikForTicker(ticker);
        if (cik != null) {
            tickerToCikCache.put(ticker.toUpperCase(), cik);
        }
        
        return cik;
    }

    private String fetchCikForTicker(String ticker) throws IOException, InterruptedException {
        log.info("Fetching CIK for ticker: {}", ticker);
        
        Path cacheFile =  workspace.resolve(PATH_ROOT).resolve("sec_company_tickers.json");
        
        if (!Files.exists(cacheFile) || isCacheExpired(cacheFile)) {
            log.info("Downloading company tickers from SEC...");
            downloadCompanyTickers(cacheFile);
        }

        Map<String,TickerDTO> tickers = FileUtils.parseContent(cacheFile.toFile(), new TypeReference<Map<String,TickerDTO>>(){});
        return tickers.values().stream()
                .filter(t-> StringUtils.equalsIgnoreCase(ticker, t.getTicker()))
                .map(TickerDTO::getCik)
                .findFirst()
                .map(Objects::toString)
                .orElse(null) ;
    }

    private boolean isCacheExpired(Path cacheFile) throws IOException {
        long lastModified = Files.getLastModifiedTime(cacheFile).toMillis();
        long now = System.currentTimeMillis();
        long oneDay = 30L * 24 * 60 * 60 * 1000;
        
        return (now - lastModified) > oneDay;
    }

    private void downloadCompanyTickers(Path cacheFile) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SEC_COMPANY_TICKERS_URL))
                .header("User-Agent", userAgent)
                .GET()
                .build();
        
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    Files.writeString(cacheFile, response.body());
                    log.info("Company tickers cached to: {}", cacheFile.toAbsolutePath());
                    return;
                } else if (response.statusCode() == 403 || response.statusCode() == 429) {
                    long waitTime = attempt * 5000L;
                    log.warn("Rate limited ({}). Waiting {}ms before retry...", response.statusCode(), waitTime);
                    Thread.sleep(waitTime);
                } else {
                    log.error("Failed to download company tickers. Status: {}", response.statusCode());
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        
        log.error("Failed to download company tickers after {} attempts", maxRetries);
    }

    @Data
    static class TickerDTO{
        @JsonProperty("ticker")
        private String ticker;
        @JsonProperty("cik_str")
        private Integer cik;
        @JsonProperty("title")
        private String title;
    }
}
