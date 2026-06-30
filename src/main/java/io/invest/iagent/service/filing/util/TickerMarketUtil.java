package io.invest.iagent.service.filing.util;

import io.invest.iagent.model.TickerMarket;

public class TickerMarketUtil {

    public static TickerMarket resolveMarket(String ticker) {
        if (ticker == null || ticker.isEmpty()) {
            throw new IllegalArgumentException("Ticker cannot be empty");
        }
        String cleanTicker = ticker.toUpperCase().trim();
        // 纯数字：判断是 A 股还是港股
        if (cleanTicker.matches("\\d+")) {
            if (cleanTicker.length() == 6) {
                return TickerMarket.CN_A;  // 6位数字是 A 股
            } else if (cleanTicker.length() == 5) {
                return TickerMarket.HK;  // 5位数字是港股
            }
        }
        // 包含字母：美股
        if (cleanTicker.matches("[A-Z]+")) {
            return TickerMarket.US;
        }
        throw new IllegalArgumentException("Cannot identify market for ticker: " + ticker);
    }
}
