package io.invest.iagent.rag;

import io.invest.iagent.rag.model.ChunkingConfig;
import io.invest.iagent.rag.model.Document;
import io.invest.iagent.rag.model.RetrieveRequest;
import io.invest.iagent.rag.model.RetrieveResultItem;

import java.util.List;

public interface RagService {

    void save(Document doc, ChunkingConfig chunkingConfig) ;

    List<RetrieveResultItem> retrieve(RetrieveRequest request) ;

}
