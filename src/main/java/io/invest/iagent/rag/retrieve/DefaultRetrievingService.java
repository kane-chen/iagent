package io.invest.iagent.rag.retrieve;

import io.invest.iagent.rag.config.RagProperties;
import io.invest.iagent.rag.model.RetrieveRequest;
import io.invest.iagent.rag.model.RetrieveResult;
import io.invest.iagent.rag.model.RetrieveResultItem;
import io.invest.iagent.rag.retrieve.dto.*;
import io.invest.iagent.rag.retrieve.handler.Handlers;
import io.invest.iagent.rag.service.RetrievingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class DefaultRetrievingService implements RetrievingService {

    @Autowired
    private Handlers handlers ;

    @Autowired
    private RagProperties ragProperties;

    @Override
    public RetrieveResult retrieve(RetrieveRequest request) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        try {
            // context
            PipelineRuntime runtime = new PipelineRuntime( null, null, traceId);
            PipelineRequest pipelineRequest = PipelineRequest.from(request, ragProperties);
            PipelineState state = new PipelineState();
            PipelineContext context = new PipelineContext(pipelineRequest, state, runtime);
            // execute
            handlers.execute(runtime,context);
            // result
            List<SearchResult> results = !state.mergeResult.isEmpty()
                    ? state.mergeResult : state.searchResult;
            List<RetrieveResultItem> items = results.stream()
                    .map(SearchResult::toRetrieveResultItem)
                    .toList();
            RetrieveResult result = new RetrieveResult();
            result.setSessionId(request.getSessionId());
            result.setUserId(request.getUserId());
            result.setQuery(request.getQuery());
            result.setChatResponse(state.getChatResponse());
            result.setItems(items);
            return result;
        } catch (Exception e) {
            log.error("Retrieve failed: {}", e.getMessage(), e);
            throw new RuntimeException("RAG retrieve failed", e);
        }
    }
}
