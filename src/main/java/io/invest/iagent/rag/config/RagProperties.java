package io.invest.iagent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 模块配置
 */
@Component
@Data
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    private boolean enabled = false;

    private Chunk chunk = new Chunk();
    private Embedding embedding = new Embedding();
    private Search search = new Search();
    private LlmModel llm = new LlmModel();
    private LlmModel rerank = new LlmModel();
    private Datasource datasource = new Datasource();

    @Data
    public static class Chunk {
        /** 分块策略: AUTO, HEADING, HEURISTIC, RECURSIVE, LEGACY */
        private String strategy = "AUTO";
        private boolean parentChild = true;
        private int parentSize = 4096;
        private int childSize = 384;
        private int chunkSize = 512;
        private int chunkOverlap = 80;
    }

    @Data
    public static class Embedding {
        private String url = "http://localhost:11434/api/embed";
        private String model = "qwen3-embedding:4b";
        private int dimension = 2560;
        private int batchSize = 16;
    }

    @Data
    public static class Search {
        private int vectorTopK = 30;
        private int keywordTopK = 30;
        private int rerankTopK = 5;
        private double vectorThreshold = 0.2;
        private double keywordThreshold = 0.3;
        private int rrfK = 60;
        private double rrfVectorWeight = 0.7;
        private double rrfKeywordWeight = 0.3;
    }

    @Data
    public static class LlmModel {
        private String baseUrl = "http://localhost:11434/v1";
        private String model = "qwen3.5:4b";
        private String apiKey = "";
        private double temperature = 0.2;
        private int maxTokens = 2048;
        private int timeoutSeconds = 180;
        /**
         * 服务提供者，仅 rerank 使用：ollama（默认）、xreference（Xinference）。
         * llm 配置中该字段无实际作用。
         */
        private String provider = "ollama";
    }

    @Data
    public static class Datasource {
        private String url;
        private String username;
        private String password;
    }
}
