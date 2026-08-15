package io.invest.iagent.rag.retrieve.plugins;

import io.invest.iagent.rag.config.RagConfig;
import io.invest.iagent.rag.retrieve.model.EventBus;
import io.invest.iagent.rag.retrieve.dto.ChatManage;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.dto.PluginErrorOrNone;
import io.invest.iagent.rag.retrieve.dto.PluginException;
import io.invest.iagent.rag.retrieve.enums.EventType;
import io.invest.iagent.service.filingrag.util.LlmClient;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * CHAT_COMPLETION_STREAM：调用 LLM 生成回答并推送到 EventBus
 */
@Slf4j
public class ChatCompletionStreamPlugin implements Plugin {

    private static final String SYSTEM_PROMPT = """
            你是一个专业的投资分析助手。请基于用户提供的知识库片段回答问题。
            要求：
            1. 只基于知识库片段回答，不要编造信息
            2. 如果知识库片段不足以回答问题，请明确说明
            3. 回答要准确、简洁、有条理
            4. 引用数据时注明来源片段编号
            """;

    private final LlmClient llmClient;
    private final RagConfig config;

    public ChatCompletionStreamPlugin(LlmClient llmClient, RagConfig config) {
        this.llmClient = llmClient;
        this.config = config;
    }

    @Override
    public List<EventType> activationEvents() {
        return Collections.singletonList(EventType.CHAT_COMPLETION_STREAM);
    }

    @Override
    public PluginErrorOrNone onEvent(PipelineContext ctx, EventType eventType, ChatManage cm,
                                     Supplier<PluginErrorOrNone> next) throws PluginException {
        String contexts = cm.getState().getRenderedContexts();
        String query = cm.getQuery();

        if (contexts == null || contexts.isBlank()) {
            contexts = "（无知识库上下文）";
        }

        try {
            RagConfig.Llm llmConfig = config.getLlm();
            String answer = llmClient.chat(
                    LlmClient.ChatRequest.builder()
                            .systemPrompt(SYSTEM_PROMPT)
                            .userPrompt(contexts)
                            .temperature(llmConfig.getTemperature())
                            .maxTokens(llmConfig.getMaxTokens())
                            .timeoutSeconds(llmConfig.getTimeoutSeconds())
                            .build());

            if (answer == null || answer.isBlank()) {
                answer = "抱歉，未能生成回答。";
            }

            cm.getState().setChatResponse(answer);

            // 通过 EventBus 推送（逐块发送，模拟流式效果）
            EventBus bus = cm.getEventBus();
            if (bus != null) {
                int chunkSize = 20;
                for (int i = 0; i < answer.length(); i += chunkSize) {
                    bus.emit(cm.getSessionId(), answer.substring(i, Math.min(i + chunkSize, answer.length())));
                }
                bus.complete(cm.getSessionId());
            }
        } catch (Exception e) {
            log.error("Chat completion failed: {}", e.getMessage(), e);
            throw new PluginException("model_call_failed", e.getMessage(), e);
        }

        return next.get();
    }
}
