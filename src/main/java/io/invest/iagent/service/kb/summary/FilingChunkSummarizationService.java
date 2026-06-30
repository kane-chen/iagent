package io.invest.iagent.service.kb.summary;

import io.invest.iagent.service.kb.model.KnowledgeBaseChunkDTO;

import java.util.Optional;

public interface FilingChunkSummarizationService {
    Optional<String> summarize(KnowledgeBaseChunkDTO chunk);

    default String model() {
        return "none";
    }
}
