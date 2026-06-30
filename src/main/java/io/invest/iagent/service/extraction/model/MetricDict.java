package io.invest.iagent.service.extraction.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 财务指标词典
 */
@Data
public class MetricDict {

    private String metricCode;
    private String metricName;
    private String metricCategory; // REVENUE, COST, EXPENSE, PROFIT
    private List<String> synonyms;
    private String formula;
    private boolean isStandard;

    public MetricDict() {
        this.synonyms = new ArrayList<>();
    }

    public MetricDict(String metricCode, String metricName, String metricCategory) {
        this();
        this.metricCode = metricCode;
        this.metricName = metricName;
        this.metricCategory = metricCategory;
        this.isStandard = true;
    }

    /**
     * 添加同义词
     */
    public void addSynonym(String synonym) {
        this.synonyms.add(synonym);
    }

    /**
     * 检查文本是否匹配该指标
     */
    public boolean matches(String text) {
        if (text == null) {
            return false;
        }
        String lowerText = text.toLowerCase().trim();
        // 检查标准名称
        if (metricName != null && lowerText.contains(metricName.toLowerCase())) {
            return true;
        }
        // 检查编码
        if (metricCode != null && lowerText.contains(metricCode.toLowerCase())) {
            return true;
        }
        // 检查同义词
        for (String synonym : synonyms) {
            if (lowerText.contains(synonym.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
