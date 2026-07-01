package io.invest.iagent.service.extraction.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.invest.iagent.service.extraction.model.CompanyConfig;
import io.invest.iagent.service.extraction.model.FinancialTable;
import io.invest.iagent.service.extraction.model.Segment;
import io.invest.iagent.service.extraction.model.SegmentMetric;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PDF 财报解析器
 *
 * 港股财报 PDF 常常存在中文字体编码问题，PDFBox 直接读取会出现乱码。
 * 因此该实现通过调用 Python 脚本（{@code extract_pdf_tables.py}）来提取表格结构，
 * 脚本内部按优先级 camelot-lattice → camelot-stream → pdfplumber-lines →
 * pdfplumber-text 多引擎抽取并去重，最大化保留表格列结构；
 * 然后基于 {@link CompanyConfig#getPdfColumnMappings()} 中预定义的映射，
 * 把表格直接转成带 metric/period 的 {@link Segment} 列表。
 *
 * 因为中文标签全是字体乱码无法靠正文识别，所以采用"位置映射"策略：
 *   - SEGMENTS_AS_COLUMNS：每列一个分部、每行一个指标，单一周期
 *   - SEGMENTS_AS_ROWS：每行一个分部、每列一个周期，单一指标
 * 周期通过 filing 目录名（如 {@code fil_hk_00700_2025_H1}）解析出来，
 * 占位符 {@code CURRENT_Q / PRIOR_Q / CURRENT_P / PRIOR_P} 在运行时绑定。
 *
 * Python 可执行文件路径可通过系统属性 {@code iagent.python.bin} 或环境变量
 * {@code IAGENT_PYTHON_BIN} 覆盖，默认使用 PATH 中的 {@code python}。
 */
@Slf4j
public class PdfReportParser extends ReportParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** filing 目录名格式：fil_hk_<ticker>_<year>_<period>，如 fil_hk_00700_2025_H1 */
    private static final Pattern FILING_DIR_PATTERN =
            Pattern.compile("fil_hk_[^_]+_(\\d{4})_([A-Z0-9]+)", Pattern.CASE_INSENSITIVE);

    /**
     * 公司配置，含 PDF 列映射。由 FinancialExtractionService 在构造服务时注入。
     */
    @Setter
    private CompanyConfig companyConfig;

    /**
     * 工作区目录，用于定位 Python 脚本。
     */
    @Setter
    private Path workspace;

    /**
     * 从 PDF 文件解析出 Segment 列表（含 metric/period）。
     * 直接生成最终模型，绕过 {@link io.invest.iagent.service.extraction.extractor.DataExtractor}，
     * 因为 PDF 因字体编码缺失没有可识别的中文标签，只能靠位置映射。
     */
    public List<Segment> parseSegments(File file) throws IOException {
        log.info("Parsing PDF report file via multi-engine extractor: {}", file.getName());

        Path scriptPath = locatePythonScript();
        if (scriptPath == null || !Files.exists(scriptPath)) {
            log.warn("Python extraction script not found, returning empty result for: {}", file.getName());
            return new ArrayList<>();
        }

        JsonNode pythonResult = runPythonScript(scriptPath, file);
        if (pythonResult == null || !pythonResult.has("tables")) {
            log.warn("No tables returned from python script for: {}", file.getName());
            return new ArrayList<>();
        }

        if (pythonResult.has("engines")) {
            log.info("PDF extractor used engines: {}", pythonResult.get("engines"));
        }

        FilingContext context = parseFilingContext(file);
        log.info("Filing context for {}: {}", file.getName(), context);

        // segmentCode -> Segment（按 segmentCode 合并所有命中表的指标）
        Map<String, Segment> segmentsByCode = new LinkedHashMap<>();

        JsonNode tablesNode = pythonResult.get("tables");
        int totalTables = tablesNode.size();

        // 表消费策略：每张表只被"一条 mapping"独占使用 —— 避免同一 (page, shape)
        // 的分部数字块被 REVENUE 和 GROSS_PROFIT 两个映射同时写入导致值互相覆盖
        // （例如腾讯 Q3 的 page 6 REVENUE 表和 page 7 GROSS_PROFIT 表结构相同）。
        //
        // 遍历顺序：mapping 外 × table 内。第一条能命中该 mapping 的 table 就消费掉，
        // 下一条 mapping 只在剩下的未消费 table 里挑选。
        Set<Integer> consumedTables = new java.util.HashSet<>();
        int totalMatched = 0;
        if (companyConfig != null && companyConfig.getPdfColumnMappings() != null) {
            for (CompanyConfig.PdfColumnMapping mapping : companyConfig.getPdfColumnMappings()) {
                if (!filingPeriodMatches(mapping, context)) continue;

                for (int i = 0; i < totalTables; i++) {
                    if (consumedTables.contains(i)) continue;
                    JsonNode tableJson = tablesNode.get(i);
                    int hits = applySingleMapping(mapping, tableJson, context, segmentsByCode);
                    if (hits > 0) {
                        consumedTables.add(i);
                        totalMatched++;
                        log.debug("Mapping {} (metric={}) consumed table {} → +{} metrics",
                                mapping.getLayout(), extractMetricLabel(mapping),
                                tableJson.path("tableId").asText("unknown"), hits);
                        // 该 mapping 已经拿到一张匹配的表，不再继续 —— 避免同一 mapping
                        // 把多张同型 table（比如 pdfplumber-text 和 camelot-stream 各出一份）
                        // 重复写入。
                        break;
                    }
                }
            }
        }

        log.info("PDF mapping consumed {} tables out of {}, produced {} segments",
                totalMatched, totalTables, segmentsByCode.size());
        return new ArrayList<>(segmentsByCode.values());
    }

    private String extractMetricLabel(CompanyConfig.PdfColumnMapping mapping) {
        if (mapping.getMetricCode() != null) return mapping.getMetricCode();
        if (mapping.getMetricCodesByRow() != null && !mapping.getMetricCodesByRow().isEmpty()) {
            return String.join("+", mapping.getMetricCodesByRow());
        }
        return "?";
    }

    /**
     * 兼容旧接口：返回空表列表 —— PDF 路径现在直接走 {@link #parseSegments(File)}，
     * 不再借用 HTML 的 FinancialTable / DataExtractor 通路。
     */
    @Override
    public List<FinancialTable> parse(File file) throws IOException {
        // 保留入口，但不再产出 FinancialTable —— FinancialExtractionService 已切到 parseSegments
        return new ArrayList<>();
    }

    @Override
    public List<FinancialTable> parseHtml(String htmlContent) {
        return new ArrayList<>();
    }

    @Override
    public boolean supports(String format) {
        return "pdf".equalsIgnoreCase(format);
    }

    /**
     * 用单条 mapping 尝试消费单张表：命中即把数据写入 segmentsByCode，
     * 返回本次产生的指标条数。
     *
     * 拆成"单 mapping × 单表"是为了让外层能实现"每张表只被一条 mapping 独占"
     * 的分配策略 —— 见 {@link #parseSegments(File)} 中的 consumedTables 逻辑。
     */
    private int applySingleMapping(CompanyConfig.PdfColumnMapping mapping,
                                   JsonNode tableJson, FilingContext context,
                                   Map<String, Segment> segmentsByCode) {
        String tableId = tableJson.path("tableId").asText("unknown");
        String currency = tableJson.path("currency").asText(null);
        String unit = tableJson.path("unit").asText(null);

        JsonNode dataRowsNode = tableJson.path("dataRows");
        if (!dataRowsNode.isArray() || dataRowsNode.size() == 0) {
            return 0;
        }

        // 把 dataRows 转成 List<List<String>>
        List<List<String>> dataRows = new ArrayList<>();
        for (JsonNode rowNode : dataRowsNode) {
            List<String> rowCells = new ArrayList<>();
            for (JsonNode cellNode : rowNode) {
                rowCells.add(cellNode.asText(""));
            }
            dataRows.add(rowCells);
        }

        // mapping.columnCount 的解释依 layout 而异：
        //   SEGMENTS_AS_COLUMNS: 整行数据列数（无标签列）
        //   SEGMENTS_AS_ROWS:    标签后的数据列数
        // 匹配检测放在各 apply 方法内部做，因为 raw columnCount 在 ROWS 情况下要加 1
        return switch (mapping.getLayout()) {
            case SEGMENTS_AS_COLUMNS ->
                    applySegmentsAsColumns(mapping, dataRows, tableId, currency, unit,
                            context, segmentsByCode);
            case SEGMENTS_AS_ROWS ->
                    applySegmentsAsRows(mapping, dataRows, tableId, currency, unit,
                            context, segmentsByCode);
        };
    }

    /**
     * mapping 是否命中当前 filing 的 period（H1/Q1/Q3/FY/...）。
     * 空 filingPeriods 视为不限制。
     */
    private boolean filingPeriodMatches(CompanyConfig.PdfColumnMapping mapping, FilingContext context) {
        List<String> allowed = mapping.getFilingPeriods();
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        if (context == null || context.period == null || context.period.isEmpty()) {
            // filing period 未知：谨慎起见跳过带白名单的 mapping
            return false;
        }
        for (String p : allowed) {
            if (p != null && p.equalsIgnoreCase(context.period)) {
                return true;
            }
        }
        return false;
    }

    /**
     * SEGMENTS_AS_COLUMNS 布局：每列=分部、每行=指标，单一周期（filing 的当期季度）。
     * 例：腾讯财报 page 44 五列 [VAS, ONLINE_ADS, FINTECH, OTHERS, TOTAL]，
     *     多行 [REVENUE, GROSS_PROFIT, ...]，对应当期。
     *
     * 严格要求避免误匹配：
     *   - 行长度恰好等于 segmentCodes.size()
     *   - 整行没有标签列（所有 cell 必须是纯数字或占位符）
     *   - 至少能凑齐 metricCodesByRow.size() 个这种行
     */
    private int applySegmentsAsColumns(CompanyConfig.PdfColumnMapping mapping,
                                       List<List<String>> dataRows,
                                       String tableId, String currency, String unit,
                                       FilingContext context,
                                       Map<String, Segment> segmentsByCode) {
        List<String> segmentCodes = mapping.getSegmentCodes();
        List<String> metricCodes = mapping.getMetricCodesByRow();
        int expectedCols = mapping.getColumnCount();
        if (segmentCodes == null || segmentCodes.isEmpty()
                || metricCodes == null || metricCodes.isEmpty()
                || expectedCols <= 0
                || segmentCodes.size() != expectedCols) {
            return 0;
        }

        // 收集"整行都是数字、列数等于 expectedCols"的行
        // 这条规则只在"分部汇总数字块"出现（如 5 个分部的当期数字）
        List<List<String>> allNumericRows = new ArrayList<>();
        for (List<String> row : dataRows) {
            if (row.size() != expectedCols) continue;
            if (isAllNumericRow(row)) {
                allNumericRows.add(row);
            }
        }
        if (allNumericRows.size() < metricCodes.size()) {
            return 0;
        }

        // 严格一致性校验：每一行中"非 TOTAL 列"之和 ≈ "TOTAL 列"值
        // 这是港股分部数字块的固有特征，能可靠区分真分部表和 5 列数字噪声块。
        int totalIdx = lastIndexOfTotal(segmentCodes);
        if (totalIdx >= 0) {
            int passing = 0;
            for (int rowIdx = 0; rowIdx < metricCodes.size(); rowIdx++) {
                List<String> row = allNumericRows.get(rowIdx);
                if (verifyTotalCell(row, totalIdx, segmentCodes)) {
                    passing++;
                }
            }
            // 至少一半的指标行通过校验，才认为这是真的分部表
            if (passing * 2 < metricCodes.size()) {
                return 0;
            }
        }

        // 当期季度（H1 报对应 Q2，FY 报对应 Q4）
        String period = context.currentQuarter();
        if (period == null || period.isEmpty()) {
            return 0;
        }

        int produced = 0;
        for (int rowIdx = 0; rowIdx < metricCodes.size(); rowIdx++) {
            String metricCode = metricCodes.get(rowIdx);
            List<String> rowCells = allNumericRows.get(rowIdx);
            for (int colIdx = 0; colIdx < segmentCodes.size(); colIdx++) {
                String segCode = segmentCodes.get(colIdx);
                if (isSkipColumn(segCode)) {
                    continue;
                }
                Double value = parseNumber(rowCells.get(colIdx));
                if (value == null) {
                    continue;
                }
                addMetric(segmentsByCode, segCode, metricCode, period, value,
                        tableId, currency, unit);
                produced++;
            }
        }
        return produced;
    }

    /**
     * SEGMENTS_AS_ROWS 布局：每行=分部、每列=周期，单一指标。
     * 例：腾讯财报 page 46 五行 [VAS, ONLINE_ADS, FINTECH, OTHERS, TOTAL]，
     *     每列一个周期。首列是分部标签（中文乱码无法识别）。
     *
     * mapping.columnCount = 标签列之后的数据列数（不含标签列）。
     *
     * 严格要求避免误匹配：
     *   - 行总长度 == 1 + columnCount
     *   - 首格是非数字（标签）
     *   - 后续 columnCount 格全部是数字
     *   - 至少凑齐 segmentCodes.size() 这种行
     */
    private int applySegmentsAsRows(CompanyConfig.PdfColumnMapping mapping,
                                    List<List<String>> dataRows,
                                    String tableId, String currency, String unit,
                                    FilingContext context,
                                    Map<String, Segment> segmentsByCode) {
        List<String> segmentCodes = mapping.getSegmentCodes();
        List<String> periodCodes = mapping.getPeriodCodesByColumn();
        String metricCode = mapping.getMetricCode();
        int expectedDataCols = mapping.getColumnCount();
        if (segmentCodes == null || segmentCodes.isEmpty()
                || periodCodes == null || periodCodes.isEmpty()
                || metricCode == null || expectedDataCols <= 0
                || periodCodes.size() != expectedDataCols) {
            return 0;
        }

        int totalCols = 1 + expectedDataCols;
        List<List<String>> qualifiedRows = new ArrayList<>();
        for (List<String> row : dataRows) {
            if (row.size() != totalCols) continue;
            // 首格必须不是数字（必须是标签 —— 中文乱码也算"非数字"）
            if (parseNumber(row.get(0)) != null) continue;
            // 我们只要求"需要取的列"（periodCodes 里非空的位置）是数字；
            // 其他列允许是 % / 空 / 占位符（如港股报表的同比、占比列）
            boolean validForMapping = true;
            for (int i = 0; i < expectedDataCols; i++) {
                String code = periodCodes.get(i);
                String cell = row.get(i + 1);
                if (code == null || code.isEmpty()) {
                    // 这列被配置忽略，cell 是什么都行
                    continue;
                }
                if (parseNumber(cell) == null) {
                    validForMapping = false;
                    break;
                }
            }
            if (validForMapping) {
                qualifiedRows.add(row);
            }
        }
        if (qualifiedRows.size() < segmentCodes.size()) {
            return 0;
        }

        // 严格一致性校验：如果 segmentCodes 末尾是 "TOTAL"，要求每一列上
        // 前 N-1 行的和 == 第 N 行的值（容忍 ±1 误差，由于四舍五入）。
        // 这是港股分部表的固有特征，能可靠区分"合计行"和无关的 5×N 数字块。
        int totalIdx = lastIndexOfTotal(segmentCodes);
        if (totalIdx >= 0 && totalIdx < qualifiedRows.size()) {
            if (!verifyTotalRow(qualifiedRows, totalIdx, expectedDataCols, segmentCodes)) {
                return 0;
            }
        }

        // 解析每列对应的 period
        List<String> resolvedPeriods = new ArrayList<>();
        for (String code : periodCodes) {
            resolvedPeriods.add(context.resolvePeriod(code));
        }

        int produced = 0;
        for (int rowIdx = 0; rowIdx < segmentCodes.size(); rowIdx++) {
            String segCode = segmentCodes.get(rowIdx);
            if (isSkipColumn(segCode)) {
                continue;
            }
            List<String> rowCells = qualifiedRows.get(rowIdx);
            for (int colIdx = 0; colIdx < expectedDataCols; colIdx++) {
                String period = resolvedPeriods.get(colIdx);
                if (period == null || period.isEmpty()) {
                    continue;
                }
                Double value = parseNumber(rowCells.get(colIdx + 1)); // +1 跳过 label
                if (value == null) {
                    continue;
                }
                addMetric(segmentsByCode, segCode, metricCode, period, value,
                        tableId, currency, unit);
                produced++;
            }
        }
        return produced;
    }

    /** 整行单元格全部是数字（允许个别空串/占位符）；至少要有 2 个真数字才算数。 */
    private boolean isAllNumericRow(List<String> row) {
        int numeric = 0;
        for (String c : row) {
            if (parseNumber(c) != null) {
                numeric++;
            } else if (!isPlaceholderCell(c)) {
                return false;
            }
        }
        return numeric >= 2;
    }

    /**
     * 校验单行：除 totalIdx 列外其他分部列之和 ≈ totalIdx 列的值。
     * 用于 SEGMENTS_AS_COLUMNS 布局的"合计列一致性"检测。
     */
    private boolean verifyTotalCell(List<String> row, int totalIdx, List<String> segmentCodes) {
        if (totalIdx < 0 || totalIdx >= row.size()) return false;
        Double totalVal = parseNumber(row.get(totalIdx));
        if (totalVal == null) return false;
        double sum = 0;
        int contributors = 0;
        for (int col = 0; col < segmentCodes.size() && col < row.size(); col++) {
            if (col == totalIdx) continue;
            String code = segmentCodes.get(col);
            if (code == null || code.isEmpty() || "TOTAL".equalsIgnoreCase(code)) continue;
            Double v = parseNumber(row.get(col));
            if (v == null) continue;
            sum += v;
            contributors++;
        }
        if (contributors < 2) return false;
        double diff = Math.abs(sum - totalVal);
        double tolerance = Math.max(1.0, Math.abs(totalVal) * 0.005);
        return diff <= tolerance;
    }

    /**
     * 找 segmentCodes 里 "TOTAL" 占位符的下标（不区分大小写）；找不到返回 -1。
     */
    private int lastIndexOfTotal(List<String> segmentCodes) {
        for (int i = segmentCodes.size() - 1; i >= 0; i--) {
            String c = segmentCodes.get(i);
            if (c != null && "TOTAL".equalsIgnoreCase(c)) return i;
        }
        return -1;
    }

    /**
     * 校验"合计行" {@code qualifiedRows.get(totalIdx)} 的每一列值 ≈ 同列其他行（按 segmentCodes 标识）之和。
     * 容忍 ±1（int 四舍五入）和 ±5% 的小幅误差，避免数据轻微取整后误判。
     * 至少要求一列通过校验；全部失败则视为"不是分部表"。
     */
    private boolean verifyTotalRow(List<List<String>> qualifiedRows, int totalIdx,
                                   int dataCols, List<String> segmentCodes) {
        List<String> totalRow = qualifiedRows.get(totalIdx);
        int passedColumns = 0;
        for (int col = 0; col < dataCols; col++) {
            Double totalVal = parseNumber(totalRow.get(col + 1));
            if (totalVal == null) continue;
            double sum = 0;
            int contributors = 0;
            for (int r = 0; r < segmentCodes.size() && r < qualifiedRows.size(); r++) {
                if (r == totalIdx) continue;
                String code = segmentCodes.get(r);
                if (code == null || code.isEmpty() || "TOTAL".equalsIgnoreCase(code)) continue;
                Double v = parseNumber(qualifiedRows.get(r).get(col + 1));
                if (v == null) continue;
                sum += v;
                contributors++;
            }
            if (contributors < 2) continue;
            double diff = Math.abs(sum - totalVal);
            double tolerance = Math.max(1.0, Math.abs(totalVal) * 0.005); // 0.5% 容差
            if (diff <= tolerance) {
                passedColumns++;
            }
        }
        return passedColumns >= 1;
    }

    private boolean isPlaceholderCell(String c) {
        if (c == null) return true;
        String s = c.trim();
        return s.isEmpty() || "-".equals(s) || "–".equals(s) || "—".equals(s) || "*".equals(s);
    }

    private boolean isSkipColumn(String segCode) {
        return segCode == null || segCode.isEmpty() || "TOTAL".equalsIgnoreCase(segCode);
    }

    /**
     * 把一条指标加到 segmentsByCode 里。如果该 (segment, metric, period) 已经存在，
     * 则保留第一份（多个表可能重复抓到同一格数据）。
     */
    private void addMetric(Map<String, Segment> segmentsByCode,
                           String segCode, String metricCode, String period, double value,
                           String tableId, String currency, String unit) {
        Segment segment = segmentsByCode.computeIfAbsent(segCode, code -> {
            Segment s = new Segment();
            s.setSegmentCode(code);
            // 从 companyConfig.segments 取名/层级，没有则给默认值
            CompanyConfig.SegmentConfig sc = findSegmentConfig(code);
            if (sc != null) {
                s.setSegmentName(sc.getSegmentName());
                s.setLevel(sc.getLevel() <= 0 ? 1 : sc.getLevel());
            } else {
                s.setSegmentName(code);
                s.setLevel(1);
            }
            return s;
        });

        // 去重：同 (metric, period) 已存在则跳过
        if (segment.getMetric(metricCode, period) != null) {
            return;
        }

        SegmentMetric metric = new SegmentMetric();
        metric.setMetricCode(metricCode);
        metric.setMetricName(metricCode);
        metric.setValue(value);
        metric.setPeriod(period);
        metric.setSourceType("TABLE_EXTRACT");
        metric.setSourceLocation(tableId);
        metric.setCurrency(currency);
        metric.setUnit(unit != null ? unit : "million");
        metric.setConfidenceScore(80);
        segment.addMetric(metric);
    }

    private CompanyConfig.SegmentConfig findSegmentConfig(String segmentCode) {
        if (companyConfig == null || companyConfig.getSegments() == null) {
            return null;
        }
        for (CompanyConfig.SegmentConfig sc : companyConfig.getSegments()) {
            if (sc.getSegmentCode() != null && sc.getSegmentCode().equalsIgnoreCase(segmentCode)) {
                return sc;
            }
        }
        return null;
    }

    /**
     * 解析数值：去千分位、负号、括号负数、空格。无法解析返回 null。
     */
    private Double parseNumber(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        // 跳过明显非数字（百分号、占位破折号）
        if (t.endsWith("%") || "-".equals(t) || "–".equals(t) || "—".equals(t) || "*".equals(t)) {
            return null;
        }
        boolean negative = false;
        if (t.startsWith("(") && t.endsWith(")")) {
            negative = true;
            t = t.substring(1, t.length() - 1);
        }
        t = t.replace(",", "").replace(" ", "");
        if (t.startsWith("-")) {
            negative = true;
            t = t.substring(1);
        }
        if (t.isEmpty()) return null;
        try {
            double v = Double.parseDouble(t);
            return negative ? -v : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 定位 Python 提取脚本，按以下顺序查找：
     * 1. workspace/skills/segment-financial-report/scripts/extract_pdf_tables.py
     * 2. 项目根目录的 workspace 同上路径
     */
    private Path locatePythonScript() {
        if (workspace != null) {
            Path candidate = workspace.resolve("skills")
                    .resolve("segment-financial-report")
                    .resolve("scripts")
                    .resolve("extract_pdf_tables.py");
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        Path fallback = Path.of(System.getProperty("user.dir"))
                .resolve("workspace")
                .resolve("skills")
                .resolve("segment-financial-report")
                .resolve("scripts")
                .resolve("extract_pdf_tables.py");
        return Files.exists(fallback) ? fallback : null;
    }

    /**
     * 调用 Python 脚本提取表格，返回解析后的 JSON 节点
     */
    private JsonNode runPythonScript(Path scriptPath, File pdfFile) throws IOException {
        Path tempOutput = Files.createTempFile("pdf_extract_", ".json");
        try {
            String pythonBin = resolvePythonBin();
            ProcessBuilder pb = new ProcessBuilder(
                    pythonBin,
                    scriptPath.toAbsolutePath().toString(),
                    pdfFile.getAbsolutePath(),
                    "--output", tempOutput.toAbsolutePath().toString(),
                    "--max-pages", "100"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            int exitCode;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Python script interrupted", e);
            }
            if (exitCode != 0) {
                log.warn("Python script ({}) failed with exit code {}: {}",
                        pythonBin, exitCode, output);
                return null;
            }
            if (output.length() > 0) {
                log.debug("Python script stderr/stdout:\n{}", output);
            }
            if (!Files.exists(tempOutput) || Files.size(tempOutput) == 0) {
                log.warn("Python script did not produce output file: {}", tempOutput);
                return null;
            }
            return MAPPER.readTree(Files.readAllBytes(tempOutput));
        } finally {
            try {
                Files.deleteIfExists(tempOutput);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 解析 Python 可执行文件路径：
     *   优先 -Diagent.python.bin → 环境变量 IAGENT_PYTHON_BIN → 默认 "python"
     */
    private String resolvePythonBin() {
        String prop = System.getProperty("iagent.python.bin");
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }
        String env = System.getenv("IAGENT_PYTHON_BIN");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return "python";
    }

    /**
     * 从 PDF 文件路径解析 filing context（年份+周期类型），格式：
     *     .../portfolio/<ticker>/filings/fil_hk_<ticker>_<year>_<period>/<file>.pdf
     * 解析失败时回退到 currentYear=0 / period=空，下游会跳过周期占位符。
     */
    static FilingContext parseFilingContext(File pdfFile) {
        if (pdfFile == null) {
            return FilingContext.empty();
        }
        File parent = pdfFile.getParentFile();
        if (parent == null) {
            return FilingContext.empty();
        }
        Matcher m = FILING_DIR_PATTERN.matcher(parent.getName());
        if (!m.find()) {
            return FilingContext.empty();
        }
        int year = Integer.parseInt(m.group(1));
        String period = m.group(2).toUpperCase(Locale.ROOT);
        return new FilingContext(year, period);
    }

    /**
     * Filing 的报告周期上下文：包含年份和周期类型（H1/FY/Q1/Q2/Q3/Q4），
     * 用来把 PdfColumnMapping 里的占位符（CURRENT_Q/PRIOR_Q/CURRENT_P/PRIOR_P）
     * 解析为实际的 period 字符串如 "2025Q2"。
     */
    static final class FilingContext {
        final int year;        // 0 表示未知
        final String period;   // H1/FY/Q1/Q2/Q3/Q4，"" 表示未知

        FilingContext(int year, String period) {
            this.year = year;
            this.period = period == null ? "" : period;
        }

        static FilingContext empty() {
            return new FilingContext(0, "");
        }

        /** 当期"季度"：H1 报 → Q2，FY 报 → Q4，其他保持原样 */
        String currentQuarter() {
            if (year <= 0) return "";
            switch (period) {
                case "H1": return year + "Q2";
                case "H2": return year + "Q4";
                case "FY": return year + "Q4";
                case "Q1":
                case "Q2":
                case "Q3":
                case "Q4":
                    return year + period;
                default: return year + period;
            }
        }

        /** 上年同季 */
        String priorQuarter() {
            if (year <= 0) return "";
            String cur = currentQuarter();
            // 取后 2 位的"Qx"，前面年份-1
            if (cur.length() >= 6) {
                String tail = cur.substring(cur.length() - 2);
                return (year - 1) + tail;
            }
            return "";
        }

        /** 当期周期（保留 H1/FY 等） */
        String currentPeriod() {
            if (year <= 0 || period.isEmpty()) return "";
            return year + period;
        }

        /** 上年同期 */
        String priorPeriod() {
            if (year <= 0 || period.isEmpty()) return "";
            return (year - 1) + period;
        }

        /**
         * 把 mapping 里的 period code 解析为具体 period 字符串。
         * 支持占位符 CURRENT_Q / PRIOR_Q / CURRENT_P / PRIOR_P，
         * 也支持写死的字符串如 "2025Q2"。
         */
        String resolvePeriod(String code) {
            if (code == null || code.isEmpty()) return "";
            switch (code) {
                case "CURRENT_Q": return currentQuarter();
                case "PRIOR_Q": return priorQuarter();
                case "CURRENT_P": return currentPeriod();
                case "PRIOR_P": return priorPeriod();
                default: return code; // 写死值
            }
        }

        @Override
        public String toString() {
            return "FilingContext{year=" + year + ", period='" + period + "', currentQ="
                    + currentQuarter() + ", priorQ=" + priorQuarter() + "}";
        }
    }
}
