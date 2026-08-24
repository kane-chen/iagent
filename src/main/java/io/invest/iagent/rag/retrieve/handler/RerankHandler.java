package io.invest.iagent.rag.retrieve.handler;

import io.invest.iagent.rag.config.RagProperties;
import io.invest.iagent.rag.reranking.Reranker;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.dto.PipelineRuntime;
import io.invest.iagent.rag.retrieve.dto.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * CHUNK_RERANK：基于 LLM 的语义重排序
 */
@Slf4j
@Service
public class RerankHandler implements Handler {

    @Autowired
    private Reranker reranker;

    @Autowired
    private RagProperties config;


    @Override
    public String name() {
        return "CHUNK_RERANK";
    }

    @Override
    public void handle(PipelineRuntime runtime, PipelineContext context) {
        List<SearchResult> searchResults = context.getState().getSearchResult();
        if (context.ignoreRetrieval() || searchResults.isEmpty()){
            return ;
        }

        try {
            // rerank
            String query = StringUtils.defaultIfBlank(context.getState().getRewriteQuery(), context.getQuery());
            List<SearchResult> reranked = reranker.rerank(query, searchResults);
            // fill
            int topK = Math.min(config.getSearch().getRerankTopK(), reranked.size());
            context.getState().setRerankResult(new ArrayList<>(reranked.subList(0, topK)));
            log.debug("Rerank completed, topK={} from {}", topK, searchResults.size());
        } catch (Exception e) {
            log.warn("Rerank failed, using original order: {}", e.getMessage());
            context.getState().setRerankResult(new ArrayList<>(searchResults));
        }
    }

}
