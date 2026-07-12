package io.invest.iagent.service.filingrag.answer;

import io.invest.iagent.service.filingrag.chunker.OverlapWindowChunker;
import io.invest.iagent.service.filingrag.model.FilingAnswer;
import io.invest.iagent.service.filingrag.model.FilingChunk;
import io.invest.iagent.service.filingrag.util.LlmClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@link AnswerSynthesizer} that calls an OpenAI-compatible chat completions endpoint (e.g., Ollama)
 * via the shared {@link LlmClient}.
 * <p>
 * System prompt (Chinese) instructs the model to answer strictly from cited chunks, annotating
 * numeric claims with [C1][C2]-style references that match the numbered citations listed at the end.
 */
@Slf4j
public class OllamaChatAnswerSynthesizer implements AnswerSynthesizer {

    /**
     * Context budget in approximate tokens (from chunk content).
     * We stop adding citations once the cumulative content exceeds this budget.
     */
    private static final int CONTEXT_BUDGET_TOKENS = 12000;

    private final LlmClient llmClient;
    private final double temperature;
    private final int maxTokens;

    public OllamaChatAnswerSynthesizer(LlmClient llmClient, double temperature, int maxTokens) {
        this.llmClient = llmClient;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    @Override
    public FilingAnswer answer(String question, List<FilingChunk> citedChunks, String backendName) {
        long start = System.currentTimeMillis();
        String queryId = UUID.randomUUID().toString();
        String model = llmClient.getModel();
        try {
            // Sort by score desc (if present) then trim to context budget
            List<FilingChunk> ordered = new ArrayList<>(citedChunks);
            ordered.sort((a, b) -> {
                double sa = a.getScore() == null ? 0.0 : a.getScore();
                double sb = b.getScore() == null ? 0.0 : b.getScore();
                return Double.compare(sb, sa);
            });
            List<FilingChunk> used = new ArrayList<>();
            int usedTokens = 0;
            StringBuilder context = new StringBuilder();
            int n = 1;
            for (FilingChunk c : ordered) {
                String text = StringUtils.defaultString(c.getContent());
                int ct = OverlapWindowChunker.estimateTokens(text);
                if (usedTokens + ct > CONTEXT_BUDGET_TOKENS && !used.isEmpty()) break;
                String prefix = "[" + n + "] ";
                String header = buildCitationHeader(n, c);
                context.append(prefix).append(header).append("\n").append(text).append("\n\n");
                used.add(c);
                usedTokens += ct;
                n++;
                if (usedTokens >= CONTEXT_BUDGET_TOKENS) break;
            }
            if (used.isEmpty()) {
                return FilingAnswer.builder()
                        .queryId(queryId).question(question)
                        .answer("在提供的财报片段中未找到相关信息。")
                        .backend(backendName).model(model)
                        .citations(List.of())
                        .elapsedMs(System.currentTimeMillis() - start)
                        .build();
            }

            String systemPrompt = """
                    你是一个严谨的投研助理。你必须仅基于下面[引用片段]中的信息回答用户的问题，不得使用外部知识。
                    回答要求：
                    1. 所有数字、结论、关键判断必须在句末标注引用编号，例如"收入同比增长12%[C1][C2]"。
                    2. 如果不同片段信息冲突，请分别列出并说明差异。
                    3. 如果引用片段不足以回答问题，明确说明"在提供的财报片段中未找到相关信息"。
                    4. 回答使用简体中文，条理清晰，先给结论再给依据。
                    5. 回答末尾必须单独起一行写"## 引用来源"，随后逐条列出每个实际引用到的片段的≤80字摘要，格式：
                       [Cn] {ticker} {formType}{fiscalYear}{fiscalPeriod} {sectionTitle} p.{page}: {摘要}
                       只列出在正文中实际被引用到的编号。
                    """;
            String userPrompt = "[引用片段]\n" + context + "\n用户问题：" + question;

            String content = llmClient.chat(LlmClient.ChatRequest.builder()
                    .systemPrompt(systemPrompt)
                    .userPrompt(userPrompt)
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .timeoutSeconds(180)
                    .build());

            // 如果content为空或为reasoning内容，尝试从reasoning中提取答案
            if (StringUtils.isBlank(content) || (content.contains("reasoning") && !content.contains("## 引用来源"))) {
                String extracted = llmClient.extractAnswerFromReasoning(content);
                if (StringUtils.isNotBlank(extracted)) {
                    content = extracted;
                }
            }

            // Convert [1] markers in LLM output to [C1] for user-facing format if not already
            String finalAnswer = normalizeCitationMarkers(StringUtils.defaultString(content));
            return FilingAnswer.builder()
                    .queryId(queryId)
                    .question(question)
                    .answer(finalAnswer)
                    .backend(backendName)
                    .model(model)
                    .citations(used)
                    .elapsedMs(System.currentTimeMillis() - start)
                    .build();
        } catch (Exception e) {
            log.error("Answer synthesis failed: {}", e.getMessage(), e);
            return FilingAnswer.builder()
                    .queryId(queryId)
                    .question(question)
                    .answer("答案生成失败：" + e.getMessage())
                    .backend(backendName)
                    .model(model)
                    .citations(citedChunks == null ? List.of() : citedChunks)
                    .elapsedMs(System.currentTimeMillis() - start)
                    .build();
        }
    }

    private String buildCitationHeader(int idx, FilingChunk c) {
        StringBuilder sb = new StringBuilder();
        sb.append(StringUtils.defaultString(c.getTicker()));
        if (StringUtils.isNotBlank(c.getFormType())) sb.append(" ").append(c.getFormType());
        if (c.getFiscalYear() != null) sb.append(c.getFiscalYear());
        if (StringUtils.isNotBlank(c.getFiscalPeriod())) sb.append(c.getFiscalPeriod());
        if (StringUtils.isNotBlank(c.getSectionTitle())) sb.append(" ").append(c.getSectionTitle());
        if (c.getPageNumber() != null) sb.append(" p.").append(c.getPageNumber());
        return sb.toString();
    }

    /** If the model outputs [1] instead of [C1], convert; leave [C1] intact. */
    private String normalizeCitationMarkers(String text) {
        return text.replaceAll("\\[(\\d+)\\]", "[C$1]");
    }
}
