package io.invest.iagent.service.filing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.invest.iagent.service.filing.model.HkCompanyIRConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * 港股IR配置加载器
 */
public class HkIRConfigLoader {

    private static final Logger logger = LoggerFactory.getLogger(HkIRConfigLoader.class);
    private static final String CONFIG_PATH = "filing/hk/company_ir_config.json";

    private static HkCompanyIRConfig config;
    private static final Object lock = new Object();

    /**
     * 加载配置（单例）
     */
    public static HkCompanyIRConfig loadConfig() {
        if (config == null) {
            synchronized (lock) {
                if (config == null) {
                    config = doLoadConfig();
                }
            }
        }
        return config;
    }

    /**
     * 重新加载配置
     */
    public static HkCompanyIRConfig reloadConfig() {
        synchronized (lock) {
            config = doLoadConfig();
            return config;
        }
    }

    private static HkCompanyIRConfig doLoadConfig() {
        try (InputStream is = HkIRConfigLoader.class.getClassLoader().getResourceAsStream(CONFIG_PATH)) {
            if (is == null) {
                logger.warn("HK IR config file not found: {}", CONFIG_PATH);
                return new HkCompanyIRConfig();
            }
            ObjectMapper mapper = new ObjectMapper();
            HkCompanyIRConfig loadedConfig = mapper.readValue(is, HkCompanyIRConfig.class);
            logger.info("Loaded HK IR config: {} companies, version {}",
                    loadedConfig.getCompanies() != null ? loadedConfig.getCompanies().size() : 0,
                    loadedConfig.getMetadata() != null ? loadedConfig.getMetadata().getVersion() : "unknown");
            return loadedConfig;
        } catch (IOException e) {
            logger.error("Failed to load HK IR config", e);
            return new HkCompanyIRConfig();
        }
    }

    /**
     * 获取公司配置
     */
    public static HkCompanyIRConfig.CompanyConfig getCompanyConfig(String stockCode) {
        HkCompanyIRConfig config = loadConfig();
        return config.findCompanyByCode(stockCode);
    }

    /**
     * 检查公司是否支持季度报告
     */
    public static boolean supportsQuarterlyReport(String stockCode) {
        HkCompanyIRConfig.CompanyConfig config = getCompanyConfig(stockCode);
        return config != null && config.isSupportsQuarterly();
    }

    /**
     * 获取公司IR页面URL
     */
    public static String getIrPageUrl(String stockCode) {
        HkCompanyIRConfig.CompanyConfig config = getCompanyConfig(stockCode);
        return config != null ? config.getIrPageUrl() : null;
    }

    /**
     * 获取公司名称
     */
    public static String getCompanyName(String stockCode) {
        HkCompanyIRConfig.CompanyConfig config = getCompanyConfig(stockCode);
        return config != null ? config.getName() : "公司" + stockCode;
    }
}
