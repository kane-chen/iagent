package io.invest.iagent.rag.retrieve.plugins;

import io.invest.iagent.rag.config.RagConfig;
import io.invest.iagent.rag.retrieve.dto.ChatManage;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.dto.PluginErrorOrNone;
import io.invest.iagent.rag.retrieve.enums.EventType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * FILTER_TOP_K：控制最终返回的上下文数量
 */
public class FilterTopKPlugin implements Plugin {

    private final RagConfig config;

    public FilterTopKPlugin(RagConfig config) {
        this.config = config;
    }

    @Override
    public List<EventType> activationEvents() {
        return Collections.singletonList(EventType.FILTER_TOP_K);
    }

    @Override
    public PluginErrorOrNone onEvent(PipelineContext ctx, EventType eventType, ChatManage cm,
                                     Supplier<PluginErrorOrNone> next) {
        if (!cm.needsRetrieval() || cm.getState().getMergeResult().isEmpty()) return Plugin.none();

        int topK = Math.min(config.getSearch().getRerankTopK(), cm.getState().getMergeResult().size());
        cm.getState().setMergeResult(new ArrayList<>(cm.getState().getMergeResult().subList(0, topK)));
        return Plugin.none();
    }
}
