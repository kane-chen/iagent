package io.invest.iagent.filingkb;

import io.invest.iagent.config.ApplicationProperties;
import io.invest.iagent.filingkb.retrieve.handler.FilingCitationHandler;
import io.invest.iagent.filingkb.retrieve.handler.FilingPeriodNormalizeHandler;
import io.invest.iagent.filingkb.retrieve.handler.FilingTagParseHandler;
import io.invest.iagent.filingkb.retrieve.handler.FilingTermExpansionHandler;
import io.invest.iagent.rag.KnowledgeService;
import io.invest.iagent.rag.chatting.Chatter;
import io.invest.iagent.rag.repository.ChunkRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * filingkb 应用层自动装配。
 * <p>仅当 {@code app.rag.enabled=true}（存在 {@link KnowledgeService}）且
 * {@code app.filing-kb.enabled=true} 时生效。
 * <p>handler 在此以 @Bean 注册（而非 @Service），确保禁用时不被组件扫描拾取。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(FilingKbProperties.class)
@ConditionalOnProperty(prefix = "app.filing-kb", name = "enabled", havingValue = "true")
@ConditionalOnBean(KnowledgeService.class)
public class FilingKbAutoConfig {

    private final Path workspace;
    private final FilingKbProperties properties;

    FilingKbAutoConfig(ApplicationProperties applicationProperties, FilingKbProperties properties) {
        String baseDir = applicationProperties.getWorkspace() != null
                ? applicationProperties.getWorkspace().getBaseDir() : null;
        this.workspace = StringUtils.isBlank(baseDir)
                ? Paths.get(System.getProperty("user.dir")).resolve("workspace")
                : Paths.get(baseDir);
        this.properties = properties;
    }

    @PostConstruct
    void logStartup() {
        log.info("FilingKB enabled: knowledgeBaseId={}, workspace={}",
                properties.getKnowledgeBaseId(), workspace);
    }

    // ---- 服务 ----

    @Bean
    public FilingKbBuildService filingKbBuildService(KnowledgeService knowledgeService,
                                                     FilingKbProperties properties) {
        return new FilingKbBuildService(knowledgeService, properties, workspace);
    }

    @Bean
    public FilingKbQaService filingKbQaService(KnowledgeService knowledgeService,
                                               FilingKbProperties properties) {
        return new FilingKbQaService(knowledgeService, properties);
    }

    // ---- 业务 handler（自动被 Handlers 通过 @Autowired List<Handler> 发现） ----

    @Bean
    public FilingTagParseHandler filingTagParseHandler(Chatter chatter) {
        return new FilingTagParseHandler(chatter);
    }

    @Bean
    public FilingPeriodNormalizeHandler filingPeriodNormalizeHandler(
            ChunkRepository chunkRepository, FilingKbProperties properties) {
        return new FilingPeriodNormalizeHandler(chunkRepository, properties.getKnowledgeBaseId());
    }

    @Bean
    public FilingTermExpansionHandler filingTermExpansionHandler() {
        return new FilingTermExpansionHandler();
    }

    @Bean
    public FilingCitationHandler filingCitationHandler() {
        return new FilingCitationHandler();
    }
}
