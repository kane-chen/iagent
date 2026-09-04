package io.invest.iagent.financial.service;

import io.invest.iagent.financial.report.downloader.ReportDownloader;
import io.invest.iagent.financial.report.enums.Market;
import io.invest.iagent.financial.report.enums.ReportType;
import io.invest.iagent.financial.report.model.ReportMeta;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.financial", name = "enabled", havingValue = "true")
public class FinancialReportService {

    @Autowired
    private List<ReportDownloader> downloaders ;

    private Map<Market,ReportDownloader> mapping ;

    @PostConstruct
    public void init() {
        mapping = downloaders.stream()
                .collect(Collectors.toMap(ReportDownloader::supportMarket, t -> t));
    }

    /**
     * 采集结果摘要（供工具层展示）。
     */
    public record DownloadResult(String ticker, boolean success, String message,
                              int periodCount, List<String> warnings) {}

    public DownloadResult downloadBatch(String ticker, List<ReportType> types, int startYear, int endYear) {
        try{
            // route
            Market market = this.marketType(ticker) ;
            types = this.formatReportTypes(types,market) ;
            ReportDownloader downloader = mapping.get(market) ;
            List<ReportMeta> reports = types.stream().map(t->downloader.download(ticker,t,startYear,endYear))
                    .flatMap(List::stream).toList() ;
            return new DownloadResult(ticker, true, null, reports.size(), null);
        } catch (Exception e) {
            log.error("财报下载失败: ticker={}", ticker, e);
            String warning = "下载失败: " + e.getMessage();
            return new DownloadResult(ticker, false, warning, 0, List.of(warning));
        }
    }

    private List<ReportType> formatReportTypes(List<ReportType> types, Market market){
        if(!CollectionUtils.isEmpty(types)){
            return types ;
        }
        if(Market.US.equals(market)){
            // 本土公司：10-K 年报 + 10-Q 季报。财年最后一季（如 Apple 7-9 月的财年 Q4）
            // 不再出 10-Q，其全年/期末数据只在 10-K 中，必须下载年报，否则该季缺失；
            // 中概股(FPI)：ANNUAL→20-F 年报，QUARTERLY→6-K 季度/全年业绩公告。
            return List.of(ReportType.ANNUAL, ReportType.QUARTERLY) ;
        }
        return List.of(ReportType.ANNUAL,ReportType.INTERIM,ReportType.INTERIM) ;
    }

    public DownloadResult download(String ticker, ReportType type, int startYear, int endYear) {
        try{
            List<ReportMeta> reports = this.doDownload(ticker,type,startYear,endYear) ;
            return new DownloadResult(ticker, true, null, reports.size(), null);
        } catch (Exception e) {
            log.error("财报下载失败: ticker={}", ticker, e);
            String warning = "下载失败: " + e.getMessage();
            return new DownloadResult(ticker, false, warning, 0, List.of(warning));
        }
    }

    private List<ReportMeta> doDownload(String stockCode, ReportType type, int startYear, int endYear) {
        // route
        ReportDownloader downloader = mapping.get(this.marketType(stockCode)) ;
        // meta
        return downloader.download(stockCode, type,startYear,endYear);
    }

    private Market marketType(String stockCode){
        if(StringUtils.isBlank(stockCode)){
            throw new IllegalArgumentException("invalid stockCode") ;
        }
        if(!StringUtils.isNumeric(stockCode)){
            return Market.US ;
        }
        if(stockCode.length() == 5){
            return Market.HK ;
        }
        if(stockCode.length() == 6){
            return Market.CN;
        }
        throw new IllegalArgumentException("invalid stockCode") ;
    }

}
