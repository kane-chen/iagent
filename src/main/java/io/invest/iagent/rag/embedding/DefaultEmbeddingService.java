package io.invest.iagent.rag.embedding;

import io.invest.iagent.rag.config.RagConfig;
import io.invest.iagent.rag.model.Chunk;
import io.invest.iagent.rag.service.EmbeddingService;
import io.invest.iagent.service.filingrag.embed.EmbeddingProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认 Embedding 服务：委托 OllamaEmbeddingProvider 生成向量
 */
@Slf4j
public class DefaultEmbeddingService implements EmbeddingService {

    private final EmbeddingProvider provider;
    private final int batchSize;

    public DefaultEmbeddingService(EmbeddingProvider provider, RagConfig config) {
        this.provider = provider;
        this.batchSize = config.getEmbedding().getBatchSize();
    }

    @Override
    public float[] embedding(String content) {
        List<Float> result = provider.embed(content);
        return toFloatArray(result);
    }

    @Override
    public void embedding(List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return;

        // 构建 embedding 文本（contextHeader + content）
        List<String> texts = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            String text = chunk.getContent();
            if (StringUtils.isNotBlank(chunk.getContextHeader())) {
                text = chunk.getContextHeader() + "\n" + text;
            }
            texts.add(text);
        }

        // 分批调用 embedding
        int dimension = provider.dimension();
        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> batch = texts.subList(i, end);
            List<List<Float>> vectors = provider.embedBatch(batch);

            for (int j = 0; j < vectors.size(); j++) {
                Chunk chunk = chunks.get(i + j);
                chunk.setEmbedding(toFloatArray(vectors.get(j)));
                chunk.setDimension(dimension);
            }
            log.debug("Embedded batch {}/{}, size={}", i + batch.size(), texts.size(), batch.size());
        }
    }

    private float[] toFloatArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }
}
