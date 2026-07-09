package io.invest.iagent.service.filingrag.chunker;

import io.invest.iagent.service.filingrag.model.FilingChunk;
import io.invest.iagent.service.filingrag.model.FilingDocumentMeta;

import java.util.List;

/**
 * Splits raw sections into {@link FilingChunk}s with deterministic IDs.
 */
public interface FilingChunker {
    /**
     * Chunk a document's sections into a list of FilingChunk objects.
     *
     * @param meta           document metadata (ticker, formType, etc.)
     * @param sourceFileName name of the source file within the document directory
     * @param sections       extracted raw sections
     * @return list of chunks (never null)
     */
    List<FilingChunk> chunk(FilingDocumentMeta meta, String sourceFileName, List<RawSection> sections);
}
