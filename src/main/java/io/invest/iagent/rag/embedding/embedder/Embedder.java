package io.invest.iagent.rag.embedding.embedder;

import java.util.List;

public interface Embedder {

    float[] embed(String text);

    List<float[]> embedBatch(List<String> texts);

    int dimension();

    String model();

}
