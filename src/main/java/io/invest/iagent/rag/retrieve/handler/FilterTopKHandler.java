package io.invest.iagent.rag.retrieve.handler;

import io.invest.iagent.rag.config.RagProperties;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.dto.PipelineRuntime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * FILTER_TOP_K：控制最终返回的上下文数量
 */
@Service
public class FilterTopKHandler implements Handler {

    @Autowired
    private RagProperties config;

    @Override
    public String name() {
        return "FILTER_TOP_K";
    }

    @Override
    public void handle(PipelineRuntime runtime, PipelineContext context) {
        if (context.ignoreRetrieval() || context.getState().getMergeResult().isEmpty()) {
            return ;
        }
        int topK = Math.min(config.getSearch().getRerankTopK(), context.getState().getMergeResult().size());
        context.getState().setMergeResult(context.getState().getMergeResult().subList(0, topK));
    }
}
