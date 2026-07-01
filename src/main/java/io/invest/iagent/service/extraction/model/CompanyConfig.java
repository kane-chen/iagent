package io.invest.iagent.service.extraction.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 公司配置
 * 包含公司的业务线定义、指标映射规则等
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
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
     * 用于识别港股财报 PDF 中的分部数据（因中文字体乱码，无法靠label匹配）。
     *
     * 支持两种表格布局：
     * <ul>
     *   <li>{@link Layout#SEGMENTS_AS_COLUMNS}：每列一个分部、每行一个指标
     *       （如腾讯财报 page 44 的"分部汇总"表：5 列 VAS/GAMES/.../TOTAL，
     *        多行 收入/毛利/...，单一周期）</li>
     *   <li>{@link Layout#SEGMENTS_AS_ROWS}：每行一个分部、每列一个周期
     *       （如腾讯财报 page 10 的"分部收入"表：5 行 VAS/广告/金科/其他/合计，
     *        每列一个周期，单一指标）</li>
     * </ul>
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PdfColumnMapping {
        public enum Layout {
            /** 每列=分部，每行=指标，单一周期（按 filing context 的当期周期） */
            SEGMENTS_AS_COLUMNS,
            /** 每行=分部，每列=周期，单一指标 */
            SEGMENTS_AS_ROWS
        }

        /**
         * 布局，默认 SEGMENTS_AS_COLUMNS（向后兼容）。
         */
        private Layout layout = Layout.SEGMENTS_AS_COLUMNS;

        /**
         * 表格列数（如 5 表示这是5列的分部表）
         */
        private int columnCount;
        /**
         * SEGMENTS_AS_COLUMNS 布局下，各列对应的分部 segmentCode，按从左到右顺序排列。
         * "TOTAL" 是保留字段表示合计列（自动跳过）。
         * 例如：["VAS", "ONLINE_ADS", "FINTECH", "OTHERS", "TOTAL"]
         */
        private List<String> segmentCodes;
        /**
         * SEGMENTS_AS_COLUMNS 布局下，数据行对应的标准指标编码，按从上到下顺序排列。
         * 例如：["REVENUE", "GROSS_PROFIT"]  表示第一个数据行是收入，第二个是毛利。
         */
        private List<String> metricCodesByRow;

        /**
         * SEGMENTS_AS_ROWS 布局下，各列对应的周期（按 filing context 的 currentPeriod 解析为占位符）：
         *   "CURRENT_Q" -> 当期季度    (2025H1 报 -> 2025Q2)
         *   "PRIOR_Q"   -> 上年同季    (2025H1 报 -> 2024Q2)
         *   "CURRENT_P" -> 当期周期    (2025H1 报 -> 2025H1)
         *   "PRIOR_P"   -> 上年同期    (2025H1 报 -> 2024H1)
         *   也可以直接写死："2025Q2"
         * 例如 H1 报常见的 4 列布局：["CURRENT_Q", "PRIOR_Q", "CURRENT_P", "PRIOR_P"]
         */
        private List<String> periodCodesByColumn;
        /**
         * SEGMENTS_AS_ROWS 布局下，这张表对应的指标（如 "REVENUE" / "GROSS_PROFIT"），
         * 因为单一指标，所有行共用。
         */
        private String metricCode;
        /**
         * SEGMENTS_AS_ROWS 布局下，期望的数据行数（用于和实际行数匹配，避免误抓行数不符的表）。
         * 通常 = segmentCodes.size()，如腾讯 5 行（VAS/广告/金科/其他/合计）。
         */
        private int rowCount;

        /**
         * 可选：只允许此 mapping 匹配指定的 filing period（H1 / Q1 / Q3 / FY / ...）。
         * 例如腾讯 Q3 报的分部表有额外的"脚注列"，需要不同的 periodCodesByColumn 布局，
         * 通过 {@code filingPeriods=["Q3"]} 把该 mapping 限定为 Q3 报专用。
         * 为空则不限制 filing period（默认，向后兼容）。
         */
        private List<String> filingPeriods;

        public PdfColumnMapping() {
            this.segmentCodes = new ArrayList<>();
            this.metricCodesByRow = new ArrayList<>();
            this.periodCodesByColumn = new ArrayList<>();
            this.filingPeriods = new ArrayList<>();
        }
    }
}
