package io.invest.iagent.tools.extraction;

import com.alibaba.fastjson2.JSON;
import io.invest.iagent.service.kb.FilingKnowledgeBaseService;
import io.invest.iagent.service.kb.FilingPreprocessService;
import io.invest.iagent.service.kb.embedding.ModelEmbeddingService;
import io.invest.iagent.service.kb.vector.VectorStoreServiceByMilvus;
import io.invest.iagent.service.extraction.SegmentExtractionAgent;
import io.invest.iagent.service.extraction.ExtractionVerificationAgent;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.*;
import java.io.FileWriter;
import java.io.IOException;

/**
 * 业务线财务数据提取基准测试
 *
 * 测试10家公司，生成提取准确率基准报告
 */
@Slf4j
public class SegmentExtractionBenchmarkTest {

    private SegmentExtractionAgent extractionAgent;
    private ExtractionVerificationAgent verificationAgent;

    // 测试公司列表（美股科技公司）
    private static final List<TestCompany> TEST_COMPANIES = List.of(
        new TestCompany("AAPL", "Apple Inc.", "2024", "10-K"),
        new TestCompany("MSFT", "Microsoft Corp.", "2024", "10-K"),
        new TestCompany("GOOGL", "Alphabet Inc.", "2024", "10-K"),
        new TestCompany("AMZN", "Amazon.com Inc.", "2024", "10-K"),
        new TestCompany("META", "Meta Platforms", "2024", "10-K"),
        new TestCompany("NVDA", "NVIDIA Corp.", "2024", "10-K"),
        new TestCompany("TSLA", "Tesla Inc.", "2024", "10-K"),
        new TestCompany("ORCL", "Oracle Corp.", "2024", "10-K"),
        new TestCompany("IBM", "IBM Corp.", "2024", "10-K"),
        new TestCompany("ADBE", "Adobe Inc.", "2024", "10-K")
    );

    @BeforeEach
    public void init() {
        // 初始化知识库服务
        java.nio.file.Path workspace = Paths.get(System.getProperty("user.dir")).resolve("./test-filings");
        FilingPreprocessService preprocessService = new FilingPreprocessService(workspace);

        String baseUri = "http://localhost:11434/api/embed";
        String apiKey = "local";
        String embeddingModel = "qwen3-embedding:4b";
        var embeddingService = new ModelEmbeddingService(baseUri, apiKey, embeddingModel, 1024);

        String milvusEndpoint = "http://127.0.0.1:19530";
        String collectionName = "invest_filing_test";
        var vectorStore = new VectorStoreServiceByMilvus(milvusEndpoint, null, collectionName);

        FilingKnowledgeBaseService kbService = new FilingKnowledgeBaseService(
            preprocessService, embeddingService, vectorStore
        );

        // LLM配置（使用OpenAI兼容接口）
        String openAiBaseUrl = "http://localhost:11434/v1";
        String llmApiKey = "local";
        String llmModel = "qwen3.5:9b";

        extractionAgent = new SegmentExtractionAgent(kbService, openAiBaseUrl, llmApiKey, llmModel);
        verificationAgent = new ExtractionVerificationAgent(kbService, openAiBaseUrl, llmApiKey, llmModel);
    }

    @Test
    public void test_google(){
        String ticker = "GOOGL" ;
        String fy = "2025" ;
        String formType = "10-K" ;
        SegmentExtractionAgent.ExtractionResult result = extractionAgent.extractSegments(
                ticker, fy, formType
        );
        Assertions.assertNotNull(result);
    }

