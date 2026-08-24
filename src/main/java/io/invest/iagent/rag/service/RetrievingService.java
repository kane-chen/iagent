package io.invest.iagent.rag.service;

import io.invest.iagent.rag.model.RetrieveRequest;
import io.invest.iagent.rag.model.RetrieveResult;

public interface RetrievingService {

    RetrieveResult retrieve(RetrieveRequest request) ;
}
