package io.invest.iagent.rag.reranking;

import io.invest.iagent.rag.retrieve.dto.SearchResult;

import java.util.List;

public interface Reranker {

    List<SearchResult> rerank(String query, List<SearchResult> results) ;

}
