package io.invest.iagent.rag.retrieve;

import io.invest.iagent.rag.config.RagProperties;
import io.invest.iagent.rag.model.RetrieveRequest;
import io.invest.iagent.rag.model.RetrieveResultItem;
import io.invest.iagent.rag.retrieve.dto.*;
import io.invest.iagent.rag.retrieve.event.EventBus;
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
    public List<RetrieveResultItem> retrieve(RetrieveRequest request) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        try {
            // context
            PipelineRequest pipelineRequest = PipelineRequest.from(request, ragProperties);
            PipelineState state = new PipelineState();
            PipelineContext context = new PipelineContext(new EventBus(), null, null, traceId);
            // execute
            ChatManage chatManage = new ChatManage(pipelineRequest, state, context);
            handlers.execute(context,chatManage);
            // result
            List<SearchResult> results = !state.mergeResult.isEmpty()
                    ? state.mergeResult : state.searchResult;
            return results.stream()
                    .map(SearchResult::toRetrieveResultItem)
                    .toList();
        } catch (Exception e) {
            log.error("Retrieve failed: {}", e.getMessage(), e);
            throw new RuntimeException("RAG retrieve failed", e);
        }
    }
}
