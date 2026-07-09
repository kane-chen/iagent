package io.invest.iagent.service.filingrag.answer;

import io.invest.iagent.service.filingrag.model.FilingAnswer;
import io.invest.iagent.service.filingrag.model.FilingChunk;

import java.util.List;

public interface AnswerSynthesizer {
    FilingAnswer answer(String question, List<FilingChunk> citedChunks, String backendName);
}
