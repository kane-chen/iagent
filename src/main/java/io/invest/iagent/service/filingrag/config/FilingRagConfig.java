package io.invest.iagent.service.filingrag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the filing RAG QA subsystem.
 * Bound to prefix {@code app.filing-rag}.
 */
@Data
@ConfigurationProperties(prefix = "app.filing-rag")
public class FilingRagConfig {

    private boolean enabled = false;
    private String backend = "milvus";

    private Chunk chunk = new Chunk();
    private Search search = new Search();
    private Ollama ollama = new Ollama();
    private Llm llm = new Llm();
    private Milvus milvus = new Milvus();
    private Ragflow ragflow = new Ragflow();
    private TextSearch textSearch = new TextSearch();

    @Data
    public static class Chunk {
        private int targetTokens = 400;
        private int maxTokens = 600;
        private int overlapTokens = 80;
    }

    @Data
    public static class Search {
        private int topK = 5;
        private double similarityThreshold = 0.30;
        /** Multiplier for initial vector recall before keyword/filter post-processing. */
        private int vectorMultiplier = 3;
    }

    @Data
    public static class Ollama {
        private String embedUrl = "http://localhost:11434/api/embed";
        private String embedModel = "qwen3-embedding:4b";
        private int dimension = 2560;
    }

    @Data
    public static class Llm {
        private String baseUrl = "http://localhost:11434/v1";
        private String apiKey = "";
        private String model = "qwen3.5:4b";
        private double temperature = 0.2;
        private int maxTokens = 2048;
    }

    @Data
    public static class Milvus {
        private String endpoint = "http://127.0.0.1:19530";
        private String token = "";
        private String collection = "invest_filing";
        private int insertBatchSize = 100;
    }

    @Data
    public static class Ragflow {
        private String baseUrl = "http://localhost:9380";
        private String apiKey = "";
        private String datasetPrefix = "filing_rag_";
        private double similarityThreshold = 0.3;
        private double keywordWeight = 0.3;
        private int parsePollTimeoutSeconds = 300;
        private int parsePollIntervalSeconds = 3;
        private int requestTimeoutSeconds = 60;
    }

    @Data
    public static class TextSearch {
        /** 关键词检索后送给LLM rerank的候选chunk数 */
        private int rerankTopN = 15;
        /** 全文回退时单文档最大chunk数（防止上下文爆炸） */
        private int fullTextMaxChunks = 50;
        /** 是否启用全文回退 */
        private boolean fullTextFallback = true;
        /** LLM调用超时秒数 */
        private int llmTimeoutSeconds = 180;
        /** 最低BM25分数阈值（低于此分数视为未命中，触发全文回退） */
        private double minKeywordScore = 0.01;
    }
}
