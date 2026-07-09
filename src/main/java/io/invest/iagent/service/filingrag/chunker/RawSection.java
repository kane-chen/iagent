package io.invest.iagent.service.filingrag.chunker;

import lombok.Builder;
import lombok.Data;

/**
 * A raw section extracted from a filing document before chunking.
 * Carries the section title, body text, and optional page number.
 */
@Data
@Builder
public class RawSection {
    private String title;
    private String content;
    private Integer pageNumber;
}
