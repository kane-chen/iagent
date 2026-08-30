package io.invest.iagent.financial.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 财务表结构初始化：启动时幂等执行 db/financial-schema.sql（CREATE TABLE IF NOT EXISTS）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.financial", name = "enabled", havingValue = "true")
public class FinancialSchemaInitializer {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        try {
            String sql = FinancialAutoConfig.readSchema();
            // pgjdbc 支持单次执行多条以分号分隔的 DDL
            jdbcTemplate.execute(sql);
            log.info("Financial schema initialized (idempotent CREATE TABLE IF NOT EXISTS)");
        } catch (Exception e) {
            log.error("Financial schema init failed: {}", e.getMessage(), e);
            throw new IllegalStateException("financial schema init failed", e);
        }
    }
}
