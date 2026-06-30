package io.invest.iagent.service.extraction.mapper;

import io.invest.iagent.service.extraction.model.MetricDict;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 财务指标映射器
 * 将财报中的原始指标名称映射到标准指标
 */
@Slf4j
public class MetricMapper {

    private final List<MetricDict> metricDictionaries;

    public MetricMapper() {
        this.metricDictionaries = new ArrayList<>();
        initDefaultMetrics();
    }

    /**
     * 初始化默认指标词典
     */
    private void initDefaultMetrics() {
        // 收入类
        MetricDict revenue = new MetricDict("REVENUE", "营业收入", "REVENUE");
        revenue.addSynonym("Revenue");
        revenue.addSynonym("Revenues");
        revenue.addSynonym("收入");
        revenue.addSynonym("营业收入");
        revenue.addSynonym("营收");
        revenue.addSynonym("营业额");
        revenue.addSynonym("销售收入");
        metricDictionaries.add(revenue);

        // 成本类
        MetricDict costOfRevenue = new MetricDict("COST_OF_REVENUE", "营业成本", "COST");
        costOfRevenue.addSynonym("Cost of revenue");
        costOfRevenue.addSynonym("Cost of revenues");
        costOfRevenue.addSynonym("营业成本");
        costOfRevenue.addSynonym("销售成本");
        costOfRevenue.addSynonym("成本");
        metricDictionaries.add(costOfRevenue);

        // 毛利润
        MetricDict grossProfit = new MetricDict("GROSS_PROFIT", "毛利润", "PROFIT");
        grossProfit.addSynonym("Gross profit");
        grossProfit.addSynonym("Gross Profit");
        grossProfit.addSynonym("毛利");
        grossProfit.addSynonym("毛利润");
        metricDictionaries.add(grossProfit);

        // 运营费用
        MetricDict operatingExpenses = new MetricDict("OPERATING_EXPENSES", "运营费用", "EXPENSE");
        operatingExpenses.addSynonym("Operating expenses");
        operatingExpenses.addSynonym("Operating Expenses");
        operatingExpenses.addSynonym("运营费用");
        operatingExpenses.addSynonym("营业费用");
        operatingExpenses.addSynonym("经营费用");
        operatingExpenses.addSynonym("运营开支");
        metricDictionaries.add(operatingExpenses);

        // 研发费用
        MetricDict rdExpenses = new MetricDict("RD_EXPENSES", "研发费用", "EXPENSE");
        rdExpenses.addSynonym("Research and development");
        rdExpenses.addSynonym("R&D expenses");
        rdExpenses.addSynonym("研发费用");
        rdExpenses.addSynonym("研究与开发费用");
        metricDictionaries.add(rdExpenses);

        // 经营利润
        MetricDict operatingIncome = new MetricDict("OPERATING_INCOME", "经营利润", "PROFIT");
        operatingIncome.addSynonym("Operating income");
        operatingIncome.addSynonym("Operating Income");
        operatingIncome.addSynonym("Income from operations");
        operatingIncome.addSynonym("经营利润");
        operatingIncome.addSynonym("营业利润");
        operatingIncome.addSynonym("经营收益");
        operatingIncome.addSynonym("营业收益");
        metricDictionaries.add(operatingIncome);

        // EBIT
        MetricDict ebit = new MetricDict("EBIT", "息税前利润", "PROFIT");
        ebit.addSynonym("EBIT");
        ebit.addSynonym("Earnings Before Interest and Tax");
        ebit.addSynonym("息税前利润");
        metricDictionaries.add(ebit);

        // EBITDA
        MetricDict ebitda = new MetricDict("EBITDA", "息税折旧摊销前利润", "PROFIT");
        ebitda.addSynonym("EBITDA");
        ebitda.addSynonym("Earnings Before Interest, Taxes, Depreciation and Amortization");
        ebitda.addSynonym("息税折旧摊销前利润");
        metricDictionaries.add(ebitda);

        // 调整后EBITA（阿里巴巴常用）
        MetricDict adjustedEbita = new MetricDict("ADJUSTED_EBITA", "调整后EBITA", "PROFIT");
        adjustedEbita.addSynonym("Adjusted EBITA");
        adjustedEbita.addSynonym("adjusted EBITA");
        adjustedEbita.addSynonym("调整后EBITA");
        adjustedEbita.addSynonym("经调整EBITA");
        metricDictionaries.add(adjustedEbita);

        // 净利润
        MetricDict netIncome = new MetricDict("NET_INCOME", "净利润", "PROFIT");
        netIncome.addSynonym("Net income");
        netIncome.addSynonym("Net Income");
        netIncome.addSynonym("Net profit");
        netIncome.addSynonym("净利润");
        netIncome.addSynonym("净收益");
        metricDictionaries.add(netIncome);
    }

    /**
     * 映射原始指标名称到标准指标
     */
    public MetricDict mapMetric(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) {
            return null;
        }

        String cleanName = rawName.trim();
        
        // 精确匹配
        for (MetricDict dict : metricDictionaries) {
            if (dict.matches(cleanName)) {
                log.debug("Matched metric: {} -> {}", rawName, dict.getMetricCode());
                return dict;
            }
        }

        // 模糊匹配（包含关系）
        for (MetricDict dict : metricDictionaries) {
            if (containsMetric(cleanName, dict)) {
                log.debug("Fuzzy matched metric: {} -> {}", rawName, dict.getMetricCode());
                return dict;
            }
        }

        log.debug("No match found for metric: {}", rawName);
        return null;
    }

    /**
     * 检查文本是否包含指标关键词
     */
    private boolean containsMetric(String text, MetricDict dict) {
        if (text == null) {
            return false;
        }
        String lowerText = text.toLowerCase();
        
        // 检查标准名称
        if (dict.getMetricName() != null && lowerText.contains(dict.getMetricName().toLowerCase())) {
            return true;
        }
        
        // 检查同义词
        for (String synonym : dict.getSynonyms()) {
            if (lowerText.contains(synonym.toLowerCase())) {
                // 排除一些特殊情况，比如"operating expenses"不应该匹配"operating income"
                if (isValidMatch(text, synonym)) {
                    return true;
                }
            }
        }
        
        return false;
    }

    /**
     * 验证匹配是否有效（排除误匹配）
     */
    private boolean isValidMatch(String text, String synonym) {
        String lowerText = text.toLowerCase();
        String lowerSynonym = synonym.toLowerCase();
        
        // 特殊处理：operating income 和 operating expenses 要区分
        if (lowerSynonym.contains("operating income") || lowerSynonym.contains("经营利润")) {
            if (lowerText.contains("expense") || lowerText.contains("费用")) {
                return false;
            }
        }
        
        return true;
    }

    /**
     * 获取所有标准指标
     */
    public List<MetricDict> getAllMetrics() {
        return new ArrayList<>(metricDictionaries);
    }

    /**
     * 添加自定义指标
     */
    public void addMetric(MetricDict metric) {
        this.metricDictionaries.add(metric);
    }

    /**
     * 根据编码获取指标
     */
    public MetricDict getMetricByCode(String metricCode) {
        for (MetricDict dict : metricDictionaries) {
            if (dict.getMetricCode().equalsIgnoreCase(metricCode)) {
                return dict;
            }
        }
        return null;
    }

}