    @Test
    public void benchmarkTest() throws IOException {
        log.info("========== 开始业务线财务数据提取基准测试 ==========");
        log.info("测试公司数量: {}", TEST_COMPANIES.size());

        List<BenchmarkResult> results = new ArrayList<>();
        int successCount = 0;
        int totalSegments = 0;
        int highConfidenceSegments = 0;

        for (TestCompany company : TEST_COMPANIES) {
            log.info("\n---------- 处理 {} ({}) ----------", company.ticker, company.name);
            try {
                long start = System.currentTimeMillis();

                // Step 1: 提取业务线数据
                var extraction = extractionAgent.extractSegments(
                    company.ticker, company.fiscalYear, company.formType
                );

                long extractTime = System.currentTimeMillis() - start;
                log.info("提取完成: {} 个业务线, 耗时 {}ms",
                    extraction.getSegments().size(), extractTime);

                // Step 2: 验证提取结果
                var verification = verificationAgent.verify(
                    extraction, company.ticker, company.fiscalYear
                );

                // 统计
                if (extraction.isSuccess()) {
                    successCount++;
                }
                totalSegments += extraction.getSegments().size();
                highConfidenceSegments += extraction.getSegments().stream()
                    .filter(s -> "high".equalsIgnoreCase(s.getConfidence()))
                    .count();

                // 保存结果
                results.add(BenchmarkResult.builder()
                    .ticker(company.ticker)
                    .name(company.name)
                    .extractionSuccess(extraction.isSuccess())
                    .segmentCount(extraction.getSegments().size())
                    .verificationScore(verification.getOverallScore())
                    .highConfidenceCount((int) extraction.getSegments().stream()
                        .filter(s -> "high".equalsIgnoreCase(s.getConfidence()))
                        .count())
                    .issueCount(verification.getIssues().size())
                    .extractTimeMs(extractTime)
                    .currency(extraction.getCurrency())
                    .totalRevenue(extraction.getTotalRevenue())
                    .build());

                log.info("验证得分: {}, 问题数: {}",
                    verification.getOverallScore(), verification.getIssues().size());

            } catch (Exception e) {
                log.error("处理 {} 失败: {}", company.ticker, e.getMessage(), e);
                results.add(BenchmarkResult.builder()
                    .ticker(company.ticker)
                    .name(company.name)
                    .extractionSuccess(false)
                    .errorMessage(e.getMessage())
                    .build());
            }
        }

        // 生成报告
        generateBenchmarkReport(results, successCount, totalSegments, highConfidenceSegments);

        log.info("\n========== 基准测试完成 ==========");
    }

    private void generateBenchmarkReport(List<BenchmarkResult> results,
                                          int successCount, int totalSegments, int highConfidenceSegments) throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("============================================\n");
        report.append("   业务线财务数据提取基准测试报告\n");
        report.append("   生成时间: ").append(new Date()).append("\n");
        report.append("============================================\n\n");

        // 总体统计
        double successRate = successCount * 100.0 / TEST_COMPANIES.size();
        double highConfidenceRate = totalSegments > 0
            ? highConfidenceSegments * 100.0 / totalSegments : 0;
        double avgScore = results.stream()
            .filter(r -> r.getVerificationScore() != null)
            .mapToInt(BenchmarkResult::getVerificationScore)
            .average()
            .orElse(0);

        report.append("【总体统计】\n");
        report.append(String.format("  测试公司数:     %d\n", TEST_COMPANIES.size()));
        report.append(String.format("  成功提取:       %d (%.1f%%)\n", successCount, successRate));
        report.append(String.format("  提取业务线总数: %d\n", totalSegments));
        report.append(String.format("  高置信度业务线: %d (%.1f%%)\n", highConfidenceSegments, highConfidenceRate));
        report.append(String.format("  平均验证得分:   %.1f\n\n", avgScore));

        // 逐个公司详细结果
        report.append("【各公司详细结果】\n");
        report.append("------------------------------------------------\n");
        report.append(String.format("%-8s %-20s %6s %8s %6s %8s\n",
            "代码", "公司名称", "状态", "业务线数", "得分", "问题数"));
        report.append("------------------------------------------------\n");

        for (BenchmarkResult r : results) {
            String status = r.isExtractionSuccess() ? "✓" : "✗";
            String scoreStr = r.getVerificationScore() != null
                ? String.valueOf(r.getVerificationScore())
                : "N/A";
            report.append(String.format("%-8s %-20s %4s %8d %7s %6d\n",
                r.getTicker(),
                r.getName().length() > 18 ? r.getName().substring(0, 18) : r.getName(),
                status,
                r.getSegmentCount(),
                scoreStr,
                r.getIssueCount()
            ));
        }

        // 保存JSON结果
        String jsonReport = JSON.toJSONString(results);
        try (FileWriter fw = new FileWriter("./benchmark_report.json")) {
            fw.write(jsonReport);
        }

        // 保存文本报告
        try (FileWriter fw = new FileWriter("./benchmark_report.txt")) {
            fw.write(report.toString());
        }

        log.info("\n基准报告已保存: benchmark_report.txt / benchmark_report.json");
        log.info("\n{}", report.toString());
    }

    // ========== 辅助类 ==========

    static class TestCompany {
        String ticker;
        String name;
        String fiscalYear;
        String formType;

        TestCompany(String ticker, String name, String fiscalYear, String formType) {
            this.ticker = ticker;
            this.name = name;
            this.fiscalYear = fiscalYear;
            this.formType = formType;
        }
    }

    @lombok.Data
    @lombok.Builder
    static class BenchmarkResult {
        String ticker;
        String name;
        boolean extractionSuccess;
        int segmentCount;
        Integer verificationScore;
        int highConfidenceCount;
        int issueCount;
        long extractTimeMs;
        String currency;
        Long totalRevenue;
        String errorMessage;
    }
}
