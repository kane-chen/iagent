package io.invest.iagent.service.extraction.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 质量校验结果
 */
@Data
public class ValidationResult {

    private boolean passed;
    private int overallScore;
    private List<ValidationIssue> issues;

    public ValidationResult() {
        this.issues = new ArrayList<>();
        this.passed = true;
        this.overallScore = 100;
    }

    /**
     * 添加校验问题
     */
    public void addIssue(ValidationIssue issue) {
        this.issues.add(issue);
        if (issue.getSeverity() == Severity.ERROR) {
            this.passed = false;
        }
    }

    /**
     * 严重程度
     */
    public enum Severity {
        INFO,       // 信息
        WARNING,    // 警告
        ERROR       // 错误
    }

    /**
     * 校验问题
     */
    @Data
    public static class ValidationIssue {
        private String ruleCode;
        private String message;
        private Severity severity;
        private String segmentName;
        private String metricCode;
        private Double expectedValue;
        private Double actualValue;

        public ValidationIssue() {
            this.severity = Severity.WARNING;
        }

        public ValidationIssue(String ruleCode, String message, Severity severity) {
            this.ruleCode = ruleCode;
            this.message = message;
            this.severity = severity;
        }
    }

    /**
     * 获取错误数量
     */
    public int getErrorCount() {
        return (int) issues.stream()
                .filter(i -> i.getSeverity() == Severity.ERROR)
                .count();
    }

    /**
     * 获取警告数量
     */
    public int getWarningCount() {
        return (int) issues.stream()
                .filter(i -> i.getSeverity() == Severity.WARNING)
                .count();
    }
}
