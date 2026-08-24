package io.invest.iagent.rag.retrieve.handler;

import io.invest.iagent.rag.chatting.Chatter;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.dto.PipelineRuntime;
import io.invest.iagent.rag.retrieve.dto.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;


/**
 * CHAT_COMPLETION：调用 LLM 生成回答
 */
@Service
@Slf4j
public class ChatCompletionHandler implements Handler {

    private static final String SYSTEM_PROMPT = """
            你是一个专业的问答助手。请基于用户提供的知识库片段回答问题。
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
    public void handle(PipelineRuntime runtime, PipelineContext context) {
        try {
            String userPrompt = this.buildUserPrompt(context);
            String answer = chatter.chat(SYSTEM_PROMPT,userPrompt) ;
            // answer
            if (StringUtils.isBlank(answer)) {
                answer = "抱歉，未能生成回答。";
            }
            context.getState().setChatResponse(answer);
        } catch (Exception e) {
            log.error("Chat completion failed: {}", e.getMessage(), e);
            throw new RuntimeException("model_call_failed", e);
        }
    }

    private String buildUserPrompt(PipelineContext cm){
        String knowledge = this.formatKnowledge(cm) ;
        if(StringUtils.isBlank(knowledge)){
            knowledge = "无知识库上下文" ;
        }
        String query = cm.getQuery() ;
        String rewriteQuery = StringUtils.firstNonBlank(cm.getState().getRewriteQuery(),query) ;
        return  String.format("""
                    ## 用户问题
                    * 原始问题：%s
                    * 改写问题：%s
                    ## 知识库片段
                    %s
                    """,query,rewriteQuery,knowledge) ;
    }

    private String formatKnowledge(PipelineContext cm){
        List<SearchResult> results = !cm.getState().getMergeResult().isEmpty()
                ? cm.getState().getMergeResult()
                : cm.getState().getSearchResult();
        if(CollectionUtils.isEmpty(results)){
            return null ;
        }
        StringBuilder sb = new StringBuilder() ;
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            if (r.contextHeader != null && !r.contextHeader.isBlank()) {
                sb.append("[片段").append(i + 1).append("] ").append(r.contextHeader).append("\n");
            } else {
                sb.append("[片段").append(i + 1).append("]\n");
            }
            sb.append(r.content).append("\n\n");
        }
        return sb.toString() ;
    }
}
