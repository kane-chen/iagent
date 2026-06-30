package io.invest.iagent.service.extraction.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.invest.iagent.service.extraction.model.CompanyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * 公司配置加载器
 */
public class CompanyConfigLoader {

    private static final Logger logger = LoggerFactory.getLogger(CompanyConfigLoader.class);

    private ObjectMapper objectMapper;
    private String configPath;

    public CompanyConfigLoader() {
        this.objectMapper = new ObjectMapper();
        this.configPath = "extraction/config/";
    }

    public CompanyConfigLoader(String configPath) {
        this.objectMapper = new ObjectMapper();
        this.configPath = configPath;
    }

    /**
     * 加载公司配置
     */
    public CompanyConfig loadConfig(String companyCode) {
        logger.info("Loading config for company: {}", companyCode);
        
        String fileName = companyCode.toLowerCase() + ".json";
        String resourcePath = configPath + fileName;
        
        try {
            // 尝试从classpath加载
            InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
            if (is != null) {
                CompanyConfig config = objectMapper.readValue(is, CompanyConfig.class);
                logger.info("Successfully loaded config for {} from classpath", companyCode);
                return config;
            }
            
            // 尝试从文件系统加载
            File file = new File(resourcePath);
            if (file.exists()) {
                CompanyConfig config = objectMapper.readValue(file, CompanyConfig.class);
                logger.info("Successfully loaded config for {} from file", companyCode);
                return config;
            }
            
            logger.warn("Config file not found for company: {}", companyCode);
            return null;
            
        } catch (IOException e) {
            logger.error("Failed to load config for company: {}", companyCode, e);
            return null;
        }
    }

    /**
     * 从文件加载配置
     */
    public CompanyConfig loadFromFile(File configFile) {
        try {
            return objectMapper.readValue(configFile, CompanyConfig.class);
        } catch (IOException e) {
            logger.error("Failed to load config from file: {}", configFile.getPath(), e);
            return null;
        }
    }

    /**
     * 从JSON字符串加载配置
     */
    public CompanyConfig loadFromJson(String json) {
        try {
            return objectMapper.readValue(json, CompanyConfig.class);
        } catch (IOException e) {
            logger.error("Failed to load config from JSON string", e);
            return null;
        }
    }
}
