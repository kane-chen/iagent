package io.invest.iagent.service.filing.metrics;

import io.invest.iagent.model.FinanceQueryParam;
import io.invest.iagent.model.FinancialIndexValueDTO;
import io.invest.iagent.service.filing.model.SecFilingDataDTO;
import io.invest.iagent.service.filing.util.SecFilingDataUtil;
import io.invest.iagent.service.filing.util.SecFilingFilterUtil;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public abstract class AbstractFinancialMetricsDataFetcher implements FinancialMetricsDataFetcher {

    protected final SecFilingDataUtil secFilingDataUtil;

    protected AbstractFinancialMetricsDataFetcher(SecFilingDataUtil secFilingDataUtil) {
        this.secFilingDataUtil = secFilingDataUtil;
    }

    protected List<FinancialIndexValueDTO> queryCompanyFacts(String ticker, FinanceQueryParam query, boolean allowNetwork) throws Exception {
        SecFilingDataDTO filingData = secFilingDataUtil.fetchFinancialIndexValue(ticker, allowNetwork);
        Map<String, Pair<String, String>> indexAliasMap = secFilingDataUtil.buildTickerDict(ticker, null);
        List<FinancialIndexValueDTO> values = SecFilingFilterUtil.filter(filingData, query, null, indexAliasMap);
        return Objects.nonNull(values) ? values : List.of();
    }
}
