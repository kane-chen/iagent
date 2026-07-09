package io.invest.iagent.service.filingrag.embed;

import java.util.List;

public interface EmbeddingProvider {
     List<Float> embed(String text);
     List<List<Float>> embedBatch(List<String> texts);
     int dimension(); String model();
 }
