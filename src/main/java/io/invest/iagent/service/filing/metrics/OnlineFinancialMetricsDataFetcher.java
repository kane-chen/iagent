package io.invest.iagent.service.filing.metrics;

import io.invest.iagent.model.FinanceQueryParam;
import io.invest.iagent.model.FinancialIndexValueDTO;
import io.invest.iagent.service.filing.util.SecFilingDataUtil;

import java.util.List;

public class OnlineFinancialMetricsDataFetcher extends AbstractFinancialMetricsDataFetcher {

    public OnlineFinancialMetricsDataFetcher(SecFilingDataUtil secFilingDataUtil) {
        super(secFilingDataUtil);
    }

    @Override
    public FinancialMetricsSourcePreference source() {
        return FinancialMetricsSourcePreference.ONLINE;
    }

    @Override
    public List<FinancialIndexValueDTO> fetch(String ticker, FinanceQueryParam query) throws Exception {
        return queryCompanyFacts(ticker, query, true);
    }
}
