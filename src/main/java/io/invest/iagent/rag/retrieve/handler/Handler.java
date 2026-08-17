package io.invest.iagent.rag.retrieve.handler;

import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.enums.EventType;
import io.invest.iagent.rag.retrieve.dto.ChatManage;

import java.util.Collections;
import java.util.List;

/**
 * 事件处理器
 */
@FunctionalInterface
public interface Handler {

    void onEvent(PipelineContext ctx, EventType eventType, ChatManage cm);

    default List<EventType> activationEvents() {
        return Collections.emptyList();
    }
}
