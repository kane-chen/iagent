package io.invest.iagent.service.kb.category;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class FilingCategoryClassification {
    String category;
    double confidence;
    String source;
    String subcategory;

    public static FilingCategoryClassification of(FilingContentCategory category, double confidence, String source) {
        return FilingCategoryClassification.builder()
                .category(category.code())
                .confidence(confidence)
                .source(source)
                .build();
    }
}
