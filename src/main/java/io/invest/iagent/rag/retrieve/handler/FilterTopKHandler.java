package io.invest.iagent.rag.retrieve.handler;

import io.invest.iagent.rag.config.RagProperties;
import io.invest.iagent.rag.retrieve.dto.ChatManage;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
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
    public void handle(PipelineContext ctx, ChatManage cm) {
        if (!cm.needsRetrieval() || cm.getState().getMergeResult().isEmpty()) {
            return ;
        }
        int topK = Math.min(config.getSearch().getRerankTopK(), cm.getState().getMergeResult().size());
        cm.getState().setMergeResult(cm.getState().getMergeResult().subList(0, topK));
    }
}
