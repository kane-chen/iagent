package io.invest.iagent.rag.service;

import io.invest.iagent.rag.model.RetrieveRequest;
import io.invest.iagent.rag.model.RetrieveResultItem;

import java.util.List;

public interface RetrievingService {

    List<RetrieveResultItem> retrieve(RetrieveRequest request) ;
}
