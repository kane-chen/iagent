package io.invest.iagent.service.filingrag.backend.textsearch;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.service.filingrag.model.FilingChunk;
import io.invest.iagent.service.filingrag.util.LlmClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * LLM语义相关性重排序器：根据用户问题对检索到的chunks进行相关性评分（0-10），
 * 按分数降序重新排列chunks。
 * 失败时默认返回原始顺序的chunks（fail-safe：保留关键词排序结果）。
 */
@Slf4j
public class SemanticReranker {

    /** 送给LLM的每个chunk摘要字符数上限 */
    private static final int CHUNK_EXCERPT_CHARS = 600;

    private final LlmClient llmClient;
    private final boolean enabled;

    private static final String RERANK_SYSTEM_PROMPT = """
            你是一个精准的文档相关性评分器。根据用户问题，评估每个财报片段的语义相关性。
            注意：
            1、不要与用户交互。
            2、只做文档相关性评分，不要尝试分析、理解和回答用户的问题。
            3、直接输出评分结果，不要思考过程，不要解释。
            规则：
            1、必须为每个片段评分，index从1开始，按输入顺序编号。
            2、输出格式：{"ranked":[{"index":1,"score":8},{"index":2,"score":5},...]}
            评分标准（0-10分）：
            - 9-10分：直接回答用户问题的核心数据/事实
            - 7-8分：高度相关，包含问题所需的关键背景信息
            - 5-6分：部分相关，提及相关主题但缺少关键数据
            - 3-4分：弱相关，仅涉及同一大类话题
            - 0-2分：不相关
            """;

    public SemanticReranker(LlmClient llmClient, boolean enabled) {
        this.llmClient = llmClient;
        this.enabled = enabled;
    }

    /**
     * 对候选chunks进行语义相关性重排序。
     *
     * @param question        用户问题
     * @param candidateChunks 候选chunks（已按关键词分数排序）
     * @return 按语义相关性降序排列的chunks；LLM不可用时返回原始顺序
     */
    public List<FilingChunk> rerank(String question, List<FilingChunk> candidateChunks) {
        if (!enabled || llmClient == null || candidateChunks == null || candidateChunks.isEmpty()) {
            return candidateChunks != null ? new ArrayList<>(candidateChunks) : new ArrayList<>();
        }

        String userPrompt = buildUserPrompt(question, candidateChunks);
        String llmOutput = llmClient.chat(LlmClient.ChatRequest.builder()
                .systemPrompt(RERANK_SYSTEM_PROMPT)
                .userPrompt(userPrompt)
                .temperature(0.3)
                .maxTokens(10240)
                .build());
        // 如果返回的是reasoning内容（content为空时回退），提取其中的JSON
        if (StringUtils.isNotBlank(llmOutput) && !llmOutput.contains("\"ranked\"")) {
            llmOutput = llmClient.extractJsonFromText(llmOutput);
        }
        if (StringUtils.isBlank(llmOutput)) {
            log.debug("LLM semantic rerank returned empty, returning original order");
            return new ArrayList<>(candidateChunks);
        }
        return parseAndReorder(candidateChunks, llmOutput);
    }

    private String buildUserPrompt(String question, List<FilingChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户问题：").append(question).append("\n\n");
        sb.append("待评估的财报片段（请对每个片段评分0-10）：\n");
        int idx = 1;
        for (FilingChunk chunk : chunks) {
            sb.append("[").append(idx).append("] ");
            if (StringUtils.isNotBlank(chunk.getSectionTitle())) {
                sb.append("Section: ").append(chunk.getSectionTitle()).append(" ");
            }
            if (chunk.getPageNumber() != null) {
                sb.append("(p.").append(chunk.getPageNumber()).append(") ");
            }
            sb.append("\n");
            String content = StringUtils.defaultString(chunk.getContent());
            String excerpt = StringUtils.abbreviate(content, CHUNK_EXCERPT_CHARS);
            sb.append(excerpt).append("\n\n");
            idx++;
        }
        sb.append("\n请按格式输出JSON：{\"ranked\":[{\"index\":1,\"score\":8},...]}，包含全部").append(chunks.size()).append("个片段。");
        return sb.toString();
    }

    /**
     * 解析LLM返回的rerank结果，按score降序重排chunks。
     * 未被LLM提及的chunk保留原始顺序排在最后。
     */
    private List<FilingChunk> parseAndReorder(List<FilingChunk> chunks, String llmOutput) {
        if (StringUtils.isBlank(llmOutput)) {
            return new ArrayList<>(chunks);
        }

        List<RankedItem> rankedItems = parseRankedItems(llmOutput);
        if (rankedItems.isEmpty()) {
            log.debug("No valid ranked items parsed from LLM output, returning original order");
            return new ArrayList<>(chunks);
        }

        // 按score降序排序
        rankedItems.sort(Comparator.comparingDouble(RankedItem::score).reversed());

        List<FilingChunk> result = new ArrayList<>(chunks.size());
        Set<Integer> placedIndices = new HashSet<>();

        // 先放入已评分的chunks（按分数降序），并更新score字段为归一化分数（0-1）
        for (RankedItem item : rankedItems) {
            int zeroIdx = item.index() - 1; // LLM index从1开始
            if (zeroIdx >= 0 && zeroIdx < chunks.size() && placedIndices.add(zeroIdx)) {
                FilingChunk chunk = chunks.get(zeroIdx);
                chunk.setScore(item.score() / 10.0); // 归一化到0-1
                result.add(chunk);
            }
        }

        // 未被LLM提及的chunk按原始顺序追加到末尾，分数设为0
        for (int i = 0; i < chunks.size(); i++) {
            if (!placedIndices.contains(i)) {
                FilingChunk chunk = chunks.get(i);
                chunk.setScore(0.0);
                result.add(chunk);
            }
        }

        return result;
    }

    private List<RankedItem> parseRankedItems(String llmOutput) {
        List<RankedItem> items = new ArrayList<>();
        try {
            String json = llmOutput.trim();
            // 去掉markdown代码块
            if (json.startsWith("```json")) {
                json = json.substring(7);
            } else if (json.startsWith("```")) {
                json = json.substring(3);
            }
            if (json.endsWith("```")) {
                json = json.substring(0, json.length() - 3);
            }
            json = json.trim();
            JSONObject obj = JSON.parseObject(json);
            JSONArray ranked = obj.getJSONArray("ranked");
            if (ranked == null) {
                return items;
            }
            for (int i = 0; i < ranked.size(); i++) {
                JSONObject r = ranked.getJSONObject(i);
                Integer index = r.getInteger("index");
                Double score = r.getDouble("score");
                if (index != null && score != null) {
                    // 分数clamp到0-10
                    double clamped = Math.max(0.0, Math.min(10.0, score));
                    items.add(new RankedItem(index, clamped));
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse rerank result '{}': {}", StringUtils.abbreviate(llmOutput, 100), e.getMessage());
        }
        return items;
    }

    /**
     * LLM评分结果项。
     */
    private record RankedItem(int index, double score) {}
}
