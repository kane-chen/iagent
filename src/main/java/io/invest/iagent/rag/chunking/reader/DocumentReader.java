package io.invest.iagent.rag.chunking.reader;

import io.invest.iagent.rag.model.Document;

public interface DocumentReader {

    String read(Document doc) ;
}
