package io.invest.iagent.service.extraction.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.invest.iagent.service.extraction.model.CompanyConfig;
import io.invest.iagent.service.extraction.model.FinancialTable;
import io.invest.iagent.service.extraction.model.Segment;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PDF 财报解析器（orchestrator）。
 *
 * <p>港股财报 PDF 常常存在中文字体编码问题，PDFBox/PDFPlumber 直接读取会出现乱码。
 * 因此该实现通过调用 Python 脚本 {@code extract_pdf_tables.py} 提取表格结构，
 * 脚本内部按优先级 camelot-lattice → camelot-stream → pdfplumber-lines → pdfplumber-text
 * → pdfplumber-plaintext 多引擎抽取并去重，最大化保留列结构；
 * 然后基于 {@link CompanyConfig#getPdfColumnMappings()} 中预定义的映射，
 * 把表格转成 {@link Segment} 列表。</p>
 *
 * <p>因为中文标签全是字体乱码无法靠正文识别，所以采用"位置映射"策略。
 * 各种 layout 的差异（腾讯 vs 美团 …）被抽到 {@link PdfLayoutHandler} 实现里，
 * 公共低层校验放在 {@link PdfExtractionSupport}。本类只负责：</p>
 * <ol>
 *   <li>调 Python 脚本产出候选表；</li>
 *   <li>解析 filing 目录名得到 {@link FilingContext}；</li>
 *   <li>按 mapping 顺序 × 表消费策略，把命中的 mapping 交给对应 handler 处理。</li>
 * </ol>
 *
 * <p>Python 可执行文件路径可通过系统属性 {@code iagent.python.bin} 或环境变量
 * {@code IAGENT_PYTHON_BIN} 覆盖，默认使用 PATH 中的 {@code python}。</p>
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
    private CompanyConfig companyConfig;

    /**
     * 工作区目录，用于定位 Python 脚本。
     */
    private Path workspace;

    /**
     * 当前 companyConfig 对应的 handler 池；按 layout 分发。
     * 每次 setCompanyConfig 后重建。
     */
    private Map<CompanyConfig.PdfColumnMapping.Layout, PdfLayoutHandler> handlers = new EnumMap<>(CompanyConfig.PdfColumnMapping.Layout.class);

    public void setCompanyConfig(CompanyConfig companyConfig) {
        this.companyConfig = companyConfig;
        PdfExtractionSupport support = new PdfExtractionSupport(companyConfig);
        Map<CompanyConfig.PdfColumnMapping.Layout, PdfLayoutHandler> map = new EnumMap<>(CompanyConfig.PdfColumnMapping.Layout.class);
        for (PdfLayoutHandler h : List.of(
                new SegmentsAsColumnsHandler(support),
                new SegmentsAsRowsHandler(support),
                new SubsegmentMatrixHandler(support))) {
            map.put(h.layout(), h);
        }
        this.handlers = map;
    }

    public void setWorkspace(Path workspace) {
        this.workspace = workspace;
    }

    /**
     * 从 PDF 文件解析出 Segment 列表（含 metric/period）。
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
        // 的分部数字块被 REVENUE 和 GROSS_PROFIT 两个映射同时写入导致值互相覆盖。
        //
        // 遍历顺序：mapping 外 × table 内。第一条能命中该 mapping 的 table 就消费掉，
        // 下一条 mapping 只在剩下的未消费 table 里挑选。
        Set<Integer> consumedTables = new HashSet<>();
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
                        log.debug("Mapping {} ({}) consumed table {} → +{} metrics",
                                mapping.getLayout(), extractMetricLabel(mapping),
                                tableJson.path("tableId").asText("unknown"), hits);
                        // 该 mapping 已经拿到一张匹配的表，不再继续
                        break;
                    }
                }
            }
        }

        log.info("PDF mapping consumed {} tables out of {}, produced {} segments",
                totalMatched, totalTables, segmentsByCode.size());
        return new ArrayList<>(segmentsByCode.values());
    }

    /**
     * 用单条 mapping 尝试消费单张表：命中即把数据写入 segmentsByCode，返回本次产生的指标条数。
     *
     * 若 mapping 上开了 {@code discardValues=true}，仍然做完整的匹配和一致性校验，
     * 但把数据写到一个丢弃桶（不返回给上层）—— 用于"占位消费掉重复同型表"的场景。
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
        List<List<String>> dataRows = PdfLayoutHandler.asRows(dataRowsNode);

        Map<String, Segment> sink = mapping.isDiscardValues()
                ? new LinkedHashMap<>()
                : segmentsByCode;

        PdfLayoutHandler handler = handlers.get(mapping.getLayout());
        if (handler == null) {
            log.warn("No handler registered for layout: {}", mapping.getLayout());
            return 0;
        }
        return handler.apply(mapping, dataRows, tableId, currency, unit, context, sink);
    }

    private String extractMetricLabel(CompanyConfig.PdfColumnMapping mapping) {
        if (mapping.getMetricCode() != null) return mapping.getMetricCode();
        if (mapping.getMetricCodesByRow() != null && !mapping.getMetricCodesByRow().isEmpty()) {
            return String.join("+", mapping.getMetricCodesByRow());
        }
        if (mapping.getRowDescriptors() != null && !mapping.getRowDescriptors().isEmpty()) {
            java.util.List<String> codes = new java.util.ArrayList<>();
            for (CompanyConfig.PdfColumnMapping.RowDescriptor rd : mapping.getRowDescriptors()) {
                if (rd.getMetricCode() != null && !rd.getMetricCode().isEmpty()) {
                    codes.add(rd.getMetricCode());
                }
            }
            return String.join("+", codes);
        }
        return "?";
    }

    /**
     * 兼容旧接口：返回空表列表 —— PDF 路径现在直接走 {@link #parseSegments(File)}。
     */
    @Override
    public List<FinancialTable> parse(File file) throws IOException {
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
     * mapping 是否命中当前 filing 的 period（H1/Q1/Q3/FY/...）。
     * 空 filingPeriods 视为不限制。
     */
    private boolean filingPeriodMatches(CompanyConfig.PdfColumnMapping mapping, FilingContext context) {
        List<String> allowed = mapping.getFilingPeriods();
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        if (context == null || context.period == null || context.period.isEmpty()) {
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
     * 定位 Python 提取脚本。
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
     * 从 PDF 文件路径解析 filing context（年份+周期类型）。
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
