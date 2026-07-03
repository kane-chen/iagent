package io.invest.iagent.tools.filing;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.invest.iagent.service.extraction.model.Segment;
import io.invest.iagent.service.extraction.model.SegmentMetricDTO;
import io.invest.iagent.service.extraction.service.FinancialExtractionService;
import io.invest.iagent.service.extraction.service.SegmentMetricUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
public class FinancialSegmentMetricsTool {

    private FinancialExtractionService service;
    private final Path workspace;
    private final ObjectMapper objectMapper;

    public FinancialSegmentMetricsTool(Path workspace) {
        this.workspace = workspace;
        this.service = new FinancialExtractionService(workspace);
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 生成分部财务数据Excel报告
     *
     * @param ticker 股票代码
     * @return 生成的Excel文件路径
     */
    @Tool(name = "export_segment_financial_excel",
            description = "生成指定公司的分部业务财务报表Excel，支持多层级分部展示，包含收入、EBITA数据及YoY同比高亮。")
    public String exportSegmentExcel(
            @ToolParam(name = "ticker", description = "股票代码，例如 BABA、AAPL、PDD") String ticker
    ) {
        try {
            // 1. 获取分部数据
            service = new FinancialExtractionService(ticker,workspace);
            List<Segment> items = service.extractSegments(ticker, null, null);
            List<SegmentMetricDTO> segments = SegmentMetricUtil.flattenAndSort(items);
            if (segments.isEmpty()) {
                throw new RuntimeException("未找到 " + ticker + " 的分部财务数据");
            }
            log.info("获取到 {} 个顶层分部", segments.size());
            // 2. 将Segment数据转换为JSON并保存到临时文件
            Path tempJsonFile = saveSegmentsToTempJson(segments, ticker);

            // 3. 调用Python脚本生成Excel
            Path excelPath = callPythonScript(ticker, tempJsonFile);

            // 4. 清理临时文件
            Files.deleteIfExists(tempJsonFile);

            return excelPath.toString();

        } catch (Exception e) {
            log.error("生成Excel失败: {}", e.getMessage(), e);
            throw new RuntimeException("生成Excel失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将Segment数据保存到临时JSON文件
     */
    private Path saveSegmentsToTempJson(List<SegmentMetricDTO> segments, String ticker) throws IOException {
        Path excelsDir = workspace.resolve("excels");
        if (!Files.exists(excelsDir)) {
            Files.createDirectories(excelsDir);
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path tempJsonFile = excelsDir.resolve(ticker + "_segments_" + timestamp + ".json");

        // 转换为简化结构后再序列化，避免循环引用

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempJsonFile.toFile(), segments);
        log.info("临时JSON文件已保存: {}, 数据量: {}", tempJsonFile, segments.size());

        return tempJsonFile;
    }

    /**
     * 调用Python脚本生成Excel
     */
    private Path callPythonScript(String ticker, Path jsonFile) throws IOException, InterruptedException {
        Path scriptPath = workspace
                .resolve("skills")
                .resolve("segment-financial-report")
                .resolve("scripts")
                .resolve("generate_segment_excel.py");

        if (!Files.exists(scriptPath)) {
            throw new RuntimeException("Python脚本不存在: " + scriptPath);
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path excelsDir = workspace.resolve("excels");
        Path outputFile = excelsDir.resolve(ticker + "_segments_" + timestamp + ".xlsx");

        ProcessBuilder pb = new ProcessBuilder(
                "python",
                scriptPath.toString(),
                ticker,
                "--json", jsonFile.toString(),
                "--output", outputFile.toString(),
                "--workspace", workspace.toString()
        );

        pb.redirectErrorStream(true);
        pb.directory(workspace.toFile());

        log.info("执行Python命令: {}", String.join(" ", pb.command()));

        Process process = pb.start();

        // 读取输出
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[Python] {}", line);
                System.out.println(line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Python脚本执行失败，退出码: " + exitCode);
        }

        if (!Files.exists(outputFile)) {
            throw new RuntimeException("Excel文件未生成: " + outputFile);
        }

        log.info("Excel文件已生成: {}", outputFile);
        return outputFile;
    }

}