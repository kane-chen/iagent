package io.invest.iagent.rag.retrieve.plugins;

import io.invest.iagent.rag.config.RagConfig;
import io.invest.iagent.rag.retrieve.dto.ChatManage;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.dto.PluginErrorOrNone;
import io.invest.iagent.rag.retrieve.dto.PluginException;
import io.invest.iagent.rag.retrieve.enums.EventType;
import io.invest.iagent.rag.retrieve.model.SearchResult;
import io.invest.iagent.service.filingrag.util.LlmClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * CHUNK_RERANK：基于 LLM 的语义重排序
 */
@Slf4j
public class RerankPlugin implements Plugin {

    private final LlmClient llmClient;
    private final RagConfig config;

    public RerankPlugin(LlmClient llmClient, RagConfig config) {
        this.llmClient = llmClient;
        this.config = config;
    }

    @Override
    public List<EventType> activationEvents() {
        return Collections.singletonList(EventType.CHUNK_RERANK);
    }

    @Override
    public PluginErrorOrNone onEvent(PipelineContext ctx, EventType eventType, ChatManage cm,
                                     Supplier<PluginErrorOrNone> next) throws PluginException {
        List<SearchResult> searchResults = cm.getState().getSearchResult();
        if (!cm.needsRetrieval() || searchResults.isEmpty()) return next.get();

        String rerankModelId = cm.getRequest().rerankModelId;
        if (StringUtils.isBlank(rerankModelId)) {
            // 未配置 rerank model，直接将 searchResult 作为 rerankResult
            cm.getState().setRerankResult(new ArrayList<>(searchResults));
            return next.get();
        }

        try {
            String query = StringUtils.defaultIfBlank(cm.getState().getRewriteQuery(), cm.getQuery());
            List<SearchResult> reranked = SemanticRerankUtil.rerank(query, searchResults, llmClient);

            int topK = Math.min(config.getSearch().getRerankTopK(), reranked.size());
            cm.getState().setRerankResult(new ArrayList<>(reranked.subList(0, topK)));
            log.debug("Rerank completed, topK={} from {}", topK, searchResults.size());
        } catch (Exception e) {
            log.warn("Rerank failed, using original order: {}", e.getMessage());
            cm.getState().setRerankResult(new ArrayList<>(searchResults));
        }

        return next.get();
    }
}
