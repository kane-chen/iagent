package io.invest.iagent.service.filing;

import io.invest.iagent.model.FinanceQueryParam;
import io.invest.iagent.model.FinancialIndexValueDTO;
import io.invest.iagent.service.filing.metrics.CachedFinancialMetricsDataFetcher;
import io.invest.iagent.service.filing.metrics.DownloadedFilingFinancialMetricsDataFetcher;
import io.invest.iagent.service.filing.metrics.FinancialMetricsDataFetcher;
import io.invest.iagent.service.filing.metrics.FinancialMetricsSourcePreference;
import io.invest.iagent.service.filing.metrics.HybridFinancialMetricsDataFetcher;
import io.invest.iagent.service.filing.metrics.LocalFinancialMetricsDataFetcher;
import io.invest.iagent.service.filing.metrics.OnlineFinancialMetricsDataFetcher;
import io.invest.iagent.service.filing.util.SecFilingDataUtil;
import io.invest.iagent.service.filing.util.SecXbrlMetricExtractor;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Path;
import java.time.Year;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class FinancialMetricsQueryService {

    private static final String DEFAULT_PERIODS = "FY,Q1,Q2,Q3,Q4";

    private final Map<FinancialMetricsSourcePreference, FinancialMetricsDataFetcher> fetchers;

    public FinancialMetricsQueryService(Path workspace, String userAgent){
        SecFilingDataUtil secFilingDataUtil = new SecFilingDataUtil(workspace,userAgent);
        SecXbrlMetricExtractor localExtractor = new SecXbrlMetricExtractor(workspace, secFilingDataUtil);
        this.fetchers = defaultFetchers(secFilingDataUtil, localExtractor);
    }

    public FinancialMetricsQueryService(SecFilingDataUtil secFilingDataUtil, SecXbrlMetricExtractor localExtractor){
        this.fetchers = defaultFetchers(secFilingDataUtil, localExtractor);
    }

    public FinancialMetricsQueryService(Path workspace, String userAgent, SecFilingDataUtil secFilingDataUtil){
        SecXbrlMetricExtractor localExtractor = new SecXbrlMetricExtractor(workspace, secFilingDataUtil);
        this.fetchers = defaultFetchers(secFilingDataUtil, localExtractor);
    }

    FinancialMetricsQueryService(Map<FinancialMetricsSourcePreference, FinancialMetricsDataFetcher> fetchers){
        this.fetchers = new EnumMap<>(FinancialMetricsSourcePreference.class);
        this.fetchers.putAll(fetchers);
    }

    public List<FinancialIndexValueDTO> queryFinancialMetrics(String ticker, String metrics, String startFiscalYear,
                                                              String endFiscalYear, String fiscalPeriods,
                                                              String sourcePreference) throws Exception {
        String normalizedTicker = normalizeTicker(ticker);
        String indexCodes = normalizeMetrics(metrics);
        String years = parseYearRange(startFiscalYear, endFiscalYear);
        String periods = parseFiscalPeriods(fiscalPeriods);
        FinancialMetricsSourcePreference source = parseSourcePreference(sourcePreference);
        FinanceQueryParam query = FinanceQueryParam.builder()
                .ticker(normalizedTicker)
                .indexCodes(indexCodes)
                .fiscalYears(years)
                .fiscalPeriods(periods)
                .build();
        FinancialMetricsDataFetcher fetcher = fetchers.get(source);
        if (fetcher == null) {
            throw new IllegalStateException("No financial metrics fetcher for source: " + source);
        }
        return fetcher.fetch(normalizedTicker, query);
    }

    private static Map<FinancialMetricsSourcePreference, FinancialMetricsDataFetcher> defaultFetchers(
            SecFilingDataUtil secFilingDataUtil, SecXbrlMetricExtractor localExtractor) {
        EnumMap<FinancialMetricsSourcePreference, FinancialMetricsDataFetcher> fetchers =
                new EnumMap<>(FinancialMetricsSourcePreference.class);
        FinancialMetricsDataFetcher onlineFetcher = new OnlineFinancialMetricsDataFetcher(secFilingDataUtil);
        FinancialMetricsDataFetcher cacheFetcher = new CachedFinancialMetricsDataFetcher(secFilingDataUtil);
        DownloadedFilingFinancialMetricsDataFetcher downloadedFilingFetcher =
                new DownloadedFilingFinancialMetricsDataFetcher(localExtractor);
        fetchers.put(onlineFetcher.source(), onlineFetcher);
        fetchers.put(cacheFetcher.source(), cacheFetcher);
        fetchers.put(FinancialMetricsSourcePreference.LOCAL,
                new LocalFinancialMetricsDataFetcher(cacheFetcher, downloadedFilingFetcher));
        fetchers.put(FinancialMetricsSourcePreference.AUTO,
                new HybridFinancialMetricsDataFetcher(onlineFetcher, downloadedFilingFetcher));
        return fetchers;
    }

    private String normalizeTicker(String ticker){
        if(StringUtils.isBlank(ticker)){
            throw new IllegalArgumentException("ticker不能为空");
        }
        return ticker.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeMetrics(String metrics){
        if(StringUtils.isBlank(metrics)){
            throw new IllegalArgumentException("metrics不能为空");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for(String item : metrics.split(",")){
            String metric = normalizeMetric(item);
            if(StringUtils.isNotBlank(metric)){
                normalized.add(metric);
            }
        }
        if(normalized.isEmpty()){
            throw new IllegalArgumentException("metrics不能为空");
        }
        return String.join(",", normalized);
    }

    private String normalizeMetric(String metric){
        String key = StringUtils.deleteWhitespace(StringUtils.defaultString(metric)).replace("-", "_").toLowerCase(Locale.ROOT);
        return switch (key){
            case "revenue", "revenues", "sales", "net_sales" -> "Revenue";
            case "cost", "costofrevenue", "cost_of_revenue", "costofgoodsandservicessold", "cogs" -> "CostOfRevenue";
            case "opex", "operatingexpense", "operatingexpenses", "operating_expense", "operating_expenses" -> "OperatingExpenses";
            case "operatingincome", "operating_income", "operatingloss", "operating_loss", "operatingincomeloss", "operating_income_loss" -> "OperatingIncomeLoss";
            case "netincome", "net_income", "netincomeloss", "net_income_loss" -> "NetIncomeLoss";
            default -> StringUtils.trim(metric);
        };
    }

    private String parseYearRange(String startFiscalYear, String endFiscalYear){
        Integer start = parseYear(startFiscalYear, "start_fiscal_year");
        Integer end = parseYear(endFiscalYear, "end_fiscal_year");
        if(Objects.isNull(start) && Objects.isNull(end)){
            return null;
        }
        if(Objects.isNull(start)){
            start = end;
        }
        if(Objects.isNull(end)){
            end = start;
        }
        if(start > end){
            throw new IllegalArgumentException("start_fiscal_year不能大于end_fiscal_year");
        }
        List<String> years = new ArrayList<>();
        for(int year = start; year <= end; year++){
            years.add(String.valueOf(year));
        }
        return String.join(",", years);
    }

    private Integer parseYear(String value, String name){
        if(StringUtils.isBlank(value)){
            return null;
        }
        try{
            int year = Integer.parseInt(value.trim());
            int current = Year.now().getValue() + 1;
            if(year < 1900 || year > current){
                throw new IllegalArgumentException(name + "不在合理范围内: " + value);
            }
            return year;
        }catch (NumberFormatException e){
            throw new IllegalArgumentException(name + "必须是四位年份: " + value);
        }
    }

    private String parseFiscalPeriods(String fiscalPeriods){
        if(StringUtils.isBlank(fiscalPeriods)){
            return DEFAULT_PERIODS;
        }
        Set<String> periods = new LinkedHashSet<>();
        for(String item : fiscalPeriods.split(",")){
            String period = item.trim().toUpperCase(Locale.ROOT);
            if(!Set.of("FY","Q1","Q2","Q3","Q4").contains(period)){
                throw new IllegalArgumentException("不支持的fiscal_period: " + item);
            }
            periods.add(period);
        }
        return String.join(",", periods);
    }

    private FinancialMetricsSourcePreference parseSourcePreference(String sourcePreference){
        if(StringUtils.isBlank(sourcePreference)){
            return FinancialMetricsSourcePreference.AUTO;
        }
        try{
            return FinancialMetricsSourcePreference.valueOf(sourcePreference.trim().toUpperCase(Locale.ROOT));
        }catch (Exception e){
            throw new IllegalArgumentException("source_preference必须是auto、online、cache或local");
        }
    }
}
