package io.invest.iagent.rag.retrieve.handler;

import io.invest.iagent.rag.retrieve.dto.ChatManage;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.enums.EventType;
import io.invest.iagent.rag.retrieve.dto.SearchResult;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * INTO_CHAT_MESSAGE：将检索结果组织为 LLM 上下文
 */
@Service
public class IntoChatMessageHandler implements Handler {

    @Override
    public List<EventType> activationEvents() {
        return Collections.singletonList(EventType.INTO_CHAT_MESSAGE);
    }

    @Override
    public void onEvent(PipelineContext ctx, EventType eventType, ChatManage cm) {
        List<SearchResult> results = !cm.getState().getMergeResult().isEmpty()
                ? cm.getState().getMergeResult()
                : cm.getState().getSearchResult();

        StringBuilder sb = new StringBuilder();
        sb.append("问题：").append(cm.getState().getRewriteQuery() != null
                ? cm.getState().getRewriteQuery() : cm.getQuery()).append("\n\n");
        sb.append("知识库片段：\n");
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            if (r.contextHeader != null && !r.contextHeader.isBlank()) {
                sb.append("[片段").append(i + 1).append("] ").append(r.contextHeader).append("\n");
            } else {
                sb.append("[片段").append(i + 1).append("]\n");
            }
            sb.append(r.content).append("\n\n");
        }
        cm.getState().setRenderedContexts(sb.toString());
    }
}
