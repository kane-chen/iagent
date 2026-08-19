package io.invest.iagent.rag.filing.model;

import org.apache.commons.lang3.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规范化的财报周期，支持排序与"前驱/去年同期"等相对计算。
 * <p>规范字符串形式：{@code YYYYQn}、{@code FYyyyy}、{@code YYYYHn}。
 * <p>排序键 {@code sortKey = year*10 + ordinal}：
 * Q1=1, Q2=2, H1=2, Q3=3, Q4=4, H2=4, FY=5（年内顺序：Q1 &lt; H1/Q2 &lt; Q3 &lt; H2/Q4 &lt; FY）。
 */
public record FiscalPeriod(int year, int ordinal, String canonical) implements Comparable<FiscalPeriod> {

    private static final Pattern Q = Pattern.compile("(\\d{4})Q([1-4])");
    private static final Pattern H = Pattern.compile("(\\d{4})H([12])");
    private static final Pattern FY = Pattern.compile("FY(\\d{4})");

    @Override
    public int compareTo(FiscalPeriod o) {
        return Integer.compare(sortKey(), o.sortKey());
    }

    public int sortKey() {
        return year * 10 + ordinal;
    }

    /** 上一期间（季度口径）。FY 无上期概念，返回 null。 */
    public FiscalPeriod previous() {
        return switch (ordinal) {
            case 1 -> new FiscalPeriod(year - 1, 4, (year - 1) + "Q4");
            case 2 -> new FiscalPeriod(year, 1, year + "Q1");
            case 3 -> new FiscalPeriod(year, 2, year + "Q2");
            case 4 -> new FiscalPeriod(year, 3, year + "Q3");
            default -> null;
        };
    }

    /** 去年同期。FY 去年为 FY(year-1)；H 保持 H；Q 保持 Q。 */
    public FiscalPeriod yearAgo() {
        if (ordinal == 5) {
            return new FiscalPeriod(year - 1, 5, "FY" + (year - 1));
        }
        String suffix = canonical.substring(4); // Qn / Hn
        return new FiscalPeriod(year - 1, ordinal, (year - 1) + suffix);
    }

    /**
     * 解析规范化周期字符串。无法识别返回 null。
     */
    public static FiscalPeriod parse(String text) {
        if (StringUtils.isBlank(text)) return null;
        String s = text.trim().toUpperCase().replace("-", "").replace(" ", "");
        Matcher m = Q.matcher(s);
        if (m.find()) {
            int y = Integer.parseInt(m.group(1));
            int q = Integer.parseInt(m.group(2));
            return new FiscalPeriod(y, q, y + "Q" + q);
        }
        m = H.matcher(s);
        if (m.find()) {
            int y = Integer.parseInt(m.group(1));
            int h = Integer.parseInt(m.group(2));
            return new FiscalPeriod(y, h == 1 ? 2 : 4, y + "H" + h);
        }
        m = FY.matcher(s);
        if (m.find()) {
            int y = Integer.parseInt(m.group(1));
            return new FiscalPeriod(y, 5, "FY" + y);
        }
        return null;
    }
}
