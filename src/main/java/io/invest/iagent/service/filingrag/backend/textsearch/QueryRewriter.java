package io.invest.iagent.service.filingrag.backend.textsearch;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.service.filingrag.model.FilingQuery;
import io.invest.iagent.service.filingrag.util.LlmClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 查询改写器：通过LLM关键词提取 + 词典扩展生成最终搜索关键词集合。
 * LLM提取的关键词优先（放前面，权重更高），词典扩展的关键词作为补充。
 * LLM调用失败时优雅降级为词典模式。
 */
@Slf4j
public class QueryRewriter {

    private static final int MAX_KEYWORDS = 15;
    private static final int MIN_KW_LEN_CJK = 2;
    private static final int MIN_KW_LEN_ASCII = 3;

    private final LlmClient llmClient;
    private final boolean llmEnabled;

    private static final String REWRITE_SYSTEM_PROMPT_TEMPLATE = """
            从用户的问题中提取问题的关键词列表，以用作后续的知识库检索。
            注意：
            1、不要与用户交互。
            2、只做关键词提取，不要尝试分析、理解和回答用户的问题。
            3、直接输出关键字列表，不要思考过程，不要解释。
            规则：
            1、返回3-10个关键词
            2、优先业务术语（见[业务术语]）
            3、排除停用词（的、了、公司、什么、the、a、of等）。
            4、输出格式：{"keywords": ["词1","词2"]}
            ---业务术语---
            %s
            ---
            """;

    /** 延迟初始化system prompt（包含术语表，只拼接一次） */
    private final String systemPrompt;

    public QueryRewriter(LlmClient llmClient, boolean llmEnabled) {
        this.llmClient = llmClient;
        this.llmEnabled = llmEnabled;
        // 将术语表拼入system prompt
        this.systemPrompt = String.format(REWRITE_SYSTEM_PROMPT_TEMPLATE,
                FinancialTermDictionary.formatTermGroupsForPrompt());
    }

    /**
     * 改写结果。
     */
    public record RewriteResult(Set<String> keywords, String reasoning) {}

    /**
     * 对查询进行关键词提取和扩展。
     * 关键词优先级：用户显式指定 > LLM提取 > 词典扩展（基于LLM关键词+基础词） > 时间词。
     * LLM关键词排在前面，词典扩展同义词跟在后面。
     */
    public RewriteResult rewrite(FilingQuery query) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        String reasoning;

        // 1. 用户显式指定的keyword优先加入
        if (StringUtils.isNotBlank(query.getKeyword())) {
            keywords.add(query.getKeyword().trim());
        }

        // 2. 从问题中提取时间词/数字（季度、年份等）
        Set<String> timeTerms = new LinkedHashSet<>();
        extractTimeTerms(query.getQuestion(), timeTerms);

        // 3. 提取基础词（用于词典扩展兜底）
        Set<String> baseTerms = extractBaseTerms(query.getQuestion());

        // 4. LLM提取关键词（优先）
        List<String> llmKeywords = new ArrayList<>();
        if (llmEnabled && llmClient != null) {
            // call
            LlmClient.ChatRequest request = LlmClient.ChatRequest.builder()
                    .systemPrompt(systemPrompt)
                    .userPrompt("用户问题：" + query.getQuestion())
                    .temperature(0.3)
                    .maxTokens(10240)
                    .build() ;
            String llmResult = llmClient.chat(request);
            // 如果返回的是reasoning内容（content为空时回退），提取其中的JSON
            if (StringUtils.isNotBlank(llmResult) && !llmResult.contains("\"keywords\"")) {
                llmResult = llmClient.extractJsonFromText(llmResult);
            }
            // parse
            llmKeywords = parseKeywordsFromJson(llmResult);
            if (!llmKeywords.isEmpty()) {
                // LLM关键词先加入（保持LLM返回顺序，排在前面）
                for (String kw : llmKeywords) {
                    if (StringUtils.isNotBlank(kw)) {
                        keywords.add(kw.trim());
                    }
                }
                reasoning = "llm";
            } else {
                log.debug("LLM query rewrite returned no keywords, using dictionary only");
                reasoning = "dictionary_only";
            }
        } else {
            reasoning = "dictionary_only";
        }

        // 5. 词典扩展：基于LLM关键词 + 基础词 做同义词扩展
        //    扩展出的同义词排在LLM关键词之后
        Set<String> expandSeeds = new LinkedHashSet<>();
        expandSeeds.addAll(llmKeywords);
        expandSeeds.addAll(baseTerms);
        Set<String> expanded = FinancialTermDictionary.expand(expandSeeds);
        keywords.addAll(expanded);

