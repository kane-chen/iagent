package io.invest.iagent.service.filing.metrics;

import io.invest.iagent.model.FinanceQueryParam;
import io.invest.iagent.model.FinancialIndexValueDTO;
import io.invest.iagent.service.filing.util.SecFilingFilterUtil;

import java.util.ArrayList;
import java.util.List;

public class LocalFinancialMetricsDataFetcher implements FinancialMetricsDataFetcher {

    private final FinancialMetricsDataFetcher cacheFetcher;
    private final DownloadedFilingFinancialMetricsDataFetcher downloadedFilingFetcher;

    public LocalFinancialMetricsDataFetcher(FinancialMetricsDataFetcher cacheFetcher,
                                            DownloadedFilingFinancialMetricsDataFetcher downloadedFilingFetcher) {
        this.cacheFetcher = cacheFetcher;
        this.downloadedFilingFetcher = downloadedFilingFetcher;
    }

    @Override
    public FinancialMetricsSourcePreference source() {
        return FinancialMetricsSourcePreference.LOCAL;
    }

    @Override
    public List<FinancialIndexValueDTO> fetch(String ticker, FinanceQueryParam query) throws Exception {
        List<FinancialIndexValueDTO> values = new ArrayList<>();
        try {
            values.addAll(cacheFetcher.fetch(ticker, query));
        } catch (Exception ignored) {
            // Cache is optional in local mode; downloaded filings are tried next.
        }
        values.addAll(downloadedFilingFetcher.fetch(ticker, query));
        return SecFilingFilterUtil.dedupe(values);
    }
}
