package io.invest.iagent.service.kb.chunk;

import io.invest.iagent.service.kb.model.FilingChunkingContext;
import io.invest.iagent.service.kb.model.KnowledgeBaseChunkDTO;

import java.util.List;

public interface FilingChunkingStrategy {
    String name();

    List<KnowledgeBaseChunkDTO> chunk(FilingChunkingContext context, String text);
}
