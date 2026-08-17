package io.invest.iagent.rag.retrieve.handler;

import io.invest.iagent.rag.retrieve.dto.ChatManage;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.enums.EventType;
import io.invest.iagent.rag.retrieve.enums.RetrieveMode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.utils.Lists;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

import static io.invest.iagent.rag.retrieve.enums.EventType.*;

@Slf4j
@Service
public class Handlers {

    @Autowired
    private List<Handler> handlers ;

    private Map<EventType,List<Handler>> listeners;

    private Map<RetrieveMode,List<EventType>> pipeline ;

    @PostConstruct
    public void init(){
        // mapping
        listeners = Optional.ofNullable(handlers).orElse(Lists.newArrayList())
                .stream()
                .flatMap(handler -> handler.activationEvents().stream()
                        .filter(Objects::nonNull)
                        .map(type -> new AbstractMap.SimpleEntry<>(type, handler)))
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())
                ));
        // 同事件多 handler 时按 @Order 排序，支持应用层定义确定的处理顺序
        listeners.values().forEach(AnnotationAwareOrderComparator::sort);
        // pipeline
        pipeline = Map.of(
                RetrieveMode.CHAT,List.of(LOAD_HISTORY, QUERY_UNDERSTAND, CHAT_COMPLETION_STREAM),
                RetrieveMode.HYBRID,List.of(LOAD_HISTORY,QUERY_UNDERSTAND,CHUNK_SEARCH_PARALLEL,
                        CHUNK_RERANK,WEB_FETCH,CHUNK_MERGE,
                        FILTER_TOP_K,INTO_CHAT_MESSAGE,CHAT_COMPLETION_STREAM)
        ) ;
    }

    public void execute(PipelineContext ctx, ChatManage cm){
        List<EventType> events = this.buildRagPipeline(cm);
        for (EventType event: events) {
            log.debug("Pipeline event: {}", event);
            List<Handler> handlers = listeners.get(event) ;
            if(CollectionUtils.isEmpty(handlers)){
                continue ;
            }
            handlers.forEach(t->t.onEvent(ctx,event,cm));
        }
    }

    private List<EventType> buildRagPipeline(ChatManage cm) {
        List<EventType> types = pipeline.get(cm.getRequest().getRetrieveMode()) ;
        if(Objects.isNull(types)){
            types = pipeline.get(RetrieveMode.HYBRID) ;
        }
        return types;
    }

}
