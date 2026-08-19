package io.invest.iagent.rag.filing.ingest;

import io.invest.iagent.rag.filing.model.FiscalPeriod;
import org.apache.commons.lang3.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 财报周期文本解析器
 * <p>支持形如 2025Q1 / Q12025 / 2024H1 / FY2025 / 2025FY / 2025 的输入，
 * 输出规范的周期字符串（{@link #canonical(String)}）与财年。
 */
public final class PeriodParser {

    private static final Pattern YYYY_QN = Pattern.compile("(\\d{4})Q([1-4])");
    private static final Pattern QN_YYYY = Pattern.compile("Q([1-4])(\\d{4})");
    private static final Pattern YYYY_HN = Pattern.compile("(\\d{4})H([12])");
    private static final Pattern FY_YYYY = Pattern.compile("FY(\\d{4})");
    private static final Pattern YYYY_FY = Pattern.compile("(\\d{4})FY");
    private static final Pattern YYYY = Pattern.compile("(\\d{4})");

    private PeriodParser() {}

    /**
     * 从文本中抽取周期，返回 (fiscalYear, period)；未识别返回 (null,null)。
     * period 取规范形式：YYYYQn / YYYYHn / FYyyyy。
     */
    public static ParsedPeriod parse(String text) {
        if (StringUtils.isBlank(text)) return new ParsedPeriod(null, null);
        String s = text.trim().toUpperCase().replace("-", "").replace(" ", "");

        Matcher m = YYYY_QN.matcher(s);
        if (m.find()) {
            int y = Integer.parseInt(m.group(1));
            return new ParsedPeriod(y, y + "Q" + m.group(2));
        }
        m = QN_YYYY.matcher(s);
        if (m.find()) {
            int y = Integer.parseInt(m.group(2));
            return new ParsedPeriod(y, y + "Q" + m.group(1));
        }
        m = YYYY_HN.matcher(s);
        if (m.find()) {
            int y = Integer.parseInt(m.group(1));
            return new ParsedPeriod(y, y + "H" + m.group(2));
        }
        m = FY_YYYY.matcher(s);
        if (m.find()) {
            int y = Integer.parseInt(m.group(1));
            return new ParsedPeriod(y, "FY" + y);
        }
        m = YYYY_FY.matcher(s);
        if (m.find()) {
            int y = Integer.parseInt(m.group(1));
            return new ParsedPeriod(y, "FY" + y);
        }
        m = YYYY.matcher(s);
        if (m.find()) {
            return new ParsedPeriod(Integer.parseInt(m.group(1)), null);
        }
        return new ParsedPeriod(null, null);
    }

    /**
     * 返回规范化周期字符串（如 "2026Q1"）；仅给出年份时返回 null（年份单独写入 fiscal_year 标签）。
     */
    public static String canonical(String text) {
        return parse(text).period();
    }

    /**
     * 解析为 {@link FiscalPeriod}，便于排序与相对计算。
     */
    public static FiscalPeriod parseFiscal(String text) {
        ParsedPeriod p = parse(text);
        return p.period() == null ? null : FiscalPeriod.parse(p.period());
    }

    /**
     * 解析结果。
     *
     * @param fiscalYear 财年（可空）
     * @param period     规范化周期（可空，如 "2026Q1"/"FY2025"）
     */
    public record ParsedPeriod(Integer fiscalYear, String period) {}
}
