package io.invest.iagent.rag.retrieve.executor;

import io.invest.iagent.rag.retrieve.enums.EventType;
import io.invest.iagent.rag.retrieve.dto.ChatManage;

import java.util.*;

// 根据请求动态组装事件链
public class PipelineBuilder {
    public static List<EventType> buildRagPipeline(ChatManage cm) {
        List<EventType> events = new ArrayList<>();
        events.add(EventType.LOAD_HISTORY);
        events.add(EventType.QUERY_UNDERSTAND);
        if (cm.needsRetrieval()) {
            events.add(EventType.CHUNK_SEARCH_PARALLEL);
            events.add(EventType.CHUNK_RERANK);
            if (cm.getRequest().isWebSearchEnabled()) {
                events.add(EventType.WEB_FETCH);
            }
            events.add(EventType.CHUNK_MERGE);
            events.add(EventType.FILTER_TOP_K);
            // DATA_ANALYSIS 可选
        }
        events.add(EventType.INTO_CHAT_MESSAGE);
        events.add(EventType.CHAT_COMPLETION_STREAM);
        return events;
    }

    // 纯聊天模式（无检索）
    public static List<EventType> buildChatPipeline(ChatManage cm) {
        return Arrays.asList(
                EventType.LOAD_HISTORY,
                EventType.QUERY_UNDERSTAND,
                EventType.CHAT_COMPLETION_STREAM
        );
    }
}


