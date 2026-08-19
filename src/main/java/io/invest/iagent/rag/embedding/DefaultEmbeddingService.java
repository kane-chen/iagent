package io.invest.iagent.rag.embedding;

import io.invest.iagent.rag.config.RagProperties;
import io.invest.iagent.rag.embedding.embedder.Embedder;
import io.invest.iagent.rag.model.Chunk;
import io.invest.iagent.rag.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认 Embedding 服务：委托 Ollama 生成向量
 */
@Service
@Slf4j
public class DefaultEmbeddingService implements EmbeddingService {

    @Autowired
    private Embedder embedder ;
    @Autowired
    private RagProperties config ;

    @Override
    public float[] embedding(String content) {
        return embedder.embed(content);
    }

    @Override
    public void embedding(List<Chunk> chunks) {
        if (CollectionUtils.isEmpty(chunks)){
            return;
        }
        int dimension = embedder.dimension();
        chunks.forEach(t->{
            String text = t.getContent();
            if (StringUtils.isNotBlank(t.getContextHeader())) {
                text = t.getContextHeader() + "\n" + text;
            }
            float[] vectors = embedder.embed(text);
            t.setEmbedding(vectors);
            t.setDimension(dimension);
        });
    }

    private void embedding(List<Chunk> chunks, int batchSize, int dimension) {
        // 构建 embedding 文本（context-Header + content）
        List<String> texts = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            String text = chunk.getContent();
            if (StringUtils.isNotBlank(chunk.getContextHeader())) {
                text = chunk.getContextHeader() + "\n" + text;
            }
            texts.add(text);
        }
        // 分批调用 embedding
        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> batch = texts.subList(i, end);
            List<float[]> vectors = embedder.embedBatch(batch);

            for (int j = 0; j < vectors.size(); j++) {
                Chunk chunk = chunks.get(i + j);
                chunk.setEmbedding(vectors.get(j));
                chunk.setDimension(dimension);
            }
            log.debug("Embedded batch {}/{}, size={}", i + batch.size(), texts.size(), batch.size());
        }
    }
}
