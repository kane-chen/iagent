package io.invest.iagent.rag.integration;

import com.zaxxer.hikari.HikariDataSource;
import io.invest.iagent.rag.config.RagConfig;
import io.invest.iagent.service.filingrag.embed.OllamaEmbeddingProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试公共基类。
 * <p>
 * 所有外部依赖（Ollama / PostgreSQL）地址通过系统属性或环境变量覆盖，默认值与
 * application.properties 保持一致：
 * <ul>
 *   <li>{@code rag.ollama.url}  / {@code RAG_OLLAMA_URL}  默认 http://localhost:11434/api/embed</li>
 *   <li>{@code rag.ollama.model} / {@code RAG_OLLAMA_MODEL} 默认 qwen3-embedding:4b</li>
 *   <li>{@code rag.pg.url} / {@code RAG_PG_URL} 默认 jdbc:postgresql://localhost:5432/iagent</li>
 *   <li>{@code rag.pg.user} / {@code RAG_PG_USER} 默认 iagent</li>
 *   <li>{@code rag.pg.password} / {@code RAG_PG_PASSWORD} 默认 空</li>
 *   <li>{@code rag.kb} / {@code RAG_KB} 默认 it_test_kb</li>
 * </ul>
 * <p>
 * 在 IDE 中直接 Run 单个测试即可；若依赖未启动，测试会在 setup 阶段抛出连接异常。
 */
public abstract class RagIntegrationTestSupport {

    protected static final String OLLAMA_URL = prop("rag.ollama.url", "RAG_OLLAMA_URL",
            "http://localhost:11434/api/embed");
    protected static final String OLLAMA_MODEL = prop("rag.ollama.model", "RAG_OLLAMA_MODEL",
            "qwen3-embedding:4b");
    protected static final int EMBED_DIM = Integer.parseInt(
            prop("rag.ollama.dim", "RAG_OLLAMA_DIM", "2560"));

    protected static final String PG_URL = prop("rag.pg.url", "RAG_PG_URL",
            "jdbc:postgresql://localhost:5432/iagent");
    protected static final String PG_USER = prop("rag.pg.user", "RAG_PG_USER", "postgres");
    protected static final String PG_PASSWORD = prop("rag.pg.password", "RAG_PG_PASSWORD", "sagAdmin888");

    protected static final String KB_ID = prop("rag.kb", "RAG_KB", "it_test_kb");

    protected static RagConfig newConfig() {
        RagConfig config = new RagConfig();
        config.setEnabled(true);
        config.getEmbedding().setUrl(OLLAMA_URL);
        config.getEmbedding().setModel(OLLAMA_MODEL);
        config.getEmbedding().setDimension(EMBED_DIM);
        config.getChunk().setParentChild(true);
        config.getChunk().setChildSize(384);
        config.getChunk().setParentSize(2048);
        config.getSearch().setVectorTopK(20);
        config.getSearch().setKeywordTopK(20);
        config.getSearch().setRerankTopK(5);
        return config;
    }

    protected static OllamaEmbeddingProvider newEmbeddingProvider() {
        return new OllamaEmbeddingProvider(OLLAMA_URL, OLLAMA_MODEL, EMBED_DIM);
    }

    protected static HikariDataSource newDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(PG_URL);
        ds.setUsername(PG_USER);
        ds.setPassword(PG_PASSWORD);
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setMaximumPoolSize(4);
        ds.setPoolName("rag-it-pool");
        return ds;
    }

    protected static JdbcTemplate newJdbcTemplate(HikariDataSource ds) {
        return new JdbcTemplate(ds);
    }

    /** 清空指定知识库的数据，测试前后可调用 */
    protected static void cleanKnowledgeBase(JdbcTemplate jdbc, String kbId) {
        jdbc.update("DELETE FROM embeddings WHERE knowledge_base_id = ?", kbId);
    }

    private static String prop(String sysKey, String envKey, String defaultValue) {
        String v = System.getProperty(sysKey);
        if (v != null && !v.isBlank()) return v;
        v = System.getenv(envKey);
        if (v != null && !v.isBlank()) return v;
        return defaultValue;
    }
}
