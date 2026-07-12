package io.invest.iagent.service.filingrag.chunker;

import java.nio.file.Path;
import java.util.List;

/**
 * Extracts raw sections (title + text) from a filing document file (HTML or PDF).
 */
public interface FilingTextExtractor {
    /**
     * Whether this extractor can handle the given content type / file.
     * @param contentType content type of the file
     * @param file file to extract from
     */
    boolean supports(String contentType, Path file);

    /**
     * Extract ordered sections from the given file.
     * @param file file to extract from
     */
    List<RawSectionVO> extract(Path file) throws Exception;
}
