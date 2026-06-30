package io.invest;

import io.invest.iagent.config.ApplicationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

@Configuration
@ComponentScan(basePackages = "io.invest.iagent")
@TestPropertySource(locations = "classpath:test.properties")
@EnableConfigurationProperties(ApplicationProperties.class)
public class AgentConfig4Test {

}

