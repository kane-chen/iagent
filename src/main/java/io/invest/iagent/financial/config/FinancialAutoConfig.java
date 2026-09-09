package io.invest.iagent.financial.config;

import com.alibaba.fastjson2.JSON;
import io.invest.iagent.financial.config.prop.FutuFieldMapping;
import io.invest.iagent.financial.config.prop.KeywordDictEntry;
import io.invest.iagent.financial.config.prop.KeywordMetricConfig;
import io.invest.iagent.financial.config.prop.RagExtraMetric;
import io.invest.iagent.financial.model.MetricCatalog;
import io.invest.iagent.financial.model.MetricDef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 财务数据服务自动装配。
 * 由 app.financial.enabled=true 启用；复用 RAG 模块的 PostgreSQL（app.rag.datasource）。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "app.financial", name = "enabled", havingValue = "true")
public class FinancialAutoConfig {

    /** 标准指标目录树 */
    @Bean
    public MetricCatalog metricCatalog() throws IOException {
        Map<String, Object> root = loadYaml("financial/metric-catalog.yml");
        List<MetricDef> defs = JSON.parseArray(JSON.toJSONString(root.get("metrics")), MetricDef.class);
        MetricCatalog catalog = new MetricCatalog(defs);
        log.info("MetricCatalog loaded: {} metrics", catalog.getByCode().size());
        return catalog;
    }

    /** futu 字段映射 */
    @Bean
    public FutuFieldMapping futuFieldMapping() throws IOException {
        FutuFieldMapping mapping = loadYamlAs("financial/futu-field-mapping.yml", FutuFieldMapping.class);
        log.info("FutuFieldMapping loaded: markets={}", mapping.getMarkets() == null ? 0 : mapping.getMarkets().size());
        return mapping;
    }

    /** RAG 补充提取指标清单 */
    @Bean
    public List<RagExtraMetric> ragExtraMetrics() throws IOException {
        Map<String, Object> root = loadYaml("financial/rag-extra-metrics.yml");
        List<RagExtraMetric> metrics = JSON.parseArray(
                JSON.toJSONString(root.get("metrics")), RagExtraMetric.class);
        log.info("RagExtraMetrics loaded: {} metrics", metrics.size());
        return metrics;
    }

    /** 关键字中英（简体/繁体/英文）对照字典 */
    @Bean
    public List<KeywordDictEntry> keywordDict() throws IOException {
        Map<String, Object> root = loadYaml("financial/keyword-dict.yml");
        List<KeywordDictEntry> entries = JSON.parseArray(
                JSON.toJSONString(root.get("keywords")), KeywordDictEntry.class);
        log.info("KeywordDict loaded: {} entries", entries == null ? 0 : entries.size());
        return entries == null ? List.of() : entries;
    }

    /** 公司维度关键字提取配置（哪些公司 × 哪些指标用关键字方式提取） */
    @Bean
    public KeywordMetricConfig keywordMetricConfig() throws IOException {
        KeywordMetricConfig config = loadYamlAs("financial/keyword-metrics.yml", KeywordMetricConfig.class);
        log.info("KeywordMetricConfig loaded: {} companies",
                config.getCompanies() == null ? 0 : config.getCompanies().size());
        return config;
    }

    private static Map<String, Object> loadYaml(String classpath) throws IOException {
        try (InputStream in = new ClassPathResource(classpath).getInputStream()) {
            return new Yaml().load(in);
        }
    }

    private static <T> T loadYamlAs(String classpath, Class<T> type) throws IOException {
        try (InputStream in = new ClassPathResource(classpath).getInputStream()) {
            Map<String, Object> map = new Yaml().load(in);
            return JSON.parseObject(JSON.toJSONString(map), type);
        }
    }

    /** 供初始化器读取 schema 文件 */
    public static String readSchema() throws IOException {
        try (InputStream in = new ClassPathResource("db/financial-schema.sql").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
