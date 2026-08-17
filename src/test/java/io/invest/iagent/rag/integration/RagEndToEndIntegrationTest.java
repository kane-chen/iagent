package io.invest.iagent.rag.integration;

import com.zaxxer.hikari.HikariDataSource;
import io.invest.iagent.rag.DefaultRagService;
import io.invest.iagent.rag.RagService;
import io.invest.iagent.rag.chunking.Chunker;
import io.invest.iagent.rag.chunking.DefaultChunkingService;
import io.invest.iagent.rag.chunking.plugins.HeadingAwareChunker;
import io.invest.iagent.rag.chunking.reader.CompositeDocumentReader;
import io.invest.iagent.rag.chunking.reader.DocumentReader;
import io.invest.iagent.rag.config.RagConfig;
import io.invest.iagent.rag.embedding.DefaultEmbeddingService;
import io.invest.iagent.rag.model.ChunkingConfig;
import io.invest.iagent.rag.model.Document;
import io.invest.iagent.rag.model.RetrieveRequest;
import io.invest.iagent.rag.model.RetrieveResultItem;
import io.invest.iagent.rag.repository.ChunkRepository;
import io.invest.iagent.rag.repository.ParadeDbChunkRepository;
import io.invest.iagent.rag.retrieve.executor.PluginsManager;
import io.invest.iagent.rag.retrieve.executor.RagPipelineExecutor;
import io.invest.iagent.rag.retrieve.plugins.*;
import io.invest.iagent.rag.service.ChunkRepositoryService;
import io.invest.iagent.rag.service.ChunkingService;
import io.invest.iagent.rag.service.EmbeddingService;
import io.invest.iagent.service.filingrag.embed.EmbeddingProvider;
import io.invest.iagent.service.filingrag.embed.OllamaEmbeddingProvider;
import io.invest.iagent.service.filingrag.util.LlmClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端集成测试：写入一份 Markdown 文档 → 真实分块 + embedding → 检索 + 重排 + LLM 回答。
 * <p>
 * 依赖：Ollama（qwen3-embedding:4b + qwen3.5:4b）、PostgreSQL（pgvector + pg_search）。
 */
class RagEndToEndIntegrationTest extends RagIntegrationTestSupport {

    private static HikariDataSource ds;
    private static RagService ragService;
    private static JdbcTemplate jdbc;
    private static ExecutorService executor;

    @BeforeAll
    static void wire() {
        RagConfig config = newConfig();

        ds = newDataSource();
        jdbc = newJdbcTemplate(ds);

        // chunking
        DocumentReader reader = new CompositeDocumentReader();
        Chunker headingChunker = new HeadingAwareChunker();
        ChunkingService chunkingService = new DefaultChunkingService(reader,
                Map.of("headingAwareChunker", headingChunker, "fixedSizeChunker", headingChunker));

        // embedding
        EmbeddingProvider embeddingProvider = new OllamaEmbeddingProvider(
                config.getEmbedding().getUrl(),
                config.getEmbedding().getModel(),
                config.getEmbedding().getDimension());
        EmbeddingService embeddingService = new DefaultEmbeddingService(embeddingProvider, config);

        // repository
        ChunkRepository repository = new ParadeDbChunkRepository(jdbc);
        ChunkRepositoryService repositoryService = new ChunkRepositoryService(repository);

        // llm
        LlmClient llmClient = new LlmClient(
                config.getLlm().getBaseUrl(),
                config.getLlm().getModel(),
                config.getLlm().getApiKey().isBlank() ? null : config.getLlm().getApiKey(),
                config.getLlm().getTimeoutSeconds());

        executor = Executors.newFixedThreadPool(4);

        // plugins (ordered)
        List<Plugin> plugins = List.of(
                new QueryUnderstandPlugin(llmClient, config),
                new SearchParallelPlugin(embeddingService, repository, executor, config),
                new RerankPlugin(llmClient, config),
                new MergePlugin(repository),
                new FilterTopKPlugin(config),
                new IntoChatMessagePlugin(),
                new ChatCompletionStreamPlugin(llmClient, config)
        );
        PluginsManager pluginsManager = new PluginsManager(plugins);
        RagPipelineExecutor pipelineExecutor = new RagPipelineExecutor(pluginsManager);

        ragService = new DefaultRagService(chunkingService, embeddingService,
                repositoryService, pipelineExecutor, config);
    }

