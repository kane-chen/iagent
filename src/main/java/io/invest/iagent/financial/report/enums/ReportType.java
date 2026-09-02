package io.invest.iagent.financial.report.enums;

// ReportType.java
public enum ReportType {
    ANNUAL,      // 年报
    INTERIM,     // 半年报/中报
    QUARTERLY;   // 季报

    public String cnKey() {
        switch (this) {
            case ANNUAL:   return "年度报告";
            case INTERIM:  return "半年度报告";
            case QUARTERLY:return "季度报告";
        }
        return "";
    }
}