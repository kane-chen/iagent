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
            // post rank
            reranked = this.postRerank(context,reranked) ;
            // fill
            int topK = Math.min(config.getSearch().getRerankTopK(), reranked.size());
            context.getState().setRerankResult(new ArrayList<>(reranked.subList(0, topK)));
            log.debug("Rerank completed, topK={} from {}", topK, searchResults.size());
        } catch (Exception e) {
            log.warn("Rerank failed, using original order: {}", e.getMessage());
            context.getState().setRerankResult(new ArrayList<>(searchResults));
        }
    }

    /**
     * 重排后置钩子：在相关性重排之后、topK 截断之前对候选列表做二次排序。
     * 默认原样返回；子类可叠加业务排序因子（如财报周期轮转，避免单一周期霸榜导致其它周期丢失）。
     */
    protected List<SearchResult> postRerank(PipelineContext context,List<SearchResult> reranked){
        return reranked ;
    }

}