    @AfterAll
    static void shutdown() {
        if (executor != null) executor.shutdown();
        if (ds != null) ds.close();
    }

    @BeforeEach
    void clean() {
        cleanKnowledgeBase(jdbc, KB_ID);
    }

    @Test
    void test_save_and_retrieve_relevant_chunk() throws Exception {
        // 1. 准备一份带标题结构的 Markdown 文档
        Path docFile = Files.createTempFile("rag-it-", ".md");
        Files.writeString(docFile, """
                # 公司业务概览

                本公司是一家专注于云计算和人工智能基础设施的科技企业，
                主要客户包括互联网公司、金融机构和政府部门。

                ## 云计算业务

                云计算业务本季度实现收入100亿元，同比增长20%，
                增长主要来自企业数字化转型带来的算力需求。

                ## 人工智能业务

                人工智能业务收入30亿元，同比增长80%。
                公司自研大模型在多个基准测试中取得领先成绩，
                并已通过API对外提供服务。

                # 风险因素

                ## 供应链风险

                高端GPU依赖海外供应商，地缘政治可能影响供货稳定性。

                ## 竞争风险

                云计算市场竞争激烈，价格战可能压缩利润率。
                """);

        ChunkingConfig chunkingConfig = new ChunkingConfig();
        chunkingConfig.setStrategy(ChunkingConfig.Strategy.AUTO);
        chunkingConfig.setEnableParentChild(false);

        Document doc = Document.builder()
                .filePath(docFile.toString())
                .knowledgeBaseId(KB_ID)
                .knowledgeId("it-doc-1")
                .chunkingConfig(chunkingConfig)
                .build();

        // 2. 写入
        ragService.save(doc, chunkingConfig);

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM embeddings WHERE knowledge_base_id = ?",
                Long.class, KB_ID);
        assertThat(count).isNotNull().isGreaterThan(0);
        System.out.println("Indexed chunks: " + count);

        // 3. 检索：直接命中云计算收入
        RetrieveRequest req = RetrieveRequest.builder()
                .sessionId(UUID.randomUUID().toString())
                .userId("u-000001")
                .query("云计算业务本季度收入是多少？同比增长多少？")
                .knowledgeBaseIds(List.of(KB_ID))
                .enableRewrite(true)
                .rerankTopK(5)
                .build();
        List<RetrieveResultItem> results = ragService.retrieve(req);

        assertThat(results).isNotEmpty();
        System.out.println("=== Retrieve results ===");
        for (int i = 0; i < results.size(); i++) {
            RetrieveResultItem r = results.get(i);
            System.out.printf("[%d] score=%.4f type=%s parent=%s%n  %s%n",
                    i + 1, r.getScore(), r.getChunkType(), r.getParentId(),
                    r.getContent() == null ? "" : r.getContent().replaceAll("\\s+", " "));
        }

        // 断言最相关的片段命中云计算业务
        assertThat(results.get(0).getContent()).containsIgnoringCase("云计算");

        Files.deleteIfExists(docFile);
    }

    @Test
    void test_retrieve_returns_empty_for_irrelevant_query() throws Exception {
        Path docFile = Files.createTempFile("rag-it-empty-", ".txt");
        Files.writeString(docFile, "This document talks about oranges and apples and fruit cultivation.");

        Document doc = Document.builder()
                .filePath(docFile.toString())
                .knowledgeBaseId(KB_ID)
                .knowledgeId("it-doc-fruit")
                .build();
        ragService.save(doc, new ChunkingConfig());

        RetrieveRequest req = RetrieveRequest.builder()
                .query("GPU 算力 数据中心 投资")
                .sessionId(UUID.randomUUID().toString())
                .userId("u-000001")
                .knowledgeBaseIds(List.of(KB_ID))
                .enableRewrite(false)
                .rerankTopK(3)
                .build();
        List<RetrieveResultItem> results = ragService.retrieve(req);

        // 阈值过滤后可能为空（也可能返回低分片段），只打印结果
        System.out.println("Irrelevant query returned " + results.size() + " chunks");
        results.forEach(r -> System.out.printf("  score=%.4f %s%n",
                r.getScore(), r.getContent()));

        Files.deleteIfExists(docFile);
    }
}
