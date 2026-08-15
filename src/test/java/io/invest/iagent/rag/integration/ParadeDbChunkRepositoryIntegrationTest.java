package io.invest.iagent.rag.integration;

import com.zaxxer.hikari.HikariDataSource;
import io.invest.iagent.rag.repository.ChunkDO;
import io.invest.iagent.rag.repository.ChunkRetrieveParams;
import io.invest.iagent.rag.repository.ChunkRetrieveResult;
import io.invest.iagent.rag.repository.ParadeDbChunkRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL + pgvector + ParadeDB 仓储集成测试。
 * <p>
 * 需要：
 * <ol>
 *   <li>PostgreSQL 已安装 pgvector、pg_search 扩展；</li>
 *   <li>已执行 src/main/resources/db/rag-schema.sql；</li>
 *   <li>数据库连接信息通过系统属性/环境变量传入（见 RagIntegrationTestSupport）。</li>
 * </ol>
 */
class ParadeDbChunkRepositoryIntegrationTest extends RagIntegrationTestSupport {

    private static HikariDataSource ds;
    private static JdbcTemplate jdbc;
    private static ParadeDbChunkRepository repo;
    private static OllamaEmbeddingRef ref;

    @BeforeAll
    static void init() {
        ds = newDataSource();
        jdbc = newJdbcTemplate(ds);
        repo = new ParadeDbChunkRepository(jdbc);
        // 仅用于生成向量，不做 embedding 质量断言
        ref = new OllamaEmbeddingRef();
    }

    @AfterAll
    static void close() {
        if (ds != null) ds.close();
    }

    @BeforeEach
    void cleanup() {
        cleanKnowledgeBase(jdbc, KB_ID);
    }

    @Test
    void test_batch_insert_and_keyword_search() {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        List<ChunkDO> chunks = List.of(
                newChunk(uid, "c1", "云计算业务收入本季度同比增长20%", "Part II > MD&A"),
                newChunk(uid, "c2", "供应链风险来自地缘政治和原材料价格波动", "Part I > Risk"),
                newChunk(uid, "c3", "研发投入重点投向人工智能基础设施"));

        repo.batchSave(chunks);

        ChunkRetrieveParams params = ChunkRetrieveParams.builder()
                .query("云计算 收入 增长")
                .knowledgeBaseIds(List.of(KB_ID))
                .topK(10)
                .build();
        List<ChunkRetrieveResult> hits = repo.keywordSearch(params);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).getContent()).contains("云计算");
    }

    @Test
    void test_vector_search_returns_semantically_related() {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        List<ChunkDO> chunks = new ArrayList<>();
        chunks.add(newChunk(uid, "cloud", "云计算业务收入本季度同比增长20%", "Part II"));
        chunks.add(newChunk(uid, "risk", "供应链风险来自地缘政治和原材料价格波动", "Part I"));
        chunks.add(newChunk(uid, "ai", "研发投入重点投向人工智能基础设施和大模型训练集群", "Part II"));
        chunks.forEach(c -> c.setEmbedding(ref.embed(c.getContent())));
        repo.batchSave(chunks);

        float[] qvec = ref.embed("AI 算力 训练 模型");
        ChunkRetrieveParams params = ChunkRetrieveParams.builder()
                .queryEmbedding(qvec)
                .knowledgeBaseIds(List.of(KB_ID))
                .topK(3)
                .rrfK(60).rrfVectorWeight(0.7).rrfKeywordWeight(0.3)
                .build();

        List<ChunkRetrieveResult> vectorHits = repo.vectorSearch(params);
        assertThat(vectorHits).isNotEmpty();
        // 语义最相关的 AI 条目排在前列
        assertThat(vectorHits.get(0).getChunkId()).contains("ai");
    }

    @Test
    void test_rrf_fusion_combines_ranks() {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        List<ChunkDO> chunks = new ArrayList<>();
        chunks.add(newChunk(uid, "kw_match", "人工智能技术持续突破", "Tech"));
        chunks.add(newChunk(uid, "vec_match", "GPU集群算力需求旺盛带动数据中心扩张", "Infra"));
        chunks.add(newChunk(uid, "both_match", "人工智能算力需求拉动GPU和数据中心业务增长", "Hybrid"));
        chunks.forEach(c -> c.setEmbedding(ref.embed(c.getContent())));
        repo.batchSave(chunks);

        ChunkRetrieveParams params = ChunkRetrieveParams.builder()
                .query("人工智能 算力 GPU")
                .queryEmbedding(ref.embed("人工智能 算力 GPU 数据中心"))
                .knowledgeBaseIds(List.of(KB_ID))
                .topK(5)
                .rrfK(60).rrfVectorWeight(0.7).rrfKeywordWeight(0.3)
                .build();

        List<ChunkRetrieveResult> kw = repo.keywordSearch(params);
        List<ChunkRetrieveResult> vec = repo.vectorSearch(params);
        List<ChunkRetrieveResult> fused = repo.rrfFuse(kw, vec, params);

        assertThat(fused).isNotEmpty();
        // 同时命中两种检索的条目 RRF 得分最高
        assertThat(fused.get(0).getChunkId()).contains("both_match");
        assertThat(fused.get(0).getMatchType()).isEqualTo("hybrid");
    }

    @Test
    void test_find_by_chunk_ids_returns_parents() {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        ChunkDO parent = newChunk(uid, "p1", "This is the full parent section content with multiple sentences.", "Section A");
        parent.setChunkType("parent_text");
        ChunkDO child = newChunk(uid, "c1", "single sentence", "Section A");
        child.setParentChunkId("p1");
        repo.batchSave(List.of(parent, child));

        List<ChunkRetrieveResult> found = repo.findByChunkIds(List.of("p1"));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getChunkType()).isEqualTo("parent_text");
        assertThat(found.get(0).getContent()).contains("parent section");
    }

    // ---- helpers ----

    private ChunkDO newChunk(String uid, String chunkId, String content, String header) {
        return ChunkDO.builder()
                .sourceId("src-" + uid)
                .sourceType(0)
                .chunkId(chunkId + "-" + uid)
                .knowledgeId("doc-" + uid)
                .knowledgeBaseId(KB_ID)
                .chunkType("text")
                .content(content)
                .contextHeader(header)
                .dimension(EMBED_DIM)
                .embedding(new float[EMBED_DIM]) // 单测关键词检索不需要真实向量
                .isEnabled(Boolean.TRUE)
                .build();
    }

    /** 内部 helper：在仓储测试中懒加载一次 embedding provider */
    static class OllamaEmbeddingRef {
        private final io.invest.iagent.service.filingrag.embed.OllamaEmbeddingProvider p = newEmbeddingProvider();

        float[] embed(String text) {
            List<Float> r = p.embed(text);
            float[] arr = new float[r.size()];
            for (int i = 0; i < r.size(); i++) arr[i] = r.get(i);
            return arr;
        }
    }
}
