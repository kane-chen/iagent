package io.invest.iagent.service.kb.search.model;

import java.util.List;

/**
 * Section semantic profile.
 *
 * Contains semantic metadata for a document section, including its semantic
 * bucket classification and searchable lexical tokens.
 */
public record SectionSemanticProfile(
        String sectionRef,
        String topic,
        String path,
        String title,
        String item,
        String bucket,
        List<String> lexicalTokens
) {
}
