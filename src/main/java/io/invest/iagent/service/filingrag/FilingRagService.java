package io.invest.iagent.service.filingrag;

import io.invest.iagent.service.filingrag.model.FilingAnswer;
import io.invest.iagent.service.filingrag.model.FilingBuildReport;
import io.invest.iagent.service.filingrag.model.FilingQuery;
import io.invest.iagent.service.filingrag.model.FilingQueryResult;

/**
 * Top-level facade for the filing RAG QA subsystem.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>{@link #buildIndex(String, boolean)} / {@link #buildDocument(String, String, boolean)} —
 *       extract text from HTML/PDF filings under {@code workspace/portfolio/&lt;TICKER&gt;/filings/},
 *       chunk it (shared across backends), embed it, and upsert into the configured backend
 *       (Milvus or RAGFlow).</li>
 *   <li>{@link #search(FilingQuery)} — embed the question, run vector (+ meta/keyword) search,
 *       return ranked {@link io.invest.iagent.service.filingrag.model.FilingChunk}s.</li>
 *   <li>{@link #answer(FilingQuery)} — search then synthesize a Chinese answer with [Cn] citations
 *       via the configured {@link io.invest.iagent.service.filingrag.answer.AnswerSynthesizer}.</li>
 *   <li>{@link #delete(String, String)} — delete all chunks for a (ticker, documentId).</li>
 * </ul>
 *
 * <h3>Configuration</h3>
 * Gated by {@code app.filing-rag.enabled=true}. Backend selected via {@code app.filing-rag.backend=milvus|ragflow}.
 * See {@link FilingRagConfig} for all properties.
 *
 * <h3>Agent integration</h3>
 * The {@code filing_qa} and {@code filing_qa_build} agent tools
 * (see {@code io.invest.iagent.tools.filingrag.FilingQaTool}) expose this service to the agent.
 *
 * <h3>Python CLI (dev / manual usage)</h3>
 * A sibling Python skill lives at {@code workspace/skills/financial-qa/} and talks to the same
 * backends directly via HTTP. See {@code SKILL.md} there for CLI usage.
 *
 * <h3>Chunking</h3>
 * Java produces chunks once; both backends receive the same chunk list to guarantee identical
 * retrieval semantics across backends.
 */
public interface FilingRagService {

    /** Build/rebuild the RAG index for all filings under the given ticker. */
    FilingBuildReport buildIndex(String ticker, boolean force);

    /** Build/rebuild the index for a single documentId within the ticker. */
    FilingBuildReport buildDocument(String ticker, String documentId, boolean force);

    /** Delete all chunks for (ticker, documentId). Returns count deleted. */
    int delete(String ticker, String documentId);

    /** Run a search and return raw chunk results (no LLM synthesis). */
    FilingQueryResult search(FilingQuery query);

    /** Search + LLM synthesize an answer with [Cn] citations. */
    FilingAnswer answer(FilingQuery query);
}
