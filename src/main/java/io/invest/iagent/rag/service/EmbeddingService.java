package io.invest.iagent.rag.service;

import io.invest.iagent.rag.model.Chunk;

import java.util.List;

public interface EmbeddingService {

    float[] embedding(String content) ;

    void embedding(List<Chunk> chunks) ;
}
