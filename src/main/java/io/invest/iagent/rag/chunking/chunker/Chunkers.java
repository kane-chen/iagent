package io.invest.iagent.rag.chunking.chunker;

import io.invest.iagent.rag.chunking.dto.ParsedChunk;
import io.invest.iagent.rag.model.ChunkingConfig;
import jakarta.annotation.PostConstruct;
import org.apache.commons.compress.utils.Lists;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class Chunkers {

    @Autowired
    private List<Chunker> chunkerList;

    private Map<ChunkStrategy,Chunker> chunkerMapping ;

    @PostConstruct
    public void init(){
        chunkerMapping = Optional.ofNullable(chunkerList).orElse(Lists.newArrayList())
                .stream().collect(Collectors.toMap(Chunker::type,t->t)) ;
    }

    public List<ParsedChunk> split(String markdown, ChunkingConfig config){
        Chunker chunker = getChunker(config) ;
        if(Objects.isNull(chunker)){
            throw new UnsupportedOperationException("invalid chunker-strategy:"+config.getStrategy());
        }
        return chunker.split(markdown,config) ;
    }


    private Chunker getChunker(ChunkingConfig config) {
        Chunker chunker = chunkerMapping.get(config.getStrategy()) ;
        if(Objects.isNull(chunker)){
            chunker = chunkerMapping.get(ChunkStrategy.FIX_SIZE) ;

        }
        return chunker ;
    }

}
