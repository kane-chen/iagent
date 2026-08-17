package io.invest.iagent.rag.chunking.reader;

import io.invest.iagent.rag.model.Document;

import java.util.List;

public interface DocumentReader {

    List<String> supportTypes() ;

    String read(Document doc) ;
}
