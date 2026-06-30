package io.invest.iagent.service.kb.chunk;

import io.invest.iagent.service.kb.category.FilingCategoryClassification;
import io.invest.iagent.service.kb.category.FilingContentCategory;
import io.invest.iagent.service.kb.category.FilingContentCategoryClassifier;
import io.invest.iagent.service.kb.category.RuleBasedFilingContentCategoryClassifier;
import io.invest.iagent.service.kb.model.FilingChunkingContext;
import io.invest.iagent.service.kb.model.KnowledgeBaseChunkDTO;
import org.apache.commons.lang3.StringUtils;

import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.Map;

public class FilingChunkFactory {

    private final FilingContentCategoryClassifier categoryClassifier;

    public FilingChunkFactory() {
        this(new RuleBasedFilingContentCategoryClassifier());
    }

    public FilingChunkFactory(FilingContentCategoryClassifier categoryClassifier) {
        this.categoryClassifier = categoryClassifier;
    }

    public KnowledgeBaseChunkDTO create(FilingChunkingContext context, int index, String chunkText) {
        return create(context, index, chunkText, null);
    }

    public KnowledgeBaseChunkDTO create(FilingChunkingContext context, int index, String chunkText, String sectionTitleOverride) {
        String chunkId = context.getDocumentId() + "_" + sha256(context.getSourceFile().getFileName() + ":" + index + ":" + chunkText).substring(0, 16);
        String sectionTitle = StringUtils.defaultIfBlank(sectionTitleOverride, inferSectionTitle(chunkText));
        FilingCategoryClassification classification = categoryClassifier.classify(context, chunkText, sectionTitle);
        String category = StringUtils.defaultIfBlank(
                FilingContentCategory.normalizeCode(classification.getCategory()),
                FilingContentCategory.OTHER.code());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source_fingerprint", StringUtils.defaultString(context.getMeta().getString("source_fingerprint")));
        metadata.put("text_hash", sha256(chunkText));
        metadata.put("content_category", category);
        metadata.put("category_confidence", classification.getConfidence());
        metadata.put("category_source", classification.getSource());
        if (StringUtils.isNotBlank(classification.getSubcategory())) {
            metadata.put("content_subcategory", classification.getSubcategory());
        }
        return KnowledgeBaseChunkDTO.builder()
                .chunkId(chunkId)
                .score(null)
                .text(chunkText)
                .ticker(context.getTicker())
                .documentId(context.getDocumentId())
                .formType(context.getMeta().getString("form_type"))
                .fiscalYear(context.getMeta().getInteger("fiscal_year"))
                .fiscalPeriod(context.getMeta().getString("fiscal_period"))
                .filingDate(context.getMeta().getString("filing_date"))
                .sourceFileName(context.getSourceFile().getFileName().toString())
                .sectionTitle(sectionTitle)
                .chunkType(context.getChunkType())
                .category(category)
                .citation(citation(context))
                .metadata(metadata)
                .build();
    }

    public String inferSectionTitle(String text) {
        String trimmed = StringUtils.left(text, 120);
        if (StringUtils.containsIgnoreCase(trimmed, "risk factor")) {
            return "Risk Factors";
        }
        if (StringUtils.containsIgnoreCase(trimmed, "management")) {
            return "Management Discussion";
        }
        if (StringUtils.containsIgnoreCase(trimmed, "financial")) {
            return "Financial Information";
        }
        return "Filing Text";
    }

    private String citation(FilingChunkingContext context) {
        return String.format("%s %s %s %s %s", context.getTicker(), context.getDocumentId(),
                StringUtils.defaultString(context.getMeta().getString("form_type")),
                StringUtils.defaultString(context.getMeta().getString("filing_date")),
                context.getSourceFile().getFileName());
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(StringUtils.defaultString(value).getBytes()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
