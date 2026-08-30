package io.invest.iagent.financial.ingest;

import java.util.Locale;

/**
 * ticker 与 futu 代码（带市场前缀）互转工具。
 * futu 代码形如 HK.00700 / US.BABA / SH.600519；内部存储统一用裸 ticker（大写）。
 */
public final class FutuCodeUtil {

    private FutuCodeUtil() {}

    /** 裸 ticker -> futu 代码（按代码形态推断市场前缀）。 */
    public static String toFutuCode(String ticker) {
        String t = ticker.trim().toUpperCase(Locale.ROOT);
        if (t.contains(".")) {
            return t; // 已带前缀
        }
        if (t.matches("\\d{5}")) {
            return "HK." + t;
        }
        if (t.matches("\\d{6}")) {
            // 6 开头沪市，其余（0/3）深市
            return (t.startsWith("6") ? "SH." : "SZ.") + t;
        }
        return "US." + t;
    }

    /** futu 代码 -> 裸 ticker。 */
    public static String bareTicker(String futuCode) {
        int idx = futuCode.indexOf('.');
        return idx > 0 ? futuCode.substring(idx + 1).toUpperCase(Locale.ROOT)
                       : futuCode.toUpperCase(Locale.ROOT);
    }

    /** futu 代码 -> 市场（US/HK/SH/SZ，统一为 US/HK/CN）。 */
    public static String marketOf(String futuCode) {
        String prefix = futuCode.contains(".") ? futuCode.substring(0, futuCode.indexOf('.')).toUpperCase(Locale.ROOT) : "";
        return switch (prefix) {
            case "SH", "SZ" -> "CN";
            default -> prefix; // US / HK
        };
    }
}
