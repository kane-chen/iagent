package io.invest.iagent.rag.config;

import com.zaxxer.hikari.HikariDataSource;
import io.invest.iagent.rag.DefaultRagService;
import io.invest.iagent.rag.RagService;
import io.invest.iagent.rag.chunking.Chunker;
import io.invest.iagent.rag.chunking.DefaultChunkingService;
import io.invest.iagent.rag.chunking.plugins.FixedSizeChunker;
import io.invest.iagent.rag.chunking.plugins.HeadingAwareChunker;
import io.invest.iagent.rag.chunking.reader.CompositeDocumentReader;
import io.invest.iagent.rag.chunking.reader.DocumentReader;
import io.invest.iagent.rag.embedding.DefaultEmbeddingService;
import io.invest.iagent.rag.repository.ChunkRepository;
import io.invest.iagent.rag.repository.ParadeDbChunkRepository;
import io.invest.iagent.rag.retrieve.executor.PluginsManager;
import io.invest.iagent.rag.retrieve.executor.RagPipelineExecutor;
import io.invest.iagent.rag.retrieve.plugins.Plugin;
import io.invest.iagent.rag.retrieve.plugins.*;
import io.invest.iagent.rag.service.ChunkRepositoryService;
import io.invest.iagent.rag.service.ChunkingService;
import io.invest.iagent.rag.service.EmbeddingService;
import io.invest.iagent.service.filingrag.embed.EmbeddingProvider;
import io.invest.iagent.service.filingrag.embed.OllamaEmbeddingProvider;
import io.invest.iagent.service.filingrag.util.LlmClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RAG 模块自动装配。
 * 由 app.rag.enabled=true 启用，与 filingrag 模块并行共存。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RagConfig.class)
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true")
public class RagAutoConfig {

    @Bean(destroyMethod = "close")
    public DataSource ragDataSource(RagConfig config) {
        RagConfig.Datasource ds = config.getDatasource();
        if (StringUtils.isBlank(ds.getUrl())) {
            throw new IllegalStateException("app.rag.datasource.url is required when app.rag.enabled=true");
        }
        HikariDataSource hikari = new HikariDataSource();
        hikari.setJdbcUrl(ds.getUrl());
        hikari.setUsername(ds.getUsername());
        hikari.setPassword(ds.getPassword());
        hikari.setDriverClassName("org.postgresql.Driver");
        hikari.setMaximumPoolSize(8);
        hikari.setPoolName("rag-pool");
        log.info("RAG DataSource initialized: {}", ds.getUrl());
        return hikari;
    }

    @Bean
    public JdbcTemplate ragJdbcTemplate(DataSource ragDataSource) {
        return new JdbcTemplate(ragDataSource);
    }

    @Bean
    public ChunkRepository chunkRepository(JdbcTemplate ragJdbcTemplate) {
        return new ParadeDbChunkRepository(ragJdbcTemplate);
    }

    // ---- Chunking ----

    @Bean
    public DocumentReader documentReader() {
        return new CompositeDocumentReader();
    }

    @Bean("fixedSizeChunker")
    public Chunker fixedSizeChunker() {
        return new FixedSizeChunker();
    }

    @Bean("headingAwareChunker")
    public Chunker headingAwareChunker() {
        return new HeadingAwareChunker();
    }

    @Bean
    public ChunkingService chunkingService(DocumentReader documentReader,
                                           Map<String, Chunker> chunkers) {
        return new DefaultChunkingService(documentReader, chunkers);
    }

    // ---- Embedding ----

    @Bean
    public EmbeddingProvider ragEmbeddingProvider(RagConfig config) {
        RagConfig.Embedding emb = config.getEmbedding();
        return new OllamaEmbeddingProvider(emb.getUrl(), emb.getModel(), emb.getDimension());
    }

    @Bean
    public EmbeddingService embeddingService(EmbeddingProvider ragEmbeddingProvider, RagConfig config) {
        return new DefaultEmbeddingService(ragEmbeddingProvider, config);
    }

    // ---- LLM ----

    @Bean
    public LlmClient ragLlmClient(RagConfig config) {
        RagConfig.Llm llm = config.getLlm();
        String apiKey = StringUtils.isNotBlank(llm.getApiKey()) ? llm.getApiKey() : null;
        return new LlmClient(llm.getBaseUrl(), llm.getModel(), apiKey, llm.getTimeoutSeconds());
    }

    // ---- Executor ----

    @Bean(destroyMethod = "shutdown")
    public ExecutorService ragExecutor() {
        AtomicInteger counter = new AtomicInteger(0);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "rag-parallel-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return Executors.newFixedThreadPool(4, factory);
    }

    // ---- Repository Service ----

    @Bean
    public ChunkRepositoryService chunkRepositoryService(ChunkRepository chunkRepository) {
        return new ChunkRepositoryService(chunkRepository);
    }

    // ---- Plugins ----

    @Bean
    @Order(10)
    public Plugin queryUnderstandPlugin(LlmClient ragLlmClient, RagConfig config) {
        return new QueryUnderstandPlugin(ragLlmClient, config);
    }

    @Bean
    @Order(20)
    public Plugin searchParallelPlugin(EmbeddingService embeddingService,
                                        ChunkRepository chunkRepository,
                                        ExecutorService ragExecutor,
                                        RagConfig config) {
        return new SearchParallelPlugin(embeddingService, chunkRepository, ragExecutor, config);
    }

    @Bean
    @Order(30)
    public Plugin rerankPlugin(LlmClient ragLlmClient, RagConfig config) {
        return new RerankPlugin(ragLlmClient, config);
    }

    @Bean
    @Order(40)
    public Plugin mergePlugin(ChunkRepository chunkRepository) {
        return new MergePlugin(chunkRepository);
    }

    @Bean
    @Order(50)
    public Plugin filterTopKPlugin(RagConfig config) {
        return new FilterTopKPlugin(config);
    }

    @Bean
    @Order(60)
    public Plugin intoChatMessagePlugin() {
        return new IntoChatMessagePlugin();
    }

    @Bean
    @Order(70)
    public Plugin chatCompletionStreamPlugin(LlmClient ragLlmClient, RagConfig config) {
        return new ChatCompletionStreamPlugin(ragLlmClient, config);
    }

    // ---- Pipeline ----

    @Bean
    public PluginsManager pluginsManager(List<Plugin> plugins) {
        return new PluginsManager(plugins);
    }

    @Bean
    public RagPipelineExecutor ragPipelineExecutor(PluginsManager pluginsManager) {
        return new RagPipelineExecutor(pluginsManager);
    }

    @Bean
    public RagService ragService(ChunkingService chunkingService,
                                  EmbeddingService embeddingService,
                                  ChunkRepositoryService chunkRepositoryService,
                                  RagPipelineExecutor ragPipelineExecutor,
                                  RagConfig ragConfig) {
        return new DefaultRagService(chunkingService, embeddingService,
                chunkRepositoryService, ragPipelineExecutor, ragConfig);
    }
}
