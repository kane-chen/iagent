package io.invest.iagent.rag.integration;

import io.invest.iagent.rag.embedding.DefaultEmbeddingService;
import io.invest.iagent.rag.model.Chunk;
import io.invest.iagent.service.filingrag.embed.OllamaEmbeddingProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Ollama embedding 集成测试。
 * 需要本地启动 Ollama 并拉取 qwen3-embedding:4b 模型。
 */
class OllamaEmbeddingIntegrationTest extends RagIntegrationTestSupport {

    @Test
    void test_single_embedding_dimension_and_cache() {
        OllamaEmbeddingProvider provider = newEmbeddingProvider();

        List<Float> v1 = provider.embed("阿里巴巴云计算业务收入增长");
        List<Float> v2 = provider.embed("阿里巴巴云计算业务收入增长");
        List<Float> v3 = provider.embed("腾讯游戏业务季度收入");

        assertThat(v1).hasSize(EMBED_DIM);
        // 相同文本相同向量（缓存命中）
        assertThat(v1).isEqualTo(v2);
        // 不同文本向量不同
        assertThat(v1).isNotEqualTo(v3);

        // 余弦相似性自检：相同语义的文本应比不同语义的文本更相似
        double same = cosine(v1, toArray(v3));
        double same2 = cosine(v1, toArray(v2));
        assertThat(same2).isGreaterThan(same);
    }

    @Test
    void test_batch_embedding_populates_chunks() {
        DefaultEmbeddingService service = new DefaultEmbeddingService(newEmbeddingProvider(), newConfig());

        List<Chunk> chunks = new ArrayList<>();
        chunks.add(Chunk.builder().chunkId("c1").content("云计算收入同比增长20%").contextHeader("Part II > Item 7").build());
        chunks.add(Chunk.builder().chunkId("c2").content("供应链风险来自地缘政治").contextHeader("Part I > Item 1A").build());
        chunks.add(Chunk.builder().chunkId("c3").content("研发投入主要投向AI基础设施").build());

        service.embedding(chunks);

        for (Chunk c : chunks) {
            assertThat(c.getEmbedding()).isNotNull().hasSize(EMBED_DIM);
            assertThat(c.getDimension()).isEqualTo(EMBED_DIM);
        }
    }

    private double cosine(List<Float> a, float[] b) {
        float[] aa = toArray(a);
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < aa.length; i++) {
            dot += aa[i] * b[i];
            na += aa[i] * aa[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }
}
