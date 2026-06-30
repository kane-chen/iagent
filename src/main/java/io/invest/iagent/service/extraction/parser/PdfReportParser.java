package io.invest.iagent.service.extraction.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.invest.iagent.service.extraction.model.CompanyConfig;
import io.invest.iagent.service.extraction.model.FinancialTable;
import io.invest.iagent.service.extraction.model.TableCell;
import io.invest.iagent.service.extraction.model.TableRow;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * PDF 财报解析器
 *
 * 港股财报 PDF 常常存在中文字体编码问题，PDFBox 直接读取会出现乱码。
 * 因此该实现通过调用 Python 脚本（基于 pdfplumber）来提取表格结构，
 * 然后基于 {@link CompanyConfig#getPdfColumnMappings()} 中预定义的列映射
 * 把数据转换为带 segment label 的 {@link FinancialTable}，
 * 这样下游的 DataExtractor 就能像处理普通 HTML 表格一样处理 PDF 数据。
 */
@Slf4j
public class PdfReportParser extends ReportParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    @Override
    public List<FinancialTable> parse(File file) throws IOException {
        log.info("Parsing PDF report file via pdfplumber: {}", file.getName());

        Path scriptPath = locatePythonScript();
        if (scriptPath == null || !Files.exists(scriptPath)) {
            log.warn("Python extraction script not found, returning empty result for: {}", file.getName());
            return new ArrayList<>();
        }

        // 调用 Python 脚本
        JsonNode pythonResult = runPythonScript(scriptPath, file);
        if (pythonResult == null || !pythonResult.has("tables")) {
            log.warn("No tables returned from python script for: {}", file.getName());
            return new ArrayList<>();
        }

        List<FinancialTable> tables = new ArrayList<>();
        JsonNode tablesNode = pythonResult.get("tables");
        for (JsonNode tableJson : tablesNode) {
            FinancialTable table = convertToFinancialTable(tableJson);
            if (table != null) {
                tables.add(table);
            }
        }

        log.info("Successfully extracted {} financial tables from PDF", tables.size());
        return tables;
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
        // 回退：从 user.dir 找
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
        // 写入临时 JSON 文件，避免 stdout 编码问题
        Path tempOutput = Files.createTempFile("pdf_extract_", ".json");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "python",
                    scriptPath.toAbsolutePath().toString(),
                    pdfFile.getAbsolutePath(),
                    "--output", tempOutput.toAbsolutePath().toString(),
                    "--max-pages", "100"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 读取 stderr/stdout（避免 buffer 满）
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
                log.warn("Python script failed with exit code {}: {}", exitCode, output);
                return null;
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
     * 把 Python 提取的表格 JSON 转换为 FinancialTable
     * 这里会基于 companyConfig.pdfColumnMappings 把"按列位置存放的数值"展开为
     * 多条按 segment label 命名的行，便于下游 DataExtractor 处理
     */
    private FinancialTable convertToFinancialTable(JsonNode tableJson) {
        int columnCount = tableJson.path("columnCount").asInt();
        String tableId = tableJson.path("tableId").asText("unknown");
        String currency = tableJson.path("currency").asText(null);
        String unit = tableJson.path("unit").asText(null);
        String periodHint = tableJson.path("periodHint").asText("");
        int year = tableJson.path("year").asInt(0);

        // 查找匹配的列映射（按 columnCount）
        CompanyConfig.PdfColumnMapping mapping = findColumnMapping(columnCount);
        if (mapping == null) {
            log.debug("No PDF column mapping for columnCount={}, skipping table {}",
                    columnCount, tableId);
            return null;
        }

        FinancialTable table = new FinancialTable();
        table.setTableId(tableId);
        table.setCurrency(currency);
        table.setUnit(unit);

        // 表头加上周期提示和年份，方便下游 PeriodTypeUtil/DataExtractor 推断周期
        StringBuilder titleBuilder = new StringBuilder();
        if (year > 0) {
            titleBuilder.append(year);
        }
        if (!periodHint.isEmpty()) {
            if (titleBuilder.length() > 0) {
                titleBuilder.append(" ");
            }
            // 把 periodHint 转成 PeriodTypeUtil 能识别的形式
            titleBuilder.append(periodHintToTitleFragment(periodHint));
        }
        table.setTitle(titleBuilder.toString());

        List<String> segmentCodes = mapping.getSegmentCodes();
        List<String> metricCodesByRow = mapping.getMetricCodesByRow();

        // 把每个分部一行：行标签 = segmentCode，cells = 该列在每个 data row 的值
        // 实际上更简单：把 Python 提取的 dataRows 转换为按 segment 组织的行
        // 每个 (metricCode, segment) 组合一条 row
        JsonNode dataRowsNode = tableJson.path("dataRows");
        if (!dataRowsNode.isArray()) {
            return null;
        }

        // 收集所有数据行的数值
        // dataRows[rowIdx][colIdx]
        List<List<String>> dataRows = new ArrayList<>();
        for (JsonNode rowNode : dataRowsNode) {
            List<String> rowCells = new ArrayList<>();
            for (JsonNode cellNode : rowNode) {
                rowCells.add(cellNode.asText(""));
            }
            dataRows.add(rowCells);
        }

        // 输出格式：每个 metric * segment 一行
        // 这样DataExtractor能用 matchesSegment(label, segmentCode, segmentName) 找到对应行
        List<TableRow> rows = new ArrayList<>();

        // 先把"周期"作为一个 fake header 行加上，确保 buildPeriodSequence 能找到年份
        if (year > 0) {
            TableRow headerRow = new TableRow();
            headerRow.setLabel("");
            // 在表头行的每个数据列放上同一个年份，让DataExtractor能为每列分配周期
            for (int i = 0; i < segmentCodes.size(); i++) {
                headerRow.addCell(new TableCell(String.valueOf(year)));
            }
            rows.add(headerRow);
        }

        int totalRowsToTake = Math.min(dataRows.size(), metricCodesByRow.size());
        for (int rowIdx = 0; rowIdx < totalRowsToTake; rowIdx++) {
            String metricCode = metricCodesByRow.get(rowIdx);
            List<String> rowCells = dataRows.get(rowIdx);

            // 为每个分部生成一行（跳过 TOTAL 列）
            for (int colIdx = 0; colIdx < segmentCodes.size() && colIdx < rowCells.size(); colIdx++) {
                String segmentCode = segmentCodes.get(colIdx);
                if (segmentCode == null || segmentCode.isEmpty() || "TOTAL".equalsIgnoreCase(segmentCode)) {
                    continue;
                }
                String value = rowCells.get(colIdx);
                if (value == null || value.isEmpty()) {
                    continue;
                }

                TableRow row = new TableRow();
                // label 格式：metricCode + segmentCode（精确匹配，避免误伤）
                // DataExtractor.matchesSegment 用 segmentCode 来匹配，且大小写不敏感
                row.setLabel(segmentCode + " " + metricCode);
                row.addCell(new TableCell(value));
                rows.add(row);
            }
        }

        table.setRows(rows);

        // 设置 header 也方便 PeriodTypeUtil 通过 header 推断
        List<String> headers = new ArrayList<>();
        if (year > 0 && !periodHint.isEmpty()) {
            headers.add(periodHintToTitleFragment(periodHint) + " " + year);
        }
        table.setHeaders(headers);

        return table;
    }

    /**
     * 根据列数找到匹配的 PDF 列映射
     */
    private CompanyConfig.PdfColumnMapping findColumnMapping(int columnCount) {
        if (companyConfig == null || companyConfig.getPdfColumnMappings() == null) {
            return null;
        }
        for (CompanyConfig.PdfColumnMapping mapping : companyConfig.getPdfColumnMappings()) {
            if (mapping.getColumnCount() == columnCount) {
                return mapping;
            }
        }
        return null;
    }

    /**
     * 把周期提示转换为标题片段，便于 PeriodTypeUtil 识别
     */
    private String periodHintToTitleFragment(String periodHint) {
        if (periodHint == null) return "";
        switch (periodHint) {
            case "Q1": return "for the three months ended March 31";
            case "Q2": return "for the three months ended June 30";
            case "Q3": return "for the three months ended September 30";
            case "Q4": return "for the three months ended December 31";
            case "Q3_YTD": return "for the nine months ended September 30";
            case "H1": return "for the six months ended June 30";
            case "H2": return "for the six months ended December 31";
            case "FY": return "for the year ended December 31";
            default: return periodHint;
        }
    }
}
