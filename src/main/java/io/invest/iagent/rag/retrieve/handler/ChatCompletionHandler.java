package io.invest.iagent.rag.retrieve.handler;

import io.invest.iagent.rag.chatting.Chatter;
import io.invest.iagent.rag.retrieve.dto.ChatManage;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


/**
 * CHAT_COMPLETION：调用 LLM 生成回答
 */
@Service
@Slf4j
public class ChatCompletionHandler implements Handler {

    private static final String SYSTEM_PROMPT = """
            你是一个专业的投资分析助手。请基于用户提供的知识库片段回答问题。
            要求：
            1. 只基于知识库片段回答，不要编造信息
            2. 如果知识库片段不足以回答问题，请明确说明
            3. 回答要准确、简洁、有条理
            4. 引用数据时注明来源片段编号
            """;

    @Autowired
    private Chatter chatter ;

    @Override
    public String name() {
        return "CHAT_COMPLETION";
    }

    @Override
    public void handle(PipelineContext ctx, ChatManage cm) {
        String contexts = cm.getState().getRenderedContexts();
        String query = cm.getQuery();

        if (StringUtils.isBlank(contexts)) {
            contexts = "（无知识库上下文）";
        }

        try {
            // call
            String userPrompt = String.format("""
                    ## 用户原始请求
                    %s
                    ## 知识库上下文
                    %s
                    """,query,contexts) ;
            String answer = chatter.chat(SYSTEM_PROMPT,userPrompt) ;
            // answer
            if (StringUtils.isBlank(answer)) {
                answer = "抱歉，未能生成回答。";
            }
            cm.getState().setChatResponse(answer);
        } catch (Exception e) {
            log.error("Chat completion failed: {}", e.getMessage(), e);
            throw new RuntimeException("model_call_failed", e);
        }
    }
}
