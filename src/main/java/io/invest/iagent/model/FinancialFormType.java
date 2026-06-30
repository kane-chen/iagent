package io.invest.iagent.model;

import java.util.Set;

public enum FinancialFormType {
    // 美股
    FORM_10K("10-K", TickerMarket.US),
    FORM_10Q("10-Q", TickerMarket.US),
    FORM_20F("20-F", TickerMarket.US),
    FORM_6K("6-K", TickerMarket.US),
    FORM_8K("8-K", TickerMarket.US),
    FORM_DEF14A("DEF 14A", TickerMarket.US),
    FORM_SC13D("SC 13D", TickerMarket.US),
    FORM_SC13G("SC 13G", TickerMarket.US),
    // A股/港股
    FY("FY", TickerMarket.CN_A, TickerMarket.HK),
    H1("H1", TickerMarket.CN_A, TickerMarket.HK),
    Q1("Q1", TickerMarket.CN_A, TickerMarket.HK),
    Q2("Q2", TickerMarket.CN_A, TickerMarket.HK),
    Q3("Q3", TickerMarket.CN_A, TickerMarket.HK),
    Q4("Q4", TickerMarket.CN_A, TickerMarket.HK);

    private final String code;
    private final Set<TickerMarket> supportedMarkets;

    FinancialFormType(String code, TickerMarket... markets) {
        this.code = code;
        this.supportedMarkets = Set.of(markets);
    }

    public String getCode() {
        return code;
    }

    public boolean isSupported(TickerMarket market) {
        return supportedMarkets.contains(market);
    }

    public static FinancialFormType fromCode(String code) {
        for (FinancialFormType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("不支持的表单类型: " + code);
    }
}