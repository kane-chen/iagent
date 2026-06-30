package io.invest.iagent.service.kb.category;

import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;

public enum FilingContentCategory {
    FINANCIAL_STATEMENTS("financial_statements"),
    FINANCIAL_METRICS("financial_metrics"),
    BUSINESS_OPERATIONS("business_operations"),
    FINANCIAL_OPERATIONS("financial_operations"),
    OPERATING_RISKS("operating_risks"),
    GOVERNANCE_LEGAL("governance_legal"),
    MARKET_STRATEGY("market_strategy"),
    ESG_HUMAN_CAPITAL("esg_human_capital"),
    OTHER("other");

    private final String code;

    FilingContentCategory(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static String normalizeCode(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String normalized = value.trim().toLowerCase().replace('-', '_');
        return Arrays.stream(values())
                .filter(category -> category.code.equals(normalized) || category.name().equalsIgnoreCase(normalized))
                .findFirst()
                .map(FilingContentCategory::code)
                .orElse(normalized);
    }
}
