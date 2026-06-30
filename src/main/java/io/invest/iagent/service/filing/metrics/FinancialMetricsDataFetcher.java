package io.invest.iagent.service.filing.metrics;

import io.invest.iagent.model.FinanceQueryParam;
import io.invest.iagent.model.FinancialIndexValueDTO;

import java.util.List;

public interface FinancialMetricsDataFetcher {
    FinancialMetricsSourcePreference source();

    List<FinancialIndexValueDTO> fetch(String ticker, FinanceQueryParam query) throws Exception;
}