        // 6. 加入时间词
        keywords.addAll(timeTerms);

        // 7. 过滤过短的关键词
        Set<String> filtered = filterByLength(keywords);

        // 8. 限制数量（保留前面优先级高的）
        Set<String> result = new LinkedHashSet<>();
        int count = 0;
        for (String kw : filtered) {
            if (count >= MAX_KEYWORDS) break;
            result.add(kw);
            count++;
        }

        log.debug("Query rewrite for '{}': keywords={} ({})", query.getQuestion(), result, reasoning);
        return new RewriteResult(result, reasoning);
    }

    /**
     * 从问题中提取时间相关词（年份、季度等）。
     */
    private void extractTimeTerms(String question, Set<String> keywords) {
        if (StringUtils.isBlank(question)) return;
        // 年份：2024、2025等4位数字
        java.util.regex.Matcher yearMatcher = java.util.regex.Pattern.compile("(20\\d{2})").matcher(question);
        while (yearMatcher.find()) {
            keywords.add(yearMatcher.group(1));
        }
        // 季度：Q1/Q2/Q3/Q4/H1/H2/FY
        String upper = question.toUpperCase();
        for (String period : new String[]{"Q1", "Q2", "Q3", "Q4", "H1", "H2", "FY"}) {
            if (upper.contains(period)) {
                keywords.add(period);
            }
        }
    }

    /**
     * 从问题文本中切分基础词（中文按2-4字窗口取bigram/trigram，英文按空格分词）。
     */
    private Set<String> extractBaseTerms(String question) {
        Set<String> terms = new LinkedHashSet<>();
        if (StringUtils.isBlank(question)) return terms;

        // 英文词：按空格/标点切分，取2+字符的字母数字串
        String[] asciiTokens = question.toLowerCase().split("[^a-z0-9]+");
        for (String tok : asciiTokens) {
            if (tok.length() >= MIN_KW_LEN_ASCII) {
                terms.add(tok);
            }
        }

        // 中文：提取连续CJK字符片段，取2-4字的子串作为基础匹配项
        StringBuilder cjkBuf = new StringBuilder();
        for (int i = 0; i < question.length(); i++) {
            char c = question.charAt(i);
            if (isCJK(c)) {
                cjkBuf.append(c);
            } else {
                if (!cjkBuf.isEmpty()) {
                    addCJKNgrams(cjkBuf.toString(), terms);
                    cjkBuf.setLength(0);
                }
            }
        }
        if (!cjkBuf.isEmpty()) {
            addCJKNgrams(cjkBuf.toString(), terms);
        }

        return terms;
    }

    private void addCJKNgrams(String cjkText, Set<String> terms) {
        int len = cjkText.length();
        // 添加2字词（bigram）
        for (int i = 0; i <= len - 2; i++) {
            terms.add(cjkText.substring(i, i + 2));
        }
        // 添加3字词（trigram）
        for (int i = 0; i <= len - 3; i++) {
            terms.add(cjkText.substring(i, i + 3));
        }
        // 添加4字词
        for (int i = 0; i <= len - 4; i++) {
            terms.add(cjkText.substring(i, i + 4));
        }
        // 整个CJK段（如果长度合理）
        if (len >= 2 && len <= 8) {
            terms.add(cjkText);
        }
    }

    private boolean isCJK(char c) {
        Character.UnicodeBlock ub = Character.UnicodeBlock.of(c);
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    /**
     * 过滤过短的关键词。
     */
    private Set<String> filterByLength(Set<String> keywords) {
        Set<String> result = new LinkedHashSet<>();
        for (String kw : keywords) {
            if (StringUtils.isBlank(kw)) continue;
            String trimmed = kw.trim();
            if (trimmed.length() < MIN_KW_LEN_CJK) continue;
            // 纯英文词至少3个字符
            boolean allAscii = true;
            for (char c : trimmed.toCharArray()) {
                if (c >= 128) { allAscii = false; break; }
            }
            if (allAscii && trimmed.length() < MIN_KW_LEN_ASCII) continue;
            result.add(trimmed);
        }
        return result;
    }

    /**
     * 从LLM返回的JSON文本中解析关键词列表。
     */
    private List<String> parseKeywordsFromJson(String text) {
        List<String> result = new ArrayList<>();
        if (StringUtils.isBlank(text)) return result;
        try {
            String json = text.trim();
            // 去掉可能的markdown代码块标记
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
            JSONArray arr = obj.getJSONArray("keywords");
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    String kw = arr.getString(i);
                    if (StringUtils.isNotBlank(kw)) {
                        result.add(kw.trim());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse LLM keywords JSON: {}", e.getMessage());
        }
        return result;
    }
}
