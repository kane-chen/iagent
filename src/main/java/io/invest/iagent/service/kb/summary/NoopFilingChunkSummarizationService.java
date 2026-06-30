package io.invest.iagent.service.kb.summary;

import io.invest.iagent.service.kb.model.KnowledgeBaseChunkDTO;

import java.util.Optional;

public class NoopFilingChunkSummarizationService implements FilingChunkSummarizationService {
    @Override
    public Optional<String> summarize(KnowledgeBaseChunkDTO chunk) {
        return Optional.empty();
    }
}
