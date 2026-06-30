package io.invest.iagent.service.filing.metrics;

import io.invest.iagent.model.FinanceQueryParam;
import io.invest.iagent.model.FinancialIndexValueDTO;
import io.invest.iagent.service.filing.util.SecXbrlMetricExtractor;

import java.util.List;

public class DownloadedFilingFinancialMetricsDataFetcher {

    private final SecXbrlMetricExtractor localExtractor;

    public DownloadedFilingFinancialMetricsDataFetcher(SecXbrlMetricExtractor localExtractor) {
        this.localExtractor = localExtractor;
    }

    public List<FinancialIndexValueDTO> fetch(String ticker, FinanceQueryParam query) throws Exception {
        return localExtractor.extract(ticker, query);
    }
}
