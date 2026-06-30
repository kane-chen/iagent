package io.invest.iagent.service.filing.metrics;

import io.invest.iagent.model.FinanceQueryParam;
import io.invest.iagent.model.FinancialIndexValueDTO;
import io.invest.iagent.service.filing.util.SecFilingFilterUtil;

import java.util.ArrayList;
import java.util.List;

public class HybridFinancialMetricsDataFetcher implements FinancialMetricsDataFetcher {

    private final FinancialMetricsDataFetcher onlineFetcher;
    private final DownloadedFilingFinancialMetricsDataFetcher downloadedFilingFetcher;

    public HybridFinancialMetricsDataFetcher(FinancialMetricsDataFetcher onlineFetcher,
                                             DownloadedFilingFinancialMetricsDataFetcher downloadedFilingFetcher) {
        this.onlineFetcher = onlineFetcher;
        this.downloadedFilingFetcher = downloadedFilingFetcher;
    }

    @Override
    public FinancialMetricsSourcePreference source() {
        return FinancialMetricsSourcePreference.AUTO;
    }

    @Override
    public List<FinancialIndexValueDTO> fetch(String ticker, FinanceQueryParam query) throws Exception {
        List<FinancialIndexValueDTO> values = new ArrayList<>();
        try {
            values.addAll(onlineFetcher.fetch(ticker, query));
        } catch (Exception ignored) {
            // Local downloaded SEC filings are tried below.
        }
        values.addAll(downloadedFilingFetcher.fetch(ticker, query));
        return SecFilingFilterUtil.dedupe(values);
    }
}
