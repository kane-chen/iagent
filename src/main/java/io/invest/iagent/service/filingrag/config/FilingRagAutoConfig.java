package io.invest.iagent.service.filingrag.config;

import io.invest.iagent.config.ApplicationProperties;
import io.invest.iagent.service.filingrag.DefaultFilingRagService;
import io.invest.iagent.service.filingrag.FilingRagService;
import io.invest.iagent.service.filingrag.answer.AnswerSynthesizer;
import io.invest.iagent.service.filingrag.answer.OllamaChatAnswerSynthesizer;
import io.invest.iagent.service.filingrag.backend.FilingRagBackend;
import io.invest.iagent.service.filingrag.backend.milvus.MilvusFilingRagBackend;
import io.invest.iagent.service.filingrag.backend.ragflow.RagflowFilingRagBackend;
import io.invest.iagent.service.filingrag.backend.textsearch.TextSearchFilingRagBackend;
import io.invest.iagent.service.filingrag.chunker.FilingChunker;
import io.invest.iagent.service.filingrag.chunker.FilingTextExtractor;
import io.invest.iagent.service.filingrag.chunker.HtmlTextExtractor;
import io.invest.iagent.service.filingrag.chunker.OverlapWindowChunker;
import io.invest.iagent.service.filingrag.chunker.PdfTextExtractor;
import io.invest.iagent.service.filingrag.embed.EmbeddingProvider;
import io.invest.iagent.service.filingrag.embed.OllamaEmbeddingProvider;
import io.invest.iagent.service.filingrag.util.LlmClient;
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
import java.util.List;

/**
 * Spring auto-configuration for the filing RAG QA subsystem.
 * <p>
 * Gated by {@code app.filing-rag.enabled=true}. When disabled (the default),
 * no beans from this package enter the context, so the rest of the application
 * is unaffected.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(FilingRagConfig.class)
@ConditionalOnProperty(prefix = "app.filing-rag", name = "enabled", havingValue = "true")
public class FilingRagAutoConfig {

    private Path workspace;

    @Autowired
    private FilingRagConfig filingRagConfig;

    @Autowired
    private ApplicationProperties applicationProperties;

    @PostConstruct
    public void init() {
        String baseDir = applicationProperties.getWorkspace().getBaseDir();
        if (StringUtils.isBlank(baseDir)) {
            workspace = Paths.get(System.getProperty("user.dir")).resolve("workspace");
        } else {
            workspace = Paths.get(baseDir);
        }
        log.info("FilingRAG module ENABLED: backend={}, workspace={}", filingRagConfig.getBackend(), workspace);
    }

    @Bean
    public FilingTextExtractor htmlTextExtractor() {
        return new HtmlTextExtractor();
    }

    @Bean
    public FilingTextExtractor pdfTextExtractor() {
        return new PdfTextExtractor();
    }

    @Bean
    public FilingChunker filingChunker() {
        return new OverlapWindowChunker(
                filingRagConfig.getChunk().getTargetTokens(),
                filingRagConfig.getChunk().getMaxTokens(),
                filingRagConfig.getChunk().getOverlapTokens());
    }

    @Bean
    public EmbeddingProvider embeddingProvider() {
        FilingRagConfig.Ollama props = filingRagConfig.getOllama();
        return new OllamaEmbeddingProvider(props.getEmbedUrl(), props.getEmbedModel(), props.getDimension());
    }

    /**
     * 共享LlmClient：供答案合成、查询改写、语义rerank等所有需要chat/completions的组件使用。
     */
    @Bean
    public LlmClient filingRagLlmClient() {
        FilingRagConfig.Llm props = filingRagConfig.getLlm();
        FilingRagConfig.TextSearch tsProps = filingRagConfig.getTextSearch();
        String apiKey = StringUtils.isNotBlank(props.getApiKey()) ? props.getApiKey() : null;
        return new LlmClient(props.getBaseUrl(), props.getModel(), apiKey, tsProps.getLlmTimeoutSeconds());
    }

    @Bean
    public AnswerSynthesizer answerSynthesizer(LlmClient filingRagLlmClient) {
        FilingRagConfig.Llm props = filingRagConfig.getLlm();
        return new OllamaChatAnswerSynthesizer(
                filingRagLlmClient, props.getTemperature(), props.getMaxTokens());
    }

    @Bean
    public FilingRagBackend filingRagBackend(EmbeddingProvider embeddingProvider, LlmClient filingRagLlmClient) {
        String backendType = StringUtils.lowerCase(
                StringUtils.defaultIfBlank(filingRagConfig.getBackend(), "milvus"));
        if ("ragflow".equals(backendType)) {
            return buildRagflowBackend(embeddingProvider);
        }
        if ("textsearch".equals(backendType)) {
            return buildTextSearchBackend(filingRagLlmClient);
        }
        return buildMilvusBackend(embeddingProvider);
    }

    private FilingRagBackend buildMilvusBackend(EmbeddingProvider embeddingProvider) {
        FilingRagConfig.Milvus props = filingRagConfig.getMilvus();
        String token = props.getToken().isBlank() ? null : props.getToken();
        return new MilvusFilingRagBackend(
                props.getEndpoint(), token, props.getCollection(),
                props.getInsertBatchSize(), embeddingProvider);
    }

    private FilingRagBackend buildRagflowBackend(EmbeddingProvider embeddingProvider) {
        FilingRagConfig.Ragflow props = filingRagConfig.getRagflow();
        return new RagflowFilingRagBackend(props, embeddingProvider);
    }

    private FilingRagBackend buildTextSearchBackend(LlmClient llmClient) {
        FilingRagConfig.TextSearch tsConfig = filingRagConfig.getTextSearch();
        return new TextSearchFilingRagBackend(workspace, tsConfig, llmClient);
    }

    @Bean
    public FilingRagService filingRagService(
            FilingRagBackend backend,
            EmbeddingProvider embeddingProvider,
            AnswerSynthesizer answerSynthesizer,
            FilingChunker chunker,
            List<FilingTextExtractor> extractors) {
        return new DefaultFilingRagService(
                workspace, filingRagConfig, backend, embeddingProvider,
                answerSynthesizer, chunker, extractors);
    }
}
