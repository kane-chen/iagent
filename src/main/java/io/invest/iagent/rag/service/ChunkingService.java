package io.invest.iagent.rag.service;

import io.invest.iagent.rag.model.Chunk;
import io.invest.iagent.rag.model.Document;

import java.util.List;

public interface ChunkingService {

    List<Chunk> chunk(Document doc) ;
}
