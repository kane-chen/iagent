package io.invest.iagent.service.extraction;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.model.KnowledgeBaseRetrieveResult;
import io.invest.iagent.service.kb.FilingKnowledgeBaseService;
import io.invest.iagent.service.kb.model.KnowledgeBaseChunkDTO;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG 财务数据提取 Agent
 */
@Slf4j
public class SegmentExtractionAgent {

    private final FilingKnowledgeBaseService kbService;
    private final String openAiBaseUrl;
    private final String apiKey;
    private final String model;

    public SegmentExtractionAgent(FilingKnowledgeBaseService kbService,
                                   String openAiBaseUrl, String apiKey, String model) {
        this.kbService = kbService;
        this.openAiBaseUrl = openAiBaseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    public ExtractionResult extractSegments(String ticker, String fiscalYear, String formType) {
        log.info("Start extracting segment financial data: ticker={}, fiscalYear={}", ticker, fiscalYear);
        long start = System.currentTimeMillis();

        // Step 1: 检索相关财报片段
        List<KnowledgeBaseChunkDTO> evidenceChunks = retrieveRelevantChunks(ticker, fiscalYear, formType);
        if (evidenceChunks.isEmpty()) {
            return ExtractionResult.builder()
                    .ticker(ticker)
                    .fiscalYear(fiscalYear)
                    .success(false)
                    .errorMessage("未检索到相关财报片段")
                    .build();
        }

        // Step 2: 构建上下文
        String context = buildContext(evidenceChunks);

        // Step 3: 调用 LLM 进行结构化提取
        String rawJson = callLLMForExtraction(ticker, fiscalYear, context);

        // Step 4: 解析并验证结果
        ExtractionResult result = parseAndValidateResult(rawJson, ticker, fiscalYear);

        // Step 5: 附加溯源引用
        attachEvidenceReferences(result, evidenceChunks);

        result.setDurationMs(System.currentTimeMillis() - start);
        result.setEvidenceChunkCount(evidenceChunks.size());

        log.info("Extraction completed: success={}, segments={}, duration={}ms",
                result.isSuccess(), result.getSegments().size(), result.getDurationMs());

        return result;
    }

    private List<KnowledgeBaseChunkDTO> retrieveRelevantChunks(String ticker, String fiscalYear, String formType) {
        List<String> queries = List.of(
            "segment revenue operating income cost by business segment",
            "reportable segments financial data revenue cost profit",
            "业务线 分部报告 收入 成本 利润"
        );

        Set<String> seenChunks = new HashSet<>();
        List<KnowledgeBaseChunkDTO> allChunks = new ArrayList<>();

        for (String query : queries) {
            try {
                KnowledgeBaseRetrieveResult result = kbService.retrieve(
                        query, ticker, 20, fiscalYear, formType, "income_statement_segment");
                if (result.getResults() != null) {
                    for (KnowledgeBaseChunkDTO chunk : result.getResults()) {
                        if (chunk.getChunkId() != null && seenChunks.add(chunk.getChunkId())) {
                            allChunks.add(chunk);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Retrieve failed: query={}, error={}", query, e.getMessage());
            }
        }

        return allChunks.stream()
                .filter(c -> c.getScore() != null && c.getScore() > 0.3)
                .sorted(Comparator.comparing(KnowledgeBaseChunkDTO::getScore).reversed())
                .limit(15)
                .collect(Collectors.toList());
    }

    private String buildContext(List<KnowledgeBaseChunkDTO> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("【财报上下文片段】\n\n");

        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeBaseChunkDTO chunk = chunks.get(i);
            sb.append(String.format("[片段 %d] %s FY%s %s\n",
                    i + 1, chunk.getTicker(), chunk.getFiscalYear(), chunk.getSectionTitle()));
            sb.append(chunk.getText()).append("\n\n");
        }

        return sb.toString();
    }

    private String callLLMForExtraction(String ticker, String fiscalYear, String context) {
        String prompt = buildPrompt(ticker, fiscalYear, context);

        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", model);
            requestBody.put("temperature", 0.1);

            JSONArray messages = new JSONArray();
            messages.add(JSONObject.of("role", "system", "content", SYSTEM_PROMPT));
            messages.add(JSONObject.of("role", "user", "content", prompt));
            requestBody.put("messages", messages);
            requestBody.put("response_format", JSONObject.of("type", "json_object"));

            String response = callOpenAiApi(requestBody.toJSONString());
            JSONObject jsonResponse = JSON.parseObject(response);

            return jsonResponse.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");

        } catch (Exception e) {
            log.error("LLM call failed: {}", e.getMessage(), e);
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    private String buildPrompt(String ticker, String fiscalYear, String context) {
        return String.format(
            """
            【任务】
            从以下财报上下文中提取 %s 在 %s 财年的业务线财务数据。

            【要求】
            1. 严格按照 JSON Schema 输出，不添加任何解释文字
            2. 所有数值统一为美元单位，原始为百万/十亿时请做相应转换
            3. 无法确定的字段填 null
            4. 保留对原文的引用标记（evidence_refs）
            5. 注意识别业务线的层级关系

            【输出格式】
            {
              "ticker": "股票代码",
              "fiscal_year": "财年",
              "currency": "货币单位 USD/CNY/HKD等",
              "unit_scale": "数值单位 millions/billions/thousands",
              "segments": [
                {
                  "segment_name": "业务线名称",
                  "revenue": 收入数值,
                  "cost_of_revenue": 成本数值,
                  "gross_profit": 毛利数值,
                  "operating_expenses": 运营费用,
                  "operating_income": 运营利润,
                  "ebit": EBIT,
                  "segment_assets": 资产,
                  "evidence_refs": ["证据片段编号列表"],
                  "confidence": "置信度 high/medium/low",
                  "confidence_reason": "置信度判断理由"
                }
              ],
              "total_revenue": 合并报表总收入,
              "extraction_notes": ["提取过程中的特殊说明"],
              "has_partial_data": false
            }

            【财报上下文】
            %s
            """, ticker, fiscalYear, context);
    }

    private ExtractionResult parseAndValidateResult(String rawJson, String ticker, String fiscalYear) {
        try {
            JSONObject json = JSON.parseObject(rawJson);

            ExtractionResult result = ExtractionResult.builder()
                    .ticker(json.getString("ticker"))
                    .fiscalYear(json.getString("fiscal_year"))
                    .currency(json.getString("currency"))
                    .unitScale(json.getString("unit_scale"))
                    .totalRevenue(json.getLong("total_revenue"))
                    .hasPartialData(json.getBooleanValue("has_partial_data"))
                    .extractionNotes(json.getJSONArray("extraction_notes") != null
                            ? json.getJSONArray("extraction_notes").toList(String.class)
                            : List.of())
                    .build();

            JSONArray segmentsJson = json.getJSONArray("segments");
            if (segmentsJson != null) {
                List<SegmentData> segments = new ArrayList<>();
                for (int i = 0; i < segmentsJson.size(); i++) {
                    JSONObject segJson = segmentsJson.getJSONObject(i);
                    segments.add(SegmentData.builder()
                            .segmentName(segJson.getString("segment_name"))
                            .revenue(segJson.getLong("revenue"))
                            .costOfRevenue(segJson.getLong("cost_of_revenue"))
                            .grossProfit(segJson.getLong("gross_profit"))
                            .operatingExpenses(segJson.getLong("operating_expenses"))
                            .operatingIncome(segJson.getLong("operating_income"))
                            .ebit(segJson.getLong("ebit"))
                            .segmentAssets(segJson.getLong("segment_assets"))
                            .confidence(segJson.getString("confidence"))
                            .confidenceReason(segJson.getString("confidence_reason"))
                            .evidenceRefs(segJson.getJSONArray("evidence_refs") != null
                                    ? segJson.getJSONArray("evidence_refs").toList(String.class)
                                    : List.of())
                            .build());
                }
                result.setSegments(segments);
            }

            validateResult(result, ticker, fiscalYear);
            result.setSuccess(true);
            result.setRawJson(rawJson);

            return result;

        } catch (Exception e) {
            log.error("Parse result failed: {}", e.getMessage(), e);
            return ExtractionResult.builder()
                    .ticker(ticker)
                    .fiscalYear(fiscalYear)
                    .success(false)
                    .errorMessage("Parse failed: " + e.getMessage())
                    .rawJson(rawJson)
                    .build();
        }
    }

    private void validateResult(ExtractionResult result, String expectedTicker, String expectedFiscalYear) {
        List<String> validationErrors = new ArrayList<>();

        if (!expectedTicker.equals(result.getTicker())) {
            validationErrors.add("股票代码不匹配: expected=" + expectedTicker + ", actual=" + result.getTicker());
        }

        if (result.getSegments().isEmpty()) {
            validationErrors.add("未提取到任何业务线数据");
        }

        for (SegmentData segment : result.getSegments()) {
            if (segment.getRevenue() != null && segment.getRevenue() < 0) {
                validationErrors.add(segment.getSegmentName() + ": 收入为负数");
            }
        }

        if (result.getTotalRevenue() != null && !result.getSegments().isEmpty()) {
            long sumSegmentRevenue = result.getSegments().stream()
                    .filter(s -> s.getRevenue() != null)
                    .mapToLong(SegmentData::getRevenue)
                    .sum();
            if (sumSegmentRevenue > 0 && Math.abs(sumSegmentRevenue - result.getTotalRevenue()) / result.getTotalRevenue() > 0.2) {
                validationErrors.add(String.format("业务线收入总和(%.0f)与合并报表总收入(%.0f)差异超过20%%",
                        (double) sumSegmentRevenue, (double) result.getTotalRevenue()));
            }
        }

        result.setValidationErrors(validationErrors);
    }

    private void attachEvidenceReferences(ExtractionResult result, List<KnowledgeBaseChunkDTO> chunks) {
        List<EvidenceRef> evidenceRefs = chunks.stream()
                .map(chunk -> EvidenceRef.builder()
                        .chunkId(chunk.getChunkId())
                        .documentId(chunk.getDocumentId())
                        .formType(chunk.getFormType())
                        .fiscalYear(chunk.getFiscalYear().toString())
                        .sectionTitle(chunk.getSectionTitle())
                        .score(chunk.getScore())
                        .build())
                .collect(Collectors.toList());
        result.setEvidenceRefs(evidenceRefs);
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
            throw new RuntimeException("API request failed: code=" + responseCode + ", error=" + error);
        }

        return new String(conn.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Data
    @Builder
    public static class ExtractionResult {
        private String ticker;
        private String fiscalYear;
        private String currency;
        private String unitScale;
        private Long totalRevenue;
        private Boolean hasPartialData;
        private List<String> extractionNotes;
        private List<SegmentData> segments;
        private List<EvidenceRef> evidenceRefs;
        private List<String> validationErrors;
        private boolean success;
        private String errorMessage;
        private String rawJson;
        private long durationMs;
        private int evidenceChunkCount;
    }

    @Data
    @Builder
    public static class SegmentData {
        private String segmentName;
        private Long revenue;
        private Long costOfRevenue;
        private Long grossProfit;
        private Long operatingExpenses;
        private Long operatingIncome;
        private Long ebit;
        private Long segmentAssets;
        private String confidence;
        private String confidenceReason;
        private List<String> evidenceRefs;
    }

    @Data
    @Builder
    public static class EvidenceRef {
        private String chunkId;
        private String documentId;
        private String formType;
        private String fiscalYear;
        private String sectionTitle;
        private Double score;
    }

    private static final String SYSTEM_PROMPT = """
    你是专业的财报分析师，擅长从美国SEC财报中提取业务线财务数据。

    工作原则：
    1. 严谨：只提取有明确证据的数据，不推测
    2. 精确：数值单位转换必须准确
    3. 可追溯：所有提取的数据都必须标记证据来源
    4. 诚实：找不到的数据填 null，不编造

    业务线识别：
    - 标准业务线：Revenue from Products, Services, Cloud, Advertising等
    - 地区业务线：United States, International, Europe, Asia Pacific等
    - 注意识别 Total 汇总行，不重复计算

    单位转换参考：
    - in millions = 乘以 1,000,000
    - in thousands = 乘以 1,000
    - in billions = 乘以 1,000,000,000

    输出必须是严格的 JSON 格式，不要添加 Markdown 代码块标记。
    """;
}
