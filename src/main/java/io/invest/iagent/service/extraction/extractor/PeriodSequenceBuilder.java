package io.invest.iagent.service.extraction.extractor;

import io.invest.iagent.service.extraction.model.FinancialTable;
import io.invest.iagent.service.extraction.model.TableCell;
import io.invest.iagent.service.extraction.model.TableRow;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表头周期序列构造器 —— 从 {@link FinancialTable} 前若干行 header 里识别
 * "Three Months Ended / Nine Months Ended / Year Ended / Quarter ended ..."
 * 之类的周期分组标记，加上按位置扫描到的 4 位年份，产出与"从左到右非空数值 cell"一一对应的
 * 周期 code 列表（如 {@code [2024Q3, 2025Q2, 2025Q3, 2025Q3]}）。
 *
 * <p>这段算法原本在 {@link DataExtractor#extractMetricDataForRow} 私有方法里
 * ({@code DataExtractor.buildPeriodSequence})、也在 {@link SegmentContributionHandler}
 * 内部各自复制了一份；每次新增 HTML layout handler 都要再抄一遍。抽到这里让所有
 * {@link HtmlLayoutHandler} 复用同一份表头解析规则，避免行为漂移。</p>
 *
 * <p>Handler 通过 {@code defaultQuarter} 参数注入自己的兜底周期后缀（DataExtractor 用
 * {@code PeriodTypeUtil.determinePeriodType} 推断、BEKE 兜底 Q4、TCOM 用 table.period），
 * builder 只负责按 header 上下文把它套到没匹配到分组标记的列上。</p>
 *
 * <p>特殊分支：如果**同一个 header cell** 里既含 group 短语（如 {@code "Quarter ended"}）又含
 * 一个 4 位年份，还含一个月份关键词（如 {@code "September"}），则该 cell 直接产出
 * {@code year + monthToQuarter(month)} 而不参与"按分组均分/就近映射"算法。TCOM 之类每列
 * 表头都自包含 "Quarter ended &lt;Month&gt; &lt;Day&gt;, &lt;Year&gt;" 的表格靠这个分支得到
 * 每列各自的精确季度；BEKE/BABA 的表格里 group 短语和年份是拆到两个 cell 的，走原来的路径不受影响。</p>
 */
final class PeriodSequenceBuilder {

    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(20\\d{2})\\b");
    private static final int HEADER_SCAN_ROWS = 5;

    private PeriodSequenceBuilder() {}

    /**
     * 从表格前 5 行 header 里生成周期序列。
     *
     * @param table          源表格
     * @param defaultQuarter 兜底季度后缀（如 "Q3"）；表头没识别到分组标记 / 分组标记不匹配时使用
     * @return 与表格数据行"从左到右非空数值 cell"一一对应的周期 code 列表；空表示未识别到年份
     */
    static List<String> build(FinancialTable table, String defaultQuarter) {
        List<Column> cols = collectYearColumns(table, defaultQuarter);
        List<String> out = new ArrayList<>(cols.size());
        for (Column c : cols) out.add(c.period);
        return out;
    }

    /**
     * 返回与 {@link #build} 顺序一致的每列币种（未识别时为空串）。用于 handler 按"年份 cell 顺序"
     * 过滤跨币种同季重复列（TCOM 每季度会同时给一份 USD 换币，需要根据 config.defaultCurrency
     * 剔除掉）。BEKE/BABA 表头不带币种或只有一种币种时，本方法只影响是否命中"defaultCurrency"，
     * 不影响 handler 保守全接受的默认行为。
     */
    static List<String> buildCurrencies(FinancialTable table, String defaultQuarter) {
        List<Column> cols = collectYearColumns(table, defaultQuarter);
        List<String> out = new ArrayList<>(cols.size());
        for (Column c : cols) out.add(c.currency == null ? "" : c.currency);
        return out;
    }

    // ---------------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------------

    /** header 扫描的中间结果：一列（对应一个年份 cell）的 period 与 currency。 */
    private static final class Column {
        final int columnIdx;
        final String period;
        final String currency;

        Column(int columnIdx, String period, String currency) {
            this.columnIdx = columnIdx;
            this.period = period;
            this.currency = currency;
        }
    }

    private static List<Column> collectYearColumns(FinancialTable table, String defaultQuarter) {
        List<Column> out = new ArrayList<>();
        if (table == null || table.getRows() == null || table.getRows().isEmpty()) {
            return out;
        }

        List<int[]> groupColumns = new ArrayList<>();   // [列, suffix index]
        List<String> groupSuffixes = new ArrayList<>();
        // "自包含"列：cell 里同时有 group 短语 + 年份 → 直接算出 period 存这里，
        // 不再让它进入"分组年份分配"环节
        List<Column> selfContained = new ArrayList<>();
        // "仅年份"列：cell 里只有年份没有 group 短语 → 依旧走原来的分组算法
        List<int[]> plainYearColumns = new ArrayList<>(); // [列, 年份]
        List<String> plainYearCurrencies = new ArrayList<>();

        int headerRowEnd = Math.min(HEADER_SCAN_ROWS, table.getRows().size());
        for (int r = 0; r < headerRowEnd; r++) {
            TableRow headerRow = table.getRows().get(r);
            if (headerRow == null || headerRow.getCells() == null) continue;
            for (int c = 0; c < headerRow.getCells().size(); c++) {
                TableCell cell = headerRow.getCells().get(c);
                if (cell == null) continue;
                String cellText = cell.getText();
                if (cellText == null || cellText.trim().isEmpty()) continue;
                String lower = cellText.toLowerCase();

                String groupSuffix = classifyGroupSuffix(lower, defaultQuarter);
                Matcher m = YEAR_PATTERN.matcher(cellText);
                boolean hasYear = m.find();
                int year = hasYear ? Integer.parseInt(m.group(1)) : 0;

                if (groupSuffix != null && hasYear) {
                    // 自包含 cell —— 优先用同 cell 里的月份关键词派生 Q1..Q4，
                    // 否则退回 groupSuffix（等同于 defaultQuarter/FY 等）
                    String monthQuarter = monthToQuarter(lower);
                    String finalSuffix = monthQuarter != null ? monthQuarter : groupSuffix;
                    selfContained.add(new Column(c, year + finalSuffix, detectCurrency(lower)));
                    // 注意：不把这个 cell 再算作独立的 group 或 plain year，避免重复计入
                    continue;
                }
                if (groupSuffix != null) {
                    groupColumns.add(new int[]{c, groupSuffixes.size()});
                    groupSuffixes.add(groupSuffix);
                }
                if (hasYear) {
                    plainYearColumns.add(new int[]{c, year});
                    plainYearCurrencies.add(detectCurrency(lower));
                }
            }
        }

        // 处理 plain year 列（保持原有"按分组均分 / 就近映射"算法）
        if (!plainYearColumns.isEmpty()) {
            // 按列号升序
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < plainYearColumns.size(); i++) order.add(i);
            order.sort((a, b) -> Integer.compare(plainYearColumns.get(a)[0], plainYearColumns.get(b)[0]));
            groupColumns.sort((a, b) -> Integer.compare(a[0], b[0]));

            List<int[]> yearColumnsSorted = new ArrayList<>();
            List<String> yearCurrenciesSorted = new ArrayList<>();
            for (int i : order) {
                yearColumnsSorted.add(plainYearColumns.get(i));
                yearCurrenciesSorted.add(plainYearCurrencies.get(i));
            }

            if (groupSuffixes.isEmpty()) {
                // 没识别到任何分组标记 → 所有年份走 defaultQuarter
                for (int i = 0; i < yearColumnsSorted.size(); i++) {
                    int[] yc = yearColumnsSorted.get(i);
                    out.add(new Column(yc[0], yc[1] + defaultQuarter, yearCurrenciesSorted.get(i)));
                }
            } else {
                int numGroups = groupSuffixes.size();
                int numYears = yearColumnsSorted.size();
                if (numYears % numGroups == 0) {
                    int yearsPerGroup = numYears / numGroups;
                    for (int g = 0; g < numGroups; g++) {
                        String suffix = groupSuffixes.get(g);
                        for (int y = 0; y < yearsPerGroup; y++) {
                            int[] yc = yearColumnsSorted.get(g * yearsPerGroup + y);
                            out.add(new Column(yc[0], yc[1] + suffix,
                                    yearCurrenciesSorted.get(g * yearsPerGroup + y)));
                        }
                    }
                } else {
                    for (int i = 0; i < yearColumnsSorted.size(); i++) {
                        int[] yc = yearColumnsSorted.get(i);
                        int col = yc[0], year = yc[1];
                        String suffix = defaultQuarter;
                        for (int[] gc : groupColumns) {
                            if (gc[0] <= col) suffix = groupSuffixes.get(gc[1]);
                            else break;
                        }
                        out.add(new Column(col, year + suffix, yearCurrenciesSorted.get(i)));
                    }
                }
            }
        }

        // 合并 selfContained + plain year 列，按列号升序（"从左到右"就等于数据行数值出现顺序）
        out.addAll(selfContained);
        out.sort((a, b) -> Integer.compare(a.columnIdx, b.columnIdx));
        return out;
    }

    /**
     * 根据 header cell 文本给出分组后缀 —— 与 {@link DataExtractor} 原有识别集合完全一致。
     * {@code null} = 这个 cell 没有分组标记信号。
     */
    private static String classifyGroupSuffix(String lower, String defaultQuarter) {
        if (lower.contains("nine month") || lower.contains("9 month")
                || lower.contains("nine-month") || lower.contains("year to date")
                || lower.contains("ytd")) {
            return "QTD9";
        }
        if (lower.contains("six month") || lower.contains("6 month")
                || lower.contains("six-month")) {
            return "QTD6";
        }
        if (lower.contains("year ended") || lower.contains("twelve months")
                || lower.contains("12 months") || lower.contains("full year")
                || lower.contains("fiscal year")) {
            return "FY";
        }
        if (lower.contains("three month") || lower.contains("3 month")
                || lower.contains("quarter")) {
            return defaultQuarter;
        }
        return null;
    }

    /**
     * 从 header cell 文本里派生 Q1..Q4（月份关键词命中即返回；同 {@code PeriodTypeUtil} 语义）。
     * 只在**同一 cell 同时含 group 短语和年份**的自包含分支里调用，避免影响 BEKE/BABA 类
     * "月份和 group 短语在不同 cell"的现有行为。
     */
    private static String monthToQuarter(String lower) {
        if (lower.contains("march") || lower.contains("january") || lower.contains("february")) {
            return "Q1";
        }
        if (lower.contains("june") || lower.contains("april") || lower.contains("may")) {
            return "Q2";
        }
        if (lower.contains("september") || lower.contains("july") || lower.contains("august")) {
            return "Q3";
        }
        if (lower.contains("december") || lower.contains("october") || lower.contains("november")) {
            return "Q4";
        }
        return null;
    }

    /**
     * 识别 header cell 文本里的报告币种关键词（大小写不敏感）。返回币种 code（大写）或 null。
     * 关键词覆盖常见币种：RMB / USD / HKD / CNY / EUR / GBP / JPY。
     */
    private static String detectCurrency(String lower) {
        if (lower.contains("rmb") || lower.contains("cny")) return "RMB";
        if (lower.contains("usd") || lower.contains("us$")) return "USD";
        if (lower.contains("hkd") || lower.contains("hk$")) return "HKD";
        if (lower.contains("eur")) return "EUR";
        if (lower.contains("gbp")) return "GBP";
        if (lower.contains("jpy")) return "JPY";
        return null;
    }
}
