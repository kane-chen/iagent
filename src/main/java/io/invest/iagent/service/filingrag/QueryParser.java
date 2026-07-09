package io.invest.iagent.service.filingrag;

import org.apache.commons.lang3.StringUtils;

/**
 * Parses period strings like "2025Q1", "2024", "FY2025", "2024H1", "2024H2"
 * into (fiscalYear, fiscalPeriod) components.
 */
public final class QueryParser {

    private QueryParser() {}

    public static record ParsedPeriod(Integer fiscalYear, String fiscalPeriod) {}

    /**
     * Parse a period string.
     * <ul>
     *   <li>"2025" → (2025, null)</li>
     *   <li>"FY2025" / "2025FY" / "2025FY" → (2025, "FY")</li>
     *   <li>"2025Q1" / "2025-Q1" → (2025, "Q1")</li>
     *   <li>"2024H1" / "2024H2" → (2024, "H1"/"H2")</li>
     *   <li>null/blank → (null, null)</li>
     * </ul>
     */
    public static ParsedPeriod parsePeriod(String text) {
        if (StringUtils.isBlank(text)) {
            return new ParsedPeriod(null, null);
        }
        String s = text.trim().toUpperCase().replace("-", "").replace(" ", "");

        // Q pattern: 2025Q1 or Q12025
        java.util.regex.Matcher qm = java.util.regex.Pattern.compile("(\\d{4})Q([1-4])").matcher(s);
        if (qm.find()) {
            return new ParsedPeriod(Integer.parseInt(qm.group(1)), "Q" + qm.group(2));
        }
        qm = java.util.regex.Pattern.compile("Q([1-4])(\\d{4})").matcher(s);
        if (qm.find()) {
            return new ParsedPeriod(Integer.parseInt(qm.group(2)), "Q" + qm.group(1));
        }

        // H pattern: 2024H1
        java.util.regex.Matcher hm = java.util.regex.Pattern.compile("(\\d{4})H([12])").matcher(s);
        if (hm.find()) {
            return new ParsedPeriod(Integer.parseInt(hm.group(1)), "H" + hm.group(2));
        }

        // FY pattern: FY2025 or 2025FY
        java.util.regex.Matcher fym = java.util.regex.Pattern.compile("FY(\\d{4})").matcher(s);
        if (fym.find()) {
            return new ParsedPeriod(Integer.parseInt(fym.group(1)), "FY");
        }
        fym = java.util.regex.Pattern.compile("(\\d{4})FY").matcher(s);
        if (fym.find()) {
            return new ParsedPeriod(Integer.parseInt(fym.group(1)), "FY");
        }

        // Plain year
        java.util.regex.Matcher ym = java.util.regex.Pattern.compile("(\\d{4})").matcher(s);
        if (ym.find()) {
            return new ParsedPeriod(Integer.parseInt(ym.group(1)), null);
        }

        return new ParsedPeriod(null, null);
    }
}
