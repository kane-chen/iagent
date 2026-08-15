package io.invest.iagent.rag.retrieve.plugins;

import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.dto.PluginErrorOrNone;
import io.invest.iagent.rag.retrieve.dto.PluginException;
import io.invest.iagent.rag.retrieve.enums.EventType;
import io.invest.iagent.rag.retrieve.dto.ChatManage;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * RAG Pipeline 插件接口
 */
@FunctionalInterface
public interface Plugin {
    PluginErrorOrNone onEvent(PipelineContext ctx, EventType eventType, ChatManage cm,
                              Supplier<PluginErrorOrNone> next) throws PluginException;

    static PluginErrorOrNone none() {
        return PluginErrorOrNone.NONE;
    }

    static PluginErrorOrNone error(PluginException e) {
        return new PluginErrorOrNone(e);
    }

    default List<EventType> activationEvents() {
        return Collections.emptyList();
    }
}
