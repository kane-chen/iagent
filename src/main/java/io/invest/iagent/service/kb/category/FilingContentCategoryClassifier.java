package io.invest.iagent.service.kb.category;

import io.invest.iagent.service.kb.model.FilingChunkingContext;

public interface FilingContentCategoryClassifier {
    FilingCategoryClassification classify(FilingChunkingContext context, String chunkText, String sectionTitle);
}
