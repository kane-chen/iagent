package io.invest.iagent.rag.retrieve.plugins;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.rag.retrieve.model.SearchResult;
import io.invest.iagent.service.filingrag.util.LlmClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.*;

/**
 * 基于 LLM 的语义重排序工具。
 * 从 filingrag SemanticReranker 移植的核心逻辑，供 rag 模块独立使用。
 */
@Slf4j
public class SemanticRerankUtil {

    private static final int EXCERPT_LEN = 600;

    /**
     * 对检索结果做 LLM 语义打分。
     *
     * @param query      查询文本
     * @param results    待排序结果
     * @param llmClient  LLM 客户端
     * @return 重排序后的列表（按分数降序）；LLM 失败时返回原始顺序
     */
    public static List<SearchResult> rerank(String query, List<SearchResult> results, LlmClient llmClient) {
        if (results == null || results.size() <= 1) return results;

        try {
            String systemPrompt = """
                    你是一个专业的搜索结果相关性评分器。请根据用户问题，对每个搜索结果片段进行相关性评分。
                    评分范围：0-10 分。
                    评分标准：
                    - 9-10：直接回答用户问题，包含关键数据或结论
                    - 7-8：与问题高度相关，提供重要背景信息
                    - 5-6：与问题相关，但信息不够直接
                    - 3-4：弱相关，仅涉及主题边缘
                    - 0-2：不相关
                    请以 JSON 格式输出，格式为：{"ranked":[{"index":0,"score":8},{"index":1,"score":5}]}
                    必须对所有片段评分，index 对应片段编号。""";

            StringBuilder userPrompt = new StringBuilder();
            userPrompt.append("用户问题：").append(query).append("\n\n");
            for (int i = 0; i < results.size(); i++) {
                String excerpt = StringUtils.left(results.get(i).content, EXCERPT_LEN);
                userPrompt.append("片段").append(i).append("：").append(excerpt).append("\n\n");
            }

            String response = llmClient.chat(systemPrompt, userPrompt.toString());
            if (StringUtils.isBlank(response)) {
                log.warn("Rerank LLM returned empty response, keeping original order");
                return results;
            }

            Map<Integer, Double> scores = parseScores(response, results.size());
            if (scores.isEmpty()) {
                log.warn("Failed to parse rerank scores, keeping original order");
                return results;
            }

            // 组合分：baseScore * 0.3 + modelScore/10 * 0.7
            List<SearchResult> sorted = new ArrayList<>(results);
            for (SearchResult r : sorted) {
                int idx = results.indexOf(r);
                double modelScore = scores.getOrDefault(idx, 5.0) / 10.0;
                double baseScore = r.score;
                double composite = baseScore * 0.3 + modelScore * 0.7;
                r.metadata.put("base_score", String.valueOf(baseScore));
                r.metadata.put("model_score", String.valueOf(modelScore));
                r.score = composite;
            }
            sorted.sort((a, b) -> Double.compare(b.score, a.score));
            return sorted;
        } catch (Exception e) {
            log.warn("Rerank failed: {}, keeping original order", e.getMessage());
            return results;
        }
    }

    private static Map<Integer, Double> parseScores(String response, int expectedSize) {
        Map<Integer, Double> scores = new HashMap<>();
        try {
            String json = extractJson(response);
            if (StringUtils.isBlank(json)) return scores;

            JSONObject obj = JSON.parseObject(json);
            JSONArray ranked = obj.getJSONArray("ranked");
            if (ranked == null) return scores;

            for (int i = 0; i < ranked.size(); i++) {
                JSONObject item = ranked.getJSONObject(i);
                int index = item.getIntValue("index");
                double score = item.getDoubleValue("score");
                if (index >= 0 && index < expectedSize) {
                    scores.put(index, score);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse rerank JSON: {}", e.getMessage());
        }
        return scores;
    }

    private static String extractJson(String text) {
        if (StringUtils.isBlank(text)) return "";
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '{') continue;
            int depth = 0;
            for (int j = i; j < text.length(); j++) {
                char c = text.charAt(j);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        String candidate = text.substring(i, j + 1);
                        if (candidate.contains("ranked")) return candidate;
                        break;
                    }
                }
            }
        }
        return "";
    }
}
