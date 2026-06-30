package io.invest.iagent.service.extraction;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.service.kb.FilingKnowledgeBaseService;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 提取结果验证 Agent
 *
 * 功能：
 * 1. 交叉验证提取结果与原始财报
 * 2. 验证勾稽关系（收入-成本=毛利等）
 * 3. 标记异常数据并给出修正建议
 * 4. 生成验证报告
 */
@Slf4j
public class ExtractionVerificationAgent {

    private final FilingKnowledgeBaseService kbService;
    private final String openAiBaseUrl;
    private final String apiKey;
    private final String model;

    public ExtractionVerificationAgent(FilingKnowledgeBaseService kbService,
                                        String openAiBaseUrl, String apiKey, String model) {
        this.kbService = kbService;
        this.openAiBaseUrl = openAiBaseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    /**
     * 完整验证流程
     */
    public VerificationResult verify(SegmentExtractionAgent.ExtractionResult extraction, String ticker, String fiscalYear) {
        log.info("开始验证提取结果: ticker={}, segments={}", ticker, extraction.getSegments().size());

        VerificationResult result = VerificationResult.builder()
                .ticker(ticker)
                .fiscalYear(fiscalYear)
                .overallScore(100) // 满分100
                .issues(new ArrayList<>())
                .recommendations(new ArrayList<>())
                .build();

        // Step 1: 规则验证（快速验证）
        ruleBasedVerification(extraction, result);

        // Step 2: 勾稽关系验证
        accountingFormulaVerification(extraction, result);

        // Step 3: LLM 交叉验证（与原文对比）
        if (!result.getIssues().isEmpty()) {
            llmCrossVerification(extraction, ticker, fiscalYear, result);
        }

        // Step 4: 计算最终得分
        calculateFinalScore(result);

        log.info("验证完成: 得分={}, 问题数={}", result.getOverallScore(), result.getIssues().size());
        return result;
    }

    /**
     * 规则验证
     */
    private void ruleBasedVerification(SegmentExtractionAgent.ExtractionResult extraction, VerificationResult result) {
        List<VerificationIssue> issues = new ArrayList<>();

        // 1. 检查必选字段
        for (SegmentExtractionAgent.SegmentData segment : extraction.getSegments()) {
            if (segment.getRevenue() == null) {
                issues.add(VerificationIssue.builder()
                        .segmentName(segment.getSegmentName())
                        .fieldName("revenue")
                        .type(VerificationIssueType.MISSING_FIELD)
                        .severity(Severity.MEDIUM)
                        .message("缺少收入字段")
                        .build());
            }

            // 2. 检查置信度
            if ("low".equalsIgnoreCase(segment.getConfidence())) {
                issues.add(VerificationIssue.builder()
                        .segmentName(segment.getSegmentName())
                        .type(VerificationIssueType.LOW_CONFIDENCE)
                        .severity(Severity.LOW)
                        .message("该业务线数据置信度低")
                        .detail(segment.getConfidenceReason())
                        .build());
            }
        }

        // 3. 检查货币/单位一致性
        Set<String> currencies = extraction.getSegments().stream()
                .map(s -> s.getConfidence())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (currencies.size() > 1) {
            issues.add(VerificationIssue.builder()
                    .type(VerificationIssueType.INCONSISTENT_UNIT)
                    .severity(Severity.HIGH)
                    .message("存在多种货币单位，需要确认转换")
                    .detail("Currency: " + currencies)
                    .build());
        }

        result.getIssues().addAll(issues);
    }

    /**
     * 会计勾稽关系验证
     */
    private void accountingFormulaVerification(SegmentExtractionAgent.ExtractionResult extraction, VerificationResult result) {
        List<VerificationIssue> issues = new ArrayList<>();

        for (SegmentExtractionAgent.SegmentData segment : extraction.getSegments()) {
            // 验证: 毛利 = 收入 - 成本
            if (segment.getRevenue() != null && segment.getCostOfRevenue() != null && segment.getGrossProfit() != null) {
                long expectedGrossProfit = segment.getRevenue() - segment.getCostOfRevenue();
                long actualGrossProfit = segment.getGrossProfit();
                double diffPercent = Math.abs(expectedGrossProfit - actualGrossProfit) * 100.0 / segment.getRevenue();

                if (diffPercent > 5) { // 差异超过5%告警
                    issues.add(VerificationIssue.builder()
                            .segmentName(segment.getSegmentName())
                            .type(VerificationIssueType.ACCOUNTING_MISMATCH)
                            .severity(Severity.HIGH)
                            .message(String.format("毛利计算差异 %.1f%%", diffPercent))
                            .detail(String.format("收入-成本=%,d，实际毛利=%,d", expectedGrossProfit, actualGrossProfit))
                            .build());
                }
            }

            // 验证: 经营利润 = 毛利 - 经营费用
            if (segment.getGrossProfit() != null && segment.getOperatingExpenses() != null && segment.getOperatingIncome() != null) {
                long expectedOperatingIncome = segment.getGrossProfit() - segment.getOperatingExpenses();
                long actualOperatingIncome = segment.getOperatingIncome();
                double diffPercent = Math.abs(expectedOperatingIncome - actualOperatingIncome) * 100.0 / segment.getGrossProfit();

                if (diffPercent > 10) { // 经营利润差异容忍度稍大
                    issues.add(VerificationIssue.builder()
                            .segmentName(segment.getSegmentName())
                            .type(VerificationIssueType.ACCOUNTING_MISMATCH)
                            .severity(Severity.MEDIUM)
                            .message(String.format("经营利润计算差异 %.1f%%", diffPercent))
                            .detail(String.format("毛利-费用=%,d，实际经营利润=%,d", expectedOperatingIncome, actualOperatingIncome))
                            .build());
                }
            }
        }

        // 验证业务线总和与合并报表
        if (extraction.getTotalRevenue() != null && !extraction.getSegments().isEmpty()) {
            long sumSegmentRevenue = extraction.getSegments().stream()
                    .filter(s -> s.getRevenue() != null)
                    .mapToLong(SegmentExtractionAgent.SegmentData::getRevenue)
                    .sum();

            if (sumSegmentRevenue > 0) {
                double diffPercent = Math.abs(sumSegmentRevenue - extraction.getTotalRevenue()) * 100.0 / extraction.getTotalRevenue();
                if (diffPercent > 10) {
                    issues.add(VerificationIssue.builder()
                            .type(VerificationIssueType.RECONCILIATION_MISMATCH)
                            .severity(Severity.HIGH)
                            .message(String.format("业务线收入总和与合并报表差异 %.1f%%", diffPercent))
                            .detail(String.format("业务线总和=%,d，合并报表=%,d", sumSegmentRevenue, extraction.getTotalRevenue()))
                            .build());
                }
            }
        }

        result.getIssues().addAll(issues);
    }

    /**
     * LLM 交叉验证（与原文对比）
     */
    private void llmCrossVerification(SegmentExtractionAgent.ExtractionResult extraction,
                                       String ticker, String fiscalYear, VerificationResult result) {
        try {
            // 检索相关上下文
            String context = retrieveVerificationContext(ticker, fiscalYear);

            // 构建验证提示词
            String prompt = buildVerificationPrompt(extraction, context);

            // 调用 LLM
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", model);
            requestBody.put("temperature", 0.1);

            JSONArray messages = new JSONArray();
            messages.add(JSONObject.of("role", "system", "content", VERIFICATION_SYSTEM_PROMPT));
            messages.add(JSONObject.of("role", "user", "content", prompt));
            requestBody.put("messages", messages);
            requestBody.put("response_format", JSONObject.of("type", "json_object"));

            String response = callOpenAiApi(requestBody.toJSONString());
            JSONObject jsonResponse = JSON.parseObject(response);
            String rawResult = jsonResponse.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");

            // 解析 LLM 验证结果
            parseLlmVerificationResult(rawResult, result);

        } catch (Exception e) {
            log.error("LLM 交叉验证失败: {}", e.getMessage(), e);
            result.getIssues().add(VerificationIssue.builder()
                    .type(VerificationIssueType.VERIFICATION_FAILED)
                    .severity(Severity.LOW)
                    .message("LLM交叉验证执行失败")
                    .detail(e.getMessage())
                    .build());
        }
    }

    private String retrieveVerificationContext(String ticker, String fiscalYear) {
        StringBuilder sb = new StringBuilder();
        try {
            String[] queries = {
                "revenue by segment breakdown",
                "segment revenue operating income cost",
                "segment reporting revenue cost profit"
            };

            Set<String> seenChunks = new HashSet<>();
            for (String query : queries) {
                var result = kbService.retrieve(query, ticker, 10, fiscalYear, null, "income_statement_segment");
                if (result.getResults() != null) {
                    for (var chunk : result.getResults()) {
                        if (chunk.getChunkId() != null && seenChunks.add(chunk.getChunkId())) {
                            sb.append("【").append(chunk.getSectionTitle()).append("】 ");
                            sb.append(chunk.getText()).append(" ");
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("检索验证上下文失败: {}", e.getMessage());
        }
        return sb.length() > 0 ? sb.toString() : "无验证上下文";
    }

    private String buildVerificationPrompt(SegmentExtractionAgent.ExtractionResult extraction, String context) {
        StringBuilder segmentsJson = new StringBuilder("[");
        for (int i = 0; i < extraction.getSegments().size(); i++) {
            SegmentExtractionAgent.SegmentData seg = extraction.getSegments().get(i);
            if (i > 0) segmentsJson.append(",");
            segmentsJson.append(String.format("{\"name\":\"%s\",\"revenue\":%d,\"profit\":%d}",
                seg.getSegmentName(), seg.getRevenue(), seg.getOperatingIncome()
            ));
        }
        segmentsJson.append("]");

        return String.format(
            """
            【验证任务】
            对比以下提取结果与财报原文，找出数据不一致或可能的错误。

            【提取结果】
            %s

            【财报原文上下文】
            %s

            【输出格式】JSON
            {
              "verified": true/false,
              "issues": [
                {
                  "segment_name": "业务线名称",
                  "issue_type": "NUMBER_MISMATCH|MISSING_DATA|UNIT_ERROR|CURRENCY_ERROR",
                  "severity": "HIGH/MEDIUM/LOW",
                  "original_value": "提取值",
                  "correct_value": "原文中的正确值",
                  "message": "问题描述",
                  "suggestion": "修正建议"
                }
              ],
              "overall_assessment": "总体评价",
              "confidence_score": 0-100
            }
            """, segmentsJson.toString(), context);
    }

    private void parseLlmVerificationResult(String rawResult, VerificationResult result) {
        try {
            JSONObject json = JSON.parseObject(rawResult);
            JSONArray issues = json.getJSONArray("issues");
            if (issues != null) {
                for (int i = 0; i < issues.size(); i++) {
                    JSONObject issue = issues.getJSONObject(i);
                    result.getIssues().add(VerificationIssue.builder()
                            .segmentName(issue.getString("segment_name"))
                            .type(VerificationIssueType.LLM_MISMATCH)
                            .severity(Severity.valueOf(issue.getString("severity")))
                            .message(issue.getString("message"))
                            .detail("原文值: " + issue.getString("correct_value") + ", 提取值: " + issue.getString("original_value"))
                            .suggestion(issue.getString("suggestion"))
                            .build());
                }
            }

            String assessment = json.getString("overall_assessment");
            result.setLlmAssessment(assessment);
            result.setLlmConfidenceScore(json.getInteger("confidence_score"));

        } catch (Exception e) {
            log.error("解析LLM验证结果失败: {}", e.getMessage());
        }
    }

    private void calculateFinalScore(VerificationResult result) {
        int score = 100;

        Map<Severity, Long> severityCount = result.getIssues().stream()
                .collect(Collectors.groupingBy(VerificationIssue::getSeverity, Collectors.counting()));

        // 严重问题扣 15 分，中等扣 8 分，轻微扣 3 分
        score -= severityCount.getOrDefault(Severity.HIGH, 0L) * 15;
        score -= severityCount.getOrDefault(Severity.MEDIUM, 0L) * 8;
        score -= severityCount.getOrDefault(Severity.LOW, 0L) * 3;

        // 最低 0 分
        result.setOverallScore(Math.max(0, score));

        // 生成综合建议
        generateRecommendations(result);
    }

    private void generateRecommendations(VerificationResult result) {
        List<String> recommendations = new ArrayList<>();

        Map<Severity, Long> severityCount = result.getIssues().stream()
                .collect(Collectors.groupingBy(VerificationIssue::getSeverity, Collectors.counting()));

        long highIssues = severityCount.getOrDefault(Severity.HIGH, 0L);
        long mediumIssues = severityCount.getOrDefault(Severity.MEDIUM, 0L);

        if (highIssues > 0) {
            recommendations.add("存在严重问题，建议人工审核确认");
        }
        if (mediumIssues > 2) {
            recommendations.add("中等问题较多，建议检查规则提取逻辑");
        }
        if (result.getLlmConfidenceScore() != null && result.getLlmConfidenceScore() < 70) {
            recommendations.add("LLM验证置信度较低，建议扩展检索上下文");
        }

        if (recommendations.isEmpty()) {
            recommendations.add("验证通过，数据质量良好");
        }

        result.setRecommendations(recommendations);
    }

    private String callOpenAiApi(String requestBody) throws Exception {
        java.net.URL url = new java.net.URL(openAiBaseUrl + "/chat/completions");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(60000);
        conn.setReadTimeout(120000);

        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            String error = new String(conn.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            throw new RuntimeException("API request failed: code=" + responseCode);
        }

        return new String(conn.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    // ========== 数据模型 ==========

    @Data
    @Builder
    public static class VerificationResult {
        private String ticker;
        private String fiscalYear;
        private Integer overallScore;
        private List<VerificationIssue> issues;
        private List<String> recommendations;
        private String llmAssessment;
        private Integer llmConfidenceScore;
    }

    @Data
    @Builder
    public static class VerificationIssue {
        private String segmentName;
        private String fieldName;
        private VerificationIssueType type;
        private Severity severity;
        private String message;
        private String detail;
        private String suggestion;
    }

    public enum VerificationIssueType {
        MISSING_FIELD, LOW_CONFIDENCE, INCONSISTENT_UNIT,
        ACCOUNTING_MISMATCH, RECONCILIATION_MISMATCH,
        LLM_MISMATCH, VERIFICATION_FAILED
    }

    public enum Severity {
        HIGH, MEDIUM, LOW
    }

    private static final String VERIFICATION_SYSTEM_PROMPT = """
    你是专业的财报审核专家，负责验证从财报中提取的业务线财务数据。

    审核标准：
    1. 数值准确性：提取的数字必须与原文完全一致（考虑单位换算）
    2. 业务线完整性：检查是否遗漏重要业务线
    3. 勾稽关系：收入-成本-利润的关系必须合理
    4. 单位一致性：所有业务线的货币单位必须一致

    请严格按照JSON格式输出审核结果。
    """;
}
