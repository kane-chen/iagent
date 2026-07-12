package io.invest.iagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Application configuration properties from application.properties.
 * <p>
 * 只承载 <b>agent 主流程</b>必需的配置：LLM、workspace 目录。
 * <p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class ApplicationProperties {

    /**
     * LLM (Large Language Model) configuration properties.
     */
    private LlmProperties llm = new LlmProperties();

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
        private String model = "qwen3.5:4b";

        private Integer maxTokens = 32*1024 ;
    }

    @Data
    public static class WorkspaceProperties {
        private String baseDir  ;
    }

}
