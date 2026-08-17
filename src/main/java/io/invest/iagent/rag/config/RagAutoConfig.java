package io.invest.iagent.rag.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RAG 模块自动装配。
 * 由 app.rag.enabled=true 启用，与 filingrag 模块并行共存。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true")
public class RagAutoConfig {

    @Bean(destroyMethod = "close")
    public DataSource ragDataSource(RagProperties config) {
        RagProperties.Datasource ds = config.getDatasource();
        if (StringUtils.isBlank(ds.getUrl())) {
            throw new IllegalStateException("app.rag.datasource.url is required when app.rag.enabled=true");
        }
        HikariDataSource hikari = new HikariDataSource();
        hikari.setJdbcUrl(ds.getUrl());
        hikari.setUsername(ds.getUsername());
        hikari.setPassword(ds.getPassword());
        hikari.setDriverClassName("org.postgresql.Driver");
        hikari.setMaximumPoolSize(8);
        hikari.setPoolName("rag-pool");
        log.info("RAG DataSource initialized: {}", ds.getUrl());
        return hikari;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource ragDataSource) {
        return new JdbcTemplate(ragDataSource);
    }


    // ---- Executor ----

    @Bean(destroyMethod = "shutdown")
    public ExecutorService ragExecutor() {
        AtomicInteger counter = new AtomicInteger(0);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "rag-parallel-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return Executors.newFixedThreadPool(4, factory);
    }


}
