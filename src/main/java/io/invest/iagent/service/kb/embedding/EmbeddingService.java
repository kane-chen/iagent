package io.invest.iagent.service.kb.embedding;

import java.util.List;

public interface EmbeddingService {
    List<Float> embed(String text);
    int dimension();
    String model();
}
