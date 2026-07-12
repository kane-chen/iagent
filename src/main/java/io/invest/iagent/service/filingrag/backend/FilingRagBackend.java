package io.invest.iagent.service.filingrag.backend;

import io.invest.iagent.service.filingrag.model.FilingChunk;
import io.invest.iagent.service.filingrag.model.FilingQuery;
import io.invest.iagent.service.filingrag.model.FilingQueryResult;

import java.util.List;

public interface FilingRagBackend {
     String name();
     void healthCheck();
     void upsertDocument(String ticker, String documentId, List<FilingChunk> chunks, List<List<Float>> embeddings);
     int delete(String ticker, String documentId);
     FilingQueryResult search(FilingQuery query, List<Float> queryEmbedding);

     /**
      * 该后端是否需要嵌入向量。基于文本检索的后端可以返回false以跳过embedding计算，降低成本。
      */
     default boolean requiresEmbeddings() { return true; }
 }
