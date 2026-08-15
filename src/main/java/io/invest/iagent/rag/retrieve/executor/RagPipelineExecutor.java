package io.invest.iagent.rag.retrieve.executor;

import io.invest.iagent.rag.retrieve.dto.ChatManage;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.dto.PluginErrorOrNone;
import io.invest.iagent.rag.retrieve.dto.PluginException;
import io.invest.iagent.rag.retrieve.enums.EventType;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * RAG Pipeline 执行器：按顺序触发事件链
 */
@Slf4j
public class RagPipelineExecutor {
    private final PluginsManager pluginsManager;

    public RagPipelineExecutor(PluginsManager pluginsManager) {
        this.pluginsManager = pluginsManager;
    }

    public void execute(PipelineContext ctx, ChatManage cm) throws PluginException {
        List<EventType> events = PipelineBuilder.buildRagPipeline(cm);
        for (EventType et : events) {
            log.debug("Pipeline event: {}", et);
            PluginErrorOrNone result = pluginsManager.trigger(ctx, et, cm);
            if (result.hasError()) {
                log.warn("Pipeline event {} returned error: {}", et, result.error.errorType);
                throw result.error;
            }
        }
    }
}
