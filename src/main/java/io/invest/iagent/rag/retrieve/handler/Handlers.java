package io.invest.iagent.rag.retrieve.handler;

import io.invest.iagent.rag.retrieve.dto.ChatManage;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.enums.RetrieveMode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class Handlers {

    @Autowired
    private List<Handler> handlers ;

    private Map<String,List<Handler>> sceneMapping ;
    private Map<String,List<Handler>> modeMapping ;

    @PostConstruct
    public void init(){

        Map<String,Handler> handlerMap = handlers.stream()
                .collect(Collectors.toMap(Handler::name,t->t)) ;
        // domain
        Map<String,List<String>> sceneHandles = Map.of(
                "filing",List.of("QUERY_UNDERSTAND","FilingPeriodNormalize","FilingTagParse","FilingTermExpansion"
                        ,"CHUNK_SEARCH_PARALLEL", "CHUNK_RERANK","CHUNK_MERGE",
                        "FILTER_TOP_K","FilingCitation","INTO_CHAT_MESSAGE","CHAT_COMPLETION")
        );
        sceneMapping = this.mapping(sceneHandles,handlerMap) ;
        // mode
        Map<String,List<String>> modeHandles = Map.of(
                RetrieveMode.CHAT.name(),List.of("QUERY_UNDERSTAND","CHAT_COMPLETION"),
                RetrieveMode.HYBRID.name(),List.of("QUERY_UNDERSTAND","CHUNK_SEARCH_PARALLEL",
                        "CHUNK_RERANK","CHUNK_MERGE",
                        "FILTER_TOP_K","INTO_CHAT_MESSAGE","CHAT_COMPLETION")
        );
        modeMapping = this.mapping(modeHandles,handlerMap) ;

    }

    private Map<String,List<Handler>> mapping(Map<String,List<String>> handlerMapping,Map<String,Handler> mapping){
        if (handlerMapping == null || handlerMapping.isEmpty() || mapping == null || mapping.isEmpty()) {
            return Map.of();
        }

        return handlerMapping.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> Optional.ofNullable(entry.getValue())
                                .orElse(Collections.emptyList())
                                .stream()
                                .map(name -> {
                                    Handler handler = mapping.get(name);
                                    if (handler == null) {
                                        throw new IllegalArgumentException("Handler not found for name: [" + name + "]");
                                    }
                                    return handler;
                                })
                                .collect(Collectors.toList())
                ));
    }

    public void execute(PipelineContext ctx, ChatManage cm){
        List<Handler> handlers = this.getHandlers(cm) ;
        if(CollectionUtils.isEmpty(handlers)){
            throw new IllegalArgumentException("handler empty") ;
        }
        handlers.forEach(t->t.handle(ctx,cm));
    }

    private List<Handler> getHandlers(ChatManage chatManage){
        String domain = chatManage.getRequest().getDomain() ;
        if(StringUtils.isNotBlank(domain)){
            List<Handler> handlers = sceneMapping.get(domain) ;
            if(!CollectionUtils.isEmpty(handlers)){
                return handlers ;
            }
        }
        RetrieveMode mode = Optional.ofNullable(chatManage.getRequest().getRetrieveMode())
                .orElse(RetrieveMode.HYBRID);
        return modeMapping.get(mode.name()) ;
    }

}
