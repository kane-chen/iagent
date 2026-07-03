package io.invest.iagent.service.kb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 财报知识库（KB）模块的独立配置根。
 *
 * <p>KB 对 agent 是<b>可选组件</b> —— 只有 {@code app.kb.enabled=true} 时，
 * {@link KnowledgeBaseAutoConfig} 才会创建 {@code FilingKnowledgeBaseService} 及其后端 bean。
 * 应用主流程与 agent 的 skill 调用（{@code financial-filing-retrieve} Python skill）
 * 不依赖本模块，即使 KB 关闭 agent 也能正常工作。</p>
 *
 * <p>配置分层：</p>
 * <ul>
 *   <li>{@link #enabled} — 模块总开关，默认 false</li>
 *   <li>{@link #backend} — 检索后端选型：{@code milvus}（本地默认）/ {@code ragflow}（外部服务）</li>
 *   <li>{@link #retrieval} — 通用检索参数（top-k、去重比例等），两种后端共享</li>
 *   <li>{@link #milvus} — Milvus 向量库连接配置（backend=milvus 时使用）</li>
 *   <li>{@link #embedding} — Embedding 模型连接配置（backend=milvus 时使用）</li>
 *   <li>{@link #ragflow} — RAGFlow 外部服务配置（backend=ragflow 时使用）</li>
 * </ul>
 *
 * <p>启用 KB 时把 {@code application.properties} 里 {@code app.kb.enabled} 打开即可；
 * 各子块的连接参数默认能跑本地 docker 起的 milvus/ollama，直接用。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.kb")
public class KnowledgeBaseConfig {

    /**
     * KB 模块总开关。默认 {@code false} —— agent 主流程与 skill 检索都不依赖它，
     * 只有需要在 Java 侧执行 preprocess / build / list / delete 时才打开。
     */
    private boolean enabled = false;

    /**
     * 后端类型：{@code milvus}（本地默认）或 {@code ragflow}（外部服务）。
     */
    private String backend = "milvus";

    private Retrieval retrieval = new Retrieval();
    private Milvus milvus = new Milvus();
    private Embedding embedding = new Embedding();
    private Ragflow ragflow = new Ragflow();

    /**
     * 检索通用参数 —— 两种后端共享。
     */
    @Data
    public static class Retrieval {
        /** 向量搜索候选池 top-k。 */
        private int vectorTopK = 50;

        /** 最终返回给用户的 top-k。 */
        private int resultTopK = 5;

        /** summary 候选倍数（resultTopK * multiplier）。 */
        private int summaryCandidateMultiplier = 4;

        /** 是否启用精确匹配封顶。 */
        private boolean enableExactCapping = true;

        /** 精确匹配存在时保留的扩展结果比例。 */
        private double expansionRatio = 0.3;
    }

    /**
     * Milvus 向量库连接配置。
     */
    @Data
    public static class Milvus {
        private String endpoint = "http://127.0.0.1:19530";
        /** 认证 token；空字符串视为无鉴权。 */
        private String token = "";
        private String collection = "invest_filing_test";
        private int dimension = 2560;
    }

    /**
     * Embedding 模型接入配置（本地 Milvus 后端使用）。
     */
    @Data
    public static class Embedding {
        private String baseUrl = "http://localhost:11434/api/embed";
        private String apiKey = "local";
        private String model = "qwen3-embedding:4b";
        private int dimension = 2560;
    }

    /**
     * RAGFlow 外部服务配置。RAGFlow 以 dataset 为单位管理文档，本项目按 ticker 建 dataset
     * （命名 {@code datasetPrefix + ticker}），每份财报作为一个 document 上传并附带 metadata。
     */
    @Data
    public static class Ragflow {
        private String baseUrl = "http://localhost:9380";
        private String apiKey = "";
        private String datasetPrefix = "filing_kb_";
        private String parserMethod = "naive";
        private int chunkTokenNum = 512;
        private float similarityThreshold = 0.2f;
        private float keywordSimilarityWeight = 0.3f;
        /** dataset 使用的 embedding 模型（由 RAGFlow 侧管理），空则用 RAGFlow 默认值。 */
        private String embeddingModel = "";
        private int requestTimeoutSeconds = 60;
        private int parsePollTimeoutSeconds = 300;
        private int parsePollIntervalSeconds = 3;
    }
}
