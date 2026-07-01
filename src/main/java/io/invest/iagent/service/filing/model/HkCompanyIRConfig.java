package io.invest.iagent.service.filing.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 港股公司投资者关系配置
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class HkCompanyIRConfig {

    private List<CompanyConfig> companies;
    private Metadata metadata;

    public List<CompanyConfig> getCompanies() {
        return companies;
    }

    public void setCompanies(List<CompanyConfig> companies) {
        this.companies = companies;
    }

    public Metadata getMetadata() {
        return metadata;
    }

    public void setMetadata(Metadata metadata) {
        this.metadata = metadata;
    }

    /**
     * 根据股票代码查找公司配置
     */
    public CompanyConfig findCompanyByCode(String stockCode) {
        if (companies == null) {
            return null;
        }
        return companies.stream()
                .filter(c -> c.getStockCode().equals(stockCode))
                .findFirst()
                .orElse(null);
    }

    /**
     * 公司配置
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CompanyConfig {
        private String stockCode;
        private String name;
        private String nameEn;
        private String irPageUrl;
        /**
         * 季度业绩专用 IR 页面（可选）。
         * 部分公司（如腾讯）主 IR 页面只列年报/中期报告，季度业绩另有专门页面 —
         * 例如 https://www.tencent.com/zh-cn/investors/quarter-result.html
         * 若配置了此字段，请求季度报告时会优先抓取此 URL 而不是 {@link #irPageUrl}。
         */
        private String quarterlyPageUrl;
        private String pdfUrlPattern;
        private List<String> reportTypes;
        private List<Integer> annualReportMonths;
        private List<Integer> interimReportMonths;
        /**
         * 季度报告发布月份约定，格式：["Q1:5", "Q2:8", "Q3:11", "Q4:3"] 或 ["Q1:5", "Q3:11"]。
         * 用于把"发布日期的月份"映射回具体的会计季度 —— 因为季度页面上不容易靠正文关键词
         * 明确区分 Q1/Q2/Q3/Q4，但发布时间点是稳定的。
         * 空则退化到旧的启发式：3=Q4/FY、5=Q1、8=Q2/H1、11=Q3。
         */
        private List<String> quarterlyReportMonths;
        private boolean supportsQuarterly;
        private String remarks;

        public String getStockCode() {
            return stockCode;
        }

        public void setStockCode(String stockCode) {
            this.stockCode = stockCode;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getNameEn() {
            return nameEn;
        }

        public void setNameEn(String nameEn) {
            this.nameEn = nameEn;
        }

        public String getIrPageUrl() {
            return irPageUrl;
        }

        public void setIrPageUrl(String irPageUrl) {
            this.irPageUrl = irPageUrl;
        }

        public String getQuarterlyPageUrl() {
            return quarterlyPageUrl;
        }

        public void setQuarterlyPageUrl(String quarterlyPageUrl) {
            this.quarterlyPageUrl = quarterlyPageUrl;
        }

        public List<String> getQuarterlyReportMonths() {
            return quarterlyReportMonths;
        }

        public void setQuarterlyReportMonths(List<String> quarterlyReportMonths) {
            this.quarterlyReportMonths = quarterlyReportMonths;
        }

        /**
         * 依据 quarterlyReportMonths 把发布月份映射为 Q1/Q2/Q3/Q4。
         * 找不到匹配返回 null（调用方要么用启发式兜底，要么直接跳过）。
         */
        public String quarterFromReleaseMonth(int releaseMonth) {
            if (quarterlyReportMonths == null) return null;
            for (String entry : quarterlyReportMonths) {
                if (entry == null) continue;
                int colon = entry.indexOf(':');
                if (colon <= 0 || colon >= entry.length() - 1) continue;
                String q = entry.substring(0, colon).trim().toUpperCase();
                try {
                    int m = Integer.parseInt(entry.substring(colon + 1).trim());
                    if (m == releaseMonth) return q;
                } catch (NumberFormatException ignored) {}
            }
            return null;
        }

        public String getPdfUrlPattern() {
            return pdfUrlPattern;
        }

        public void setPdfUrlPattern(String pdfUrlPattern) {
            this.pdfUrlPattern = pdfUrlPattern;
        }

        public List<String> getReportTypes() {
            return reportTypes;
        }

        public void setReportTypes(List<String> reportTypes) {
            this.reportTypes = reportTypes;
        }

        public List<Integer> getAnnualReportMonths() {
            return annualReportMonths;
        }

        public void setAnnualReportMonths(List<Integer> annualReportMonths) {
            this.annualReportMonths = annualReportMonths;
        }

        public List<Integer> getInterimReportMonths() {
            return interimReportMonths;
        }

        public void setInterimReportMonths(List<Integer> interimReportMonths) {
            this.interimReportMonths = interimReportMonths;
        }

        public boolean isSupportsQuarterly() {
            return supportsQuarterly;
        }

        public void setSupportsQuarterly(boolean supportsQuarterly) {
            this.supportsQuarterly = supportsQuarterly;
        }

        public String getRemarks() {
            return remarks;
        }

        public void setRemarks(String remarks) {
            this.remarks = remarks;
        }

        /**
         * 检查是否支持指定的报告类型
         */
        public boolean supportsReportType(String reportType) {
            if (reportTypes == null) {
                return false;
            }
            return reportTypes.contains(reportType);
        }

        /**
         * 检查月份是否属于年报发布期
         */
        public boolean isAnnualReportMonth(int month) {
            return annualReportMonths != null && annualReportMonths.contains(month);
        }

        /**
         * 检查月份是否属于中期报告发布期
         */
        public boolean isInterimReportMonth(int month) {
            return interimReportMonths != null && interimReportMonths.contains(month);
        }
    }

    /**
     * 元数据
     */
    public static class Metadata {
        private String version;
        private String lastUpdated;
        private String description;

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getLastUpdated() {
            return lastUpdated;
        }

        public void setLastUpdated(String lastUpdated) {
            this.lastUpdated = lastUpdated;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}
