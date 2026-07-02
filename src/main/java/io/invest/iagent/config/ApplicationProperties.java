package io.invest.iagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;

/**
 * Application configuration properties from application.properties.
 * <p>
 * This class centralizes all configuration properties used in the application,
 * making it easier to manage and update configuration values.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class ApplicationProperties {

    /**
     * LLM (Large Language Model) configuration properties.
     */
    private LlmProperties llm = new LlmProperties();

    /**
     * Milvus vector database configuration properties.
     */
    private MilvusProperties milvus = new MilvusProperties();

    /**
     * Embedding model configuration properties.
     */
    private EmbeddingProperties embedding = new EmbeddingProperties();

    /**
     * Knowledge base configuration properties.
     */
    private KnowledgeBaseProperties kb = new KnowledgeBaseProperties();

    /**
     * RAGFlow 外部服务配置。仅当 {@code app.kb.backend=ragflow} 时启用。
     */
    private RagflowProperties ragflow = new RagflowProperties();

    private FilingProperties filing = new FilingProperties();

    private WorkspaceProperties workspace = new WorkspaceProperties();

    /**
     * LLM configuration properties.
     */
    @Data
    public static class LlmProperties {
        /**
         * Base URL for the LLM API endpoint.
         */
        private String baseUrl = "http://localhost:11434/";

        /**
         * API key for authenticating with the LLM service.
         */
        private String apiKey = "local";

        /**
         * Name of the LLM model to use.
         */
        private String model = "qwen3:4b";

        private Integer maxTokens = 32*1024 ;
    }

    /**
     * Milvus vector database configuration properties.
     */
    @Data
    public static class MilvusProperties {
        /**
         * Milvus server endpoint URL.
         */
        private String endpoint = "http://127.0.0.1:19530";

        /**
         * Authentication token for Milvus (if required).
         */
        private String token = "";

        /**
         * Collection name for storing document embeddings.
         */
        private String collection = "invest_filing_test";

        /**
         * Dimension of the embedding vectors.
         */
        private int dimension = 2560;
    }

    /**
     * Embedding model configuration properties.
     */
    @Data
    public static class EmbeddingProperties {
        /**
         * Base URL for the embedding API endpoint.
         */
        private String baseUrl = "http://localhost:11434/api/embed";

        /**
         * API key for authenticating with the embedding service.
         */
        private String apiKey = "local";

        /**
         * Name of the embedding model to use.
         */
        private String model = "qwen3-embedding:4b";

        /**
         * Dimension of the embedding vectors produced by the model.
         */
        private int dimension = 2560;
    }

    /**
     * Knowledge base configuration properties.
     */
    @Data
    public static class KnowledgeBaseProperties {
        /**
         * 后端类型：{@code milvus}（本地默认）或 {@code ragflow}（外部服务）。
         */
        private String backend = "milvus";

        /**
         * Number of top-k candidates to retrieve from vector search.
         */
        private int vectorTopK = 50;

        /**
         * Number of top-k results to return to the user.
         */
        private int resultTopK = 5;

        /**
         * Summary candidate top-k multiplier (resultTopK * multiplier).
         */
        private int summaryCandidateMultiplier = 4;

        /**
         * Whether to enable exact match priority capping.
         */
        private boolean enableExactCapping = true;

        /**
         * Ratio of expansion results to keep when exact matches exist.
         */
        private double expansionRatio = 0.3;
    }

    /**
     * RAGFlow 外部服务配置。
     * <p>
     * RAGFlow 以 dataset 为单位管理文档，本项目按 ticker 建 dataset（命名 {@code filing_kb_<TICKER>}），
     * 每份财报作为一个 document 上传并附带 metadata（form_type、fiscal_year 等）。
     */
    @Data
    public static class RagflowProperties {
        /**
         * RAGFlow 服务基础地址，例如 {@code http://localhost:9380}。
         */
        private String baseUrl = "http://localhost:9380";

        /**
         * RAGFlow API Key。
         */
        private String apiKey = "";

        /**
         * dataset 命名前缀，最终名称 = prefix + ticker，与本地 knowledgeBaseId 语义对齐。
         */
        private String datasetPrefix = "filing_kb_";

        /**
         * 上传文档后使用的 chunk parser 方法，默认 naive。
         */
        private String parserMethod = "naive";

        /**
         * chunk 大小（字符或 token，取决于 RAGFlow 版本）。
         */
        private int chunkTokenNum = 512;

        /**
         * 检索时的相似度阈值（0-1），低于该值的 chunk 会被过滤。
         */
        private float similarityThreshold = 0.2f;

        /**
         * 关键字与向量相似度的加权（0-1，向量占比 = 1 - keywordSimilarityWeight）。
         */
        private float keywordSimilarityWeight = 0.3f;

        /**
         * dataset 使用的 embedding 模型（由 RAGFlow 侧管理），空则用 RAGFlow 默认值。
         */
        private String embeddingModel = "";

        /**
         * 单次 HTTP 请求超时秒数。
         */
        private int requestTimeoutSeconds = 60;

        /**
         * 文档 parse 完成的轮询超时秒数。
         */
        private int parsePollTimeoutSeconds = 300;

        /**
         * 文档 parse 完成的轮询间隔秒数。
         */
        private int parsePollIntervalSeconds = 3;
    }

    @Data
    public static class FilingProperties {
        private String SecUserAgent = "io/yiying5@gmail.com";
    }

    @Data
    public static class WorkspaceProperties {
        private String baseDir  ;
    }

}
