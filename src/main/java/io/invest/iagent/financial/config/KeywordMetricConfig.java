package io.invest.iagent.financial.config;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公司维度关键字提取配置（keyword-metrics.yml）：ticker（大写）→ 待提取指标定义列表。
 * 仅在此配置中的公司与指标才会触发关键字提取。
 */
@Data
public class KeywordMetricConfig {

    /** 公司股票代码（大写裸 ticker）→ 指标定义 */
    private Map<String, List<KeywordMetricDef>> companies = new LinkedHashMap<>();
}
