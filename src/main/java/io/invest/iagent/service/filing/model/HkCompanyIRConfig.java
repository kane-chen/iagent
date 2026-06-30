package io.invest.iagent.service.filing.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 港股公司投资者关系配置
 */
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
    public static class CompanyConfig {
        private String stockCode;
        private String name;
        private String nameEn;
        private String irPageUrl;
        private String pdfUrlPattern;
        private List<String> reportTypes;
        private List<Integer> annualReportMonths;
        private List<Integer> interimReportMonths;
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
