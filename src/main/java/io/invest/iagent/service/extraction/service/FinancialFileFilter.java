package io.invest.iagent.service.extraction.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.utils.WorkspacePaths;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 从 workspace/portfolio/<TICKER>/filings/... 里过滤出待解析的财报正文文件。
 *
 * 规则（对齐 futu-filing 的下载脚本）：
 * <ul>
 *   <li>HK / CN 市场：单一 PDF —— 目录里 {@code *.pdf} / {@code *.PDF}</li>
 *   <li>US 市场：SEC accession 目录里的两种正文之一：
 *     <ul>
 *       <li>{@code <lower_ticker>_yyyymmdd.htm[l]}  ——  10-K / 10-Q 主文件（如 {@code aapl-20260328.htm}）</li>
 *       <li>{@code *ex99-1.htm[l]}                  ——  6-K / 20-F 附带的业绩发布正文</li>
 *     </ul>
 *   </li>
 * </ul>
 * 财年过滤：{@code meta.json} 中的 {@code fiscal_year} 或 {@code fiscalYear} 与 [start, end] 比较（字符串比较，覆盖 4 位年份）。
 */
public class FinancialFileFilter {

    private final Path workspace;

    public FinancialFileFilter(Path workspace) {
        this.workspace = workspace;
    }

    public List<Path> filter(String ticker, String fiscalYearStart, String fiscalYearEnd) throws IOException {
        String normalizedTicker = ticker.toUpperCase(Locale.ROOT);
        Path filingsDir = WorkspacePaths.filingsDir(workspace, normalizedTicker);
        if (!Files.isDirectory(filingsDir)) {
            return List.of();
        }
        List<Path> values = new ArrayList<>();
        try (Stream<Path> stream = Files.list(filingsDir)) {
            for (Path filingDir : stream.filter(Files::isDirectory).toList()) {
                values.addAll(doFilter(filingDir, normalizedTicker, fiscalYearStart, fiscalYearEnd));
            }
        }
        return values;
    }

    private List<Path> doFilter(Path filingDir, String ticker,
                                String fiscalYearStart, String fiscalYearEnd) throws IOException {
        Path metaFile = filingDir.resolve("meta.json");
        if (!Files.exists(metaFile)) {
            return List.of();
        }
        JSONObject meta = JSON.parseObject(Files.readString(metaFile));
        if (!isActiveCompleteFiling(meta)) {
            return List.of();
        }
        if (!within(meta, fiscalYearStart, fiscalYearEnd)) {
            return List.of();
        }
        String market = readField(meta, "market");
        if(StringUtils.equalsIgnoreCase("US",market)){
            return usPrimaryFiles(filingDir, meta, ticker);
        }
        return pdfFiles(filingDir, meta);
    }

    /** meta.json 里可能出现同一字段的 snake_case / camelCase 两种命名，取任一非空。 */
    private String readField(JSONObject meta, String camelCase) {
        String v = meta.getString(camelCase);
        if (StringUtils.isNotBlank(v)) return v;
        String snake = camelCase.replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT);
        return meta.getString(snake);
    }

    private boolean within(JSONObject meta, String fiscalYearStart, String fiscalYearEnd) {
        String theYear = readField(meta, "fiscalYear");
        if (StringUtils.isBlank(theYear)) {
            return false;
        }
        if (StringUtils.isNotBlank(fiscalYearStart) && fiscalYearStart.compareTo(theYear) > 0) {
            return false;
        }
        if (StringUtils.isNotBlank(fiscalYearEnd) && fiscalYearEnd.compareTo(theYear) < 0) {
            return false;
        }
        return true;
    }

    private boolean isActiveCompleteFiling(JSONObject meta) {
        // 港股/A 股/新版 US filing 没有 ingest_complete 字段，默认视为已完成；只做 deleted 兜底
        if (!meta.containsKey("ingest_complete")) {
            return !meta.getBooleanValue("is_deleted") && !meta.getBooleanValue("deleted");
        }
        if (Boolean.FALSE.equals(meta.getBoolean("ingest_complete"))) {
            return false;
        }
        return !meta.getBooleanValue("is_deleted") && !meta.getBooleanValue("deleted");
    }

    /** HK / CN：目录里的 PDF 文件。 */
    private List<Path> pdfFiles(Path filingDir, JSONObject meta) {
        List<Path> pdfs = new ArrayList<>();
        for (String name : metaFileNames(meta)) {
            if (StringUtils.endsWithIgnoreCase(name, ".pdf")) {
                pdfs.add(filingDir.resolve(name));
            }
        }
        return distinctExisting(pdfs);
    }

    /** SEC 10-K/10-Q 主文件命名：{@code <lower_ticker>_yyyymmdd.htm[l]}，如 {@code aapl-20260328.htm}。 */
    private static Pattern usPrimaryDocPattern(String ticker) {
        String t = Pattern.quote(ticker.toLowerCase(Locale.ROOT));
        return Pattern.compile("^" + t + "[-_]\\d{8}\\.htm[l]?$", Pattern.CASE_INSENSITIVE);
    }

    /** SEC 6-K / 20-F 业绩发布：{@code *ex99-1.htm[l]}。 */
    private static final Pattern EX99_1_PATTERN =
            Pattern.compile(".*ex[-_]?99[-_]?1\\.htm[l]?$", Pattern.CASE_INSENSITIVE);

    /** US：优先取 10-K/10-Q 主文件，其次取 ex99-1 附件。两者都可能同时存在，但 10-K/10-Q 主文件即可覆盖财报正文。 */
    private List<Path> usPrimaryFiles(Path filingDir, JSONObject meta, String ticker) {
        Pattern primaryPattern = usPrimaryDocPattern(ticker);
        List<Path> primaries = new ArrayList<>();
        List<Path> ex99 = new ArrayList<>();
        for (String name : metaFileNames(meta)) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (primaryPattern.matcher(lower).matches()) {
                primaries.add(filingDir.resolve(name));
            } else if (EX99_1_PATTERN.matcher(lower).matches()) {
                ex99.add(filingDir.resolve(name));
            }
        }
        List<Path> result = new ArrayList<>();
        result.addAll(primaries);
        result.addAll(ex99);
        return distinctExisting(result);
    }

    /** 收集 meta.json 里所有出现过的文件名（兼容 files 数组 / primary_document / primaryFile.name）。 */
    private List<String> metaFileNames(JSONObject meta) {
        List<String> names = new ArrayList<>();

        JSONArray files = meta.getJSONArray("files");
        if (Objects.nonNull(files)) {
            for (int i = 0; i < files.size(); i++) {
                String name = files.getString(i);
                if (StringUtils.isNotBlank(name)) {
                    names.add(name);
                }
            }
        }

        String primaryDocument = meta.getString("primary_document");
        if (StringUtils.isNotBlank(primaryDocument)) {
            names.add(primaryDocument);
        }

        JSONObject primaryFile = meta.getJSONObject("primaryFile");
        if (primaryFile != null) {
            String name = primaryFile.getString("name");
            if (StringUtils.isNotBlank(name)) {
                names.add(name);
            }
        }

        return names;
    }

    private List<Path> distinctExisting(List<Path> files) {
        LinkedHashSet<Path> distinct = new LinkedHashSet<>();
        files.stream().filter(Files::exists).forEach(distinct::add);
        return new ArrayList<>(distinct);
    }
}
