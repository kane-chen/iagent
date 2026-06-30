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

    @Data
    public static class FilingProperties {
        private String SecUserAgent = "io/yiying5@gmail.com";
    }

    @Data
    public static class WorkspaceProperties {
        private String baseDir  ;
    }

}
