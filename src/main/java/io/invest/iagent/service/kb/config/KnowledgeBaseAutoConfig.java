package io.invest.iagent.service.kb.config;

import io.invest.iagent.config.ApplicationProperties;
import io.invest.iagent.service.kb.FilingKnowledgeBaseService;
import io.invest.iagent.service.kb.FilingPreprocessService;
import io.invest.iagent.service.kb.backend.KnowledgeBaseBackend;
import io.invest.iagent.service.kb.backend.MilvusKnowledgeBaseBackend;
import io.invest.iagent.service.kb.backend.RagflowKnowledgeBaseBackend;
import io.invest.iagent.service.kb.backend.ragflow.RagflowClient;
import io.invest.iagent.service.kb.embedding.EmbeddingService;
import io.invest.iagent.service.kb.embedding.ModelEmbeddingService;
import io.invest.iagent.service.kb.vector.VectorStoreService;
import io.invest.iagent.service.kb.vector.VectorStoreServiceByMilvus;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 知识库（KB）模块的独立 Spring 装配入口。
 *
 * <p>KB 对 agent 是<b>可选组件</b> —— 通过 {@code app.kb.enabled=true} 启用；
 * 关闭时（默认关）本类不会实例化任何 bean，{@link FilingKnowledgeBaseService} 及其
 * 后端 {@link KnowledgeBaseBackend} 不进入 Spring 上下文，主 agent 与 skill 调用不受影响。</p>
 *
 * <p>装配拆分为两块：</p>
 * <ol>
 *   <li>{@link KnowledgeBaseConfig} — 配置根，独立于 {@code ApplicationProperties}</li>
 *   <li>本类 — 根据 {@code kbConfig.backend} 选择 Milvus / RAGFlow 后端构造 bean</li>
 * </ol>
 *
 * <p>历史上这些 bean 挂在 {@code AgentConfig} 里，配置项散在 {@code ApplicationProperties}
 * 的 4 个嵌套类中（milvus / embedding / kb / ragflow），耦合到 agent 主流程；
 * 现已剥离到独立模块。</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(KnowledgeBaseConfig.class)
@ConditionalOnProperty(prefix = "app.kb", name = "enabled", havingValue = "true")
public class KnowledgeBaseAutoConfig {

    private Path workspace;

    @Autowired
    private KnowledgeBaseConfig kbConfig;

    @Autowired
    private ApplicationProperties applicationProperties;

    @PostConstruct
    public void init() {
        String workSpaceBaseDir = applicationProperties.getWorkspace().getBaseDir();
        if (StringUtils.isBlank(workSpaceBaseDir)) {
            workspace = Paths.get(System.getProperty("user.dir")).resolve("workspace");
        } else {
            workspace = Paths.get(workSpaceBaseDir);
        }
        log.info("KnowledgeBase module ENABLED: backend={}, workspace={}", kbConfig.getBackend(), workspace);
    }

    @Bean
    public FilingKnowledgeBaseService filingKnowledgeBaseService() {
        String backendType = StringUtils.lowerCase(
                StringUtils.defaultIfBlank(kbConfig.getBackend(), "milvus"));
        KnowledgeBaseBackend backend = "ragflow".equals(backendType)
                ? buildRagflowBackend()
                : buildMilvusBackend();
        return new FilingKnowledgeBaseService(backend);
    }

    @Bean("milvusKnowledgeBaseBackend")
    public KnowledgeBaseBackend buildMilvusBackend() {
        KnowledgeBaseConfig.Embedding embeddingProps = kbConfig.getEmbedding();
        EmbeddingService embeddingService = new ModelEmbeddingService(
                embeddingProps.getBaseUrl(),
                embeddingProps.getApiKey(),
                embeddingProps.getModel(),
                1024);

        KnowledgeBaseConfig.Milvus milvusProps = kbConfig.getMilvus();
        String token = milvusProps.getToken().isBlank() ? null : milvusProps.getToken();
        VectorStoreService vectorStoreService = new VectorStoreServiceByMilvus(
                milvusProps.getEndpoint(), token, milvusProps.getCollection());

        FilingPreprocessService preprocessService = new FilingPreprocessService(workspace);
        return new MilvusKnowledgeBaseBackend(preprocessService, embeddingService, vectorStoreService);
    }

    @Bean("ragflowKnowledgeBaseBackend")
    public KnowledgeBaseBackend buildRagflowBackend() {
        RagflowClient client = new RagflowClient(kbConfig.getRagflow());
        return new RagflowKnowledgeBaseBackend(workspace, client, kbConfig.getRagflow());
    }
}
