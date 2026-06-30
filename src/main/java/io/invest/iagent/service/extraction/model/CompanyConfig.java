package io.invest.iagent.service.extraction.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 公司配置
 * 包含公司的业务线定义、指标映射规则等
 */
@Data
public class CompanyConfig {

    private String companyCode;
    private String companyName;
    private String market;
    private String defaultCurrency;
    private String defaultUnit;
    /**
     * 需要保留的周期类型，可选值 FY、H1、H2、Q1、Q2、Q3、Q4，为空则不过滤
     */
    private List<String> includePeriodTypes;
    private List<SegmentConfig> segments;
    private List<MetricMappingRule> metricMappingRules;

    /**
     * PDF 分部表的列映射（用于港股财报，因中文乱码无法依靠label识别分部）
     * 例如腾讯财报的分部表：[VAS, ONLINE_ADS, FINTECH, OTHERS, TOTAL]
     * 数据行的每个数值按顺序对应这些列。配置为空表示走通用HTML解析逻辑。
     */
    private List<PdfColumnMapping> pdfColumnMappings;

    public CompanyConfig() {
        this.segments = new ArrayList<>();
        this.metricMappingRules = new ArrayList<>();
        this.includePeriodTypes = new ArrayList<>();
        this.pdfColumnMappings = new ArrayList<>();
    }

    /**
     * 业务分部配置
     */
    @Data
    public static class SegmentConfig {
        private String segmentCode;
        private String segmentName;
        private List<String> aliases;
        private int level;
        private String parentCode;


        public SegmentConfig() {
            this.aliases = new ArrayList<>();
        }

        /**
         * 检查文本是否匹配该分部
         */
        public boolean matches(String text) {
            if (text == null) {
                return false;
            }
            String lowerText = text.toLowerCase().trim();
            if (segmentName != null && lowerText.contains(segmentName.toLowerCase())) {
                return true;
            }
            if (segmentCode != null && lowerText.contains(segmentCode.toLowerCase())) {
                return true;
            }
            for (String alias : aliases) {
                if (lowerText.contains(alias.toLowerCase())) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 指标映射规则
     */
    @Data
    public static class MetricMappingRule {
        private String standardMetricCode;
        private List<String> rawMetricNames;
        private String formula;

        public MetricMappingRule() {
            this.rawMetricNames = new ArrayList<>();
        }
    }

    /**
     * PDF 表格列映射
     * 用于识别港股财报 PDF 中的分部数据列（因中文字体乱码，无法靠label匹配）
     */
    @Data
    public static class PdfColumnMapping {
        /**
         * 表格列数（如 5 表示这是5列的分部表）
         */
        private int columnCount;
        /**
         * 各列对应的分部 segmentCode，按从左到右顺序排列
         * "TOTAL" 是保留字段表示合计列（自动跳过）
         * 例如：["VAS", "ONLINE_ADS", "FINTECH", "OTHERS", "TOTAL"]
         */
        private List<String> segmentCodes;
        /**
         * 数据行对应的标准指标编码，按从上到下顺序排列
         * 例如：["REVENUE", "GROSS_PROFIT"]  表示第一个数据行是收入，第二个是毛利
         */
        private List<String> metricCodesByRow;

        public PdfColumnMapping() {
            this.segmentCodes = new ArrayList<>();
            this.metricCodesByRow = new ArrayList<>();
        }
    }
}
